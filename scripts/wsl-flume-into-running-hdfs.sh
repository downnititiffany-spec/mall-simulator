#!/usr/bin/env bash
set -Eeuo pipefail

# Transfer one completed JSONL file through Flume into an already-running,
# run-scoped single-node HDFS namespace. This helper never starts, formats,
# stops, or deletes HDFS services/data.
readonly HADOOP_HOME=/opt/hadoop-3.3.4
readonly JAVA_HOME=/opt/jdk-8u351
readonly FLUME_HOME=/home/asus/.local/opt/apache-flume-1.11.0-bin
readonly HDFS_AUTHORITY=127.0.0.1:9100

if [[ $# -ne 2 || ! "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$ ]]; then
  echo "用法: $0 <existing-hdfs-run-id> <input-jsonl>" >&2
  exit 2
fi

run_id="$1"
input_file="$2"
readonly run_root="/home/asus/graduationproject-runs/$run_id"
readonly hadoop_conf="$run_root/hadoop-conf"
readonly hdfs_path="/landing/$run_id/raw"

for required in "$HADOOP_HOME/bin/hdfs" "$JAVA_HOME/bin/java" \
                "$FLUME_HOME/bin/flume-ng" "$hadoop_conf/core-site.xml" \
                "$hadoop_conf/hdfs-site.xml"; do
  [[ -e "$required" ]] || { echo "缺少已验证依赖或本 RunId 配置: $required" >&2; exit 3; }
done
[[ -f "$input_file" && -r "$input_file" ]] || { echo "输入 JSONL 不存在或不可读" >&2; exit 3; }

export HADOOP_HOME JAVA_HOME HADOOP_CONF_DIR="$hadoop_conf"
export HADOOP_OPTS="${HADOOP_OPTS:-} -Djava.net.preferIPv4Stack=true"
export FLUME_HOME
export FLUME_JAVA_OPTS="${FLUME_JAVA_OPTS:--Xms64m -Xmx256m} -Djava.net.preferIPv4Stack=true"

port_open() { ss -H -ltn "sport = :$1" 2>/dev/null | grep -q .; }
if ! port_open 9100; then
  echo "NameNode RPC 未监听；本脚本只复用现有 HDFS，不负责启动它" >&2
  exit 4
fi

mapfile -t nn_pids < <(pgrep -f 'org.apache.hadoop.hdfs.server.namenode.NameNode' || true)
mapfile -t dn_pids < <(pgrep -f 'org.apache.hadoop.hdfs.server.datanode.DataNode' || true)
if [[ "${#nn_pids[@]}" -ne 1 || "${#dn_pids[@]}" -ne 1 ]]; then
  echo "当前 HDFS 进程不是唯一单节点 NameNode/DataNode；拒绝继续" >&2
  exit 4
fi
for pid in "${nn_pids[0]}" "${dn_pids[0]}"; do
  if ! tr '\0' '\n' <"/proc/$pid/environ" | grep -Fxq "HADOOP_CONF_DIR=$hadoop_conf"; then
    echo "HDFS 进程未使用指定 RunId 的配置；拒绝写入" >&2
    exit 4
  fi
done

nn_dir="$($HADOOP_HOME/bin/hdfs getconf -confKey dfs.namenode.name.dir | sed 's#^file://##')"
dn_dir="$($HADOOP_HOME/bin/hdfs getconf -confKey dfs.datanode.data.dir | sed 's#^file://##')"
nn_cluster="$(sed -n 's/^clusterID=//p' "$nn_dir/current/VERSION" | head -n 1)"
dn_cluster="$(sed -n 's/^clusterID=//p' "$dn_dir/current/VERSION" | head -n 1)"
if [[ -z "$nn_cluster" || "$nn_cluster" != "$dn_cluster" ]]; then
  echo "NameNode/DataNode namespace 哨兵不一致；拒绝写入" >&2
  exit 4
fi
report="$($HADOOP_HOME/bin/hdfs dfsadmin -report 2>/dev/null)"
safemode="$($HADOOP_HOME/bin/hdfs dfsadmin -safemode get 2>/dev/null)"
if ! grep -q 'Live datanodes (1)' <<<"$report" || ! grep -q 'Safe mode is OFF' <<<"$safemode"; then
  echo "HDFS 未达到单 DataNode 且 Safe mode OFF；拒绝写入" >&2
  exit 4
fi
if ! $HADOOP_HOME/bin/hdfs dfs -test -e "$hdfs_path"; then
  echo "目标 Landing 目录不存在；拒绝创建新 namespace: $hdfs_path" >&2
  exit 5
fi

input_lines="$(wc -l <"$input_file" | tr -d '[:space:]')"
input_sha="$(sha256sum "$input_file" | awk '{print $1}')"
python3 - "$input_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
lines = path.read_text(encoding='utf-8').splitlines()
if not lines:
    raise SystemExit('JSONL 为空')
for number, line in enumerate(lines, 1):
    if not line.strip():
        raise SystemExit(f'JSONL 第 {number} 行为空')
    try:
        value = json.loads(line)
    except Exception as exc:
        raise SystemExit(f'JSONL 第 {number} 行不是有效 JSON: {exc}')
    if not isinstance(value, dict):
        raise SystemExit(f'JSONL 第 {number} 行不是 JSON object')
PY
if [[ "$input_lines" -le 0 ]]; then echo "输入文件没有完整 JSONL 行" >&2; exit 6; fi

agent_root="$(mktemp -d "$run_root/flume-existing-hdfs.XXXXXX")"
spool_dir="$agent_root/spool"
mkdir -p "$spool_dir" "$agent_root/channel/checkpoint" "$agent_root/channel/data" "$agent_root/logs"
agent_id="$(date -u +%Y%m%dT%H%M%S)-$$"
spool_file="$spool_dir/events.jsonl"
cp -- "$input_file" "$spool_file"
if [[ "$(sha256sum "$spool_file" | awk '{print $1}')" != "$input_sha" ]]; then
  echo "输入复制校验失败；尚未启动 Flume" >&2
  exit 7
fi

cat >"$agent_root/flume.conf" <<EOF
a1.sources = r
a1.channels = c
a1.sinks = k
a1.sources.r.type = spooldir
a1.sources.r.spoolDir = $spool_dir
a1.sources.r.fileHeader = true
a1.sources.r.channels = c
a1.channels.c.type = file
a1.channels.c.checkpointDir = $agent_root/channel/checkpoint
a1.channels.c.dataDirs = $agent_root/channel/data
a1.channels.c.capacity = 1000
a1.channels.c.transactionCapacity = 100
a1.sinks.k.type = hdfs
a1.sinks.k.channel = c
a1.sinks.k.hdfs.path = hdfs://$HDFS_AUTHORITY$hdfs_path
a1.sinks.k.hdfs.filePrefix = events_$agent_id
a1.sinks.k.hdfs.fileSuffix = .jsonl
a1.sinks.k.hdfs.fileType = DataStream
a1.sinks.k.hdfs.writeFormat = Text
a1.sinks.k.hdfs.rollInterval = 2
a1.sinks.k.hdfs.rollSize = 0
a1.sinks.k.hdfs.rollCount = 0
a1.sinks.k.hdfs.batchSize = 100
a1.sinks.k.hdfs.useLocalTimeStamp = true
EOF

setsid "$FLUME_HOME/bin/flume-ng" agent --conf "$FLUME_HOME/conf" \
  --conf-file "$agent_root/flume.conf" --name a1 \
  -Dflume.root.logger=INFO,console >"$agent_root/logs/flume.log" 2>&1 </dev/null &
flume_pid=$!
printf '%s\n' "$flume_pid" >"$agent_root/flume.pid"
pgid="$(ps -o pgid= -p "$flume_pid" | tr -d '[:space:]')"
if [[ "$pgid" != "$flume_pid" ]]; then
  echo "Flume 未在独立进程组运行；拒绝对未知进程组执行停止" >&2
  exit 8
fi

stop_flume() {
  if kill -0 "$flume_pid" 2>/dev/null; then
    kill -TERM -- "-$pgid" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$flume_pid" 2>/dev/null || break; sleep 1; done
    kill -KILL -- "-$pgid" 2>/dev/null || true
  fi
  wait "$flume_pid" 2>/dev/null || true
}
trap stop_flume EXIT

completed=0
for _ in $(seq 1 90); do
  if [[ -f "$spool_file.COMPLETED" ]]; then completed=1; break; fi
  if ! kill -0 "$flume_pid" 2>/dev/null; then break; fi
  sleep 1
done
if [[ "$completed" -ne 1 ]]; then
  echo "Flume 未完成 spool 文件；查看 $agent_root/logs/flume.log" >&2
  exit 9
fi
sleep 4
stop_flume
trap - EXIT

mapfile -t hdfs_files < <($HADOOP_HOME/bin/hdfs dfs -ls "$hdfs_path" 2>/dev/null | awk -v prefix="events_$agent_id" '$1 ~ /^-/ && $8 ~ ("/" prefix) {print $8}')
if [[ "${#hdfs_files[@]}" -ne 1 ]]; then
  echo "本次 Flume 输出应恰有一个文件，实际 ${#hdfs_files[@]}；查看 $agent_root/logs/flume.log" >&2
  exit 10
fi
hdfs_lines="$($HADOOP_HOME/bin/hdfs dfs -cat "${hdfs_files[0]}" | wc -l | tr -d '[:space:]')"
hdfs_sha="$($HADOOP_HOME/bin/hdfs dfs -cat "${hdfs_files[0]}" | sha256sum | awk '{print $1}')"
if [[ "$hdfs_lines" != "$input_lines" || "$hdfs_sha" != "$input_sha" ]]; then
  echo "Flume/HDFS 内容校验不匹配：本地行=$input_lines HDFS行=$hdfs_lines" >&2
  exit 11
fi

printf 'runId=%s\ninputLines=%s\ninputSha256=%s\nhdfsUri=%s\nhdfsLines=%s\nhdfsSha256=%s\nhdfsClusterId=%s\nagentEvidence=%s\n' \
  "$run_id" "$input_lines" "$input_sha" "hdfs://${HDFS_AUTHORITY}${hdfs_files[0]}" \
  "$hdfs_lines" "$hdfs_sha" "$nn_cluster" "$agent_root"
