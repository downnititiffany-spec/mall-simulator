#!/usr/bin/env bash
set -Eeuo pipefail

# Task-scoped Flume -> HDFS transfer for the already initialized WSL single-node
# namespace used by the graduation project. This script never formats HDFS and
# refuses to reuse an existing run id or any occupied HDFS endpoint.

readonly BASE_HADOOP_CONF="/home/asus/stage7u_20260919_170729/hadoop-conf"
readonly BASE_NN_DIR="/home/asus/stage7u_20260919_170729/hdfs-nn"
readonly BASE_DN_DIR="/home/asus/stage7u_20260919_170729/hdfs-dn"
readonly HADOOP_HOME="/opt/hadoop-3.3.4"
readonly JAVA_HOME="/opt/jdk-8u351"
readonly FLUME_HOME="/home/asus/.local/opt/apache-flume-1.11.0-bin"
readonly HDFS_AUTHORITY="127.0.0.1:9100"
readonly EXPECTED_CLUSTER_ID="CID-c74c5a0e-ec0e-498e-b5f4-0e06eb0b949f"

usage() {
  echo "用法: $0 <run-id> <input-jsonl>"
  echo "示例: $0 malle2e_261005_01 /home/asus/graduationproject-runs/srcwiz_261005_01/mall/landing/events/2026100512.jsonl"
}

if [[ $# -ne 2 ]]; then
  usage >&2
  exit 2
fi

run_id="$1"
input_file="$2"
if [[ ! "$run_id" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{2,63}$ ]]; then
  echo "拒绝非法 run-id" >&2
  exit 2
fi
if [[ ! -f "$input_file" || ! -r "$input_file" ]]; then
  echo "输入 JSONL 不存在或不可读" >&2
  exit 2
fi

for path in "$HADOOP_HOME/bin/hdfs" "$JAVA_HOME/bin/java" "$FLUME_HOME/bin/flume-ng" \
            "$BASE_HADOOP_CONF/core-site.xml" "$BASE_HADOOP_CONF/hdfs-site.xml" \
            "$BASE_NN_DIR/current/VERSION" "$BASE_DN_DIR/current/VERSION"; do
  [[ -e "$path" ]] || { echo "缺少已验证的运行依赖: $path" >&2; exit 3; }
done

run_root="/home/asus/graduationproject-runs/$run_id"
if [[ -e "$run_root" ]]; then
  echo "拒绝复用已存在的运行目录: $run_root" >&2
  exit 3
fi

cluster_id() {
  sed -n 's/^clusterID=//p' "$1" | head -n 1
}
nn_cluster="$(cluster_id "$BASE_NN_DIR/current/VERSION")"
dn_cluster="$(cluster_id "$BASE_DN_DIR/current/VERSION")"
if [[ "$nn_cluster" != "$EXPECTED_CLUSTER_ID" || "$dn_cluster" != "$EXPECTED_CLUSTER_ID" ]]; then
  echo "HDFS 哨兵与已核验 namespace 不匹配；拒绝启动" >&2
  exit 4
fi
if grep -q "<value>hdfs://$HDFS_AUTHORITY</value>" "$BASE_HADOOP_CONF/core-site.xml"; then
  :
else
  echo "HDFS core-site.xml 中的 RPC 地址与固定约定不匹配" >&2
  exit 4
fi

if ss -ltn 2>/dev/null | grep -Eq "(^|[[:space:]])[^[:space:]]*:$((9100))[[:space:]]"; then
  echo "HDFS RPC 端口 9100 已被占用；拒绝连接或终止未知服务" >&2
  exit 5
fi

mkdir -p "$run_root"/{hadoop-conf,hadoop-pids,hadoop-logs,flume/spool,flume/checkpoint,flume/data,logs}
cp -a "$BASE_HADOOP_CONF/." "$run_root/hadoop-conf/"
# The historical config points to a machine-specific Java 11 path. Patch only
# this run's copied config; never edit the preserved source config.
if ! grep -q '^export JAVA_HOME=' "$run_root/hadoop-conf/hadoop-env.sh" \
   || ! grep -q '^export HADOOP_LOG_DIR=' "$run_root/hadoop-conf/hadoop-env.sh" \
   || ! grep -q '^export HADOOP_PID_DIR=' "$run_root/hadoop-conf/hadoop-env.sh"; then
  echo "复制的 hadoop-env.sh 缺少预期 Java/log/pid 配置；拒绝猜测修补" >&2
  exit 6
fi
sed -i "s|^export JAVA_HOME=.*|export JAVA_HOME=$JAVA_HOME|" "$run_root/hadoop-conf/hadoop-env.sh"
sed -i "s|^export HADOOP_LOG_DIR=.*|export HADOOP_LOG_DIR=$run_root/hadoop-logs|" "$run_root/hadoop-conf/hadoop-env.sh"
sed -i "s|^export HADOOP_PID_DIR=.*|export HADOOP_PID_DIR=$run_root/hadoop-pids|" "$run_root/hadoop-conf/hadoop-env.sh"

input_lines="$(wc -l < "$input_file" | tr -d '[:space:]')"
input_sha="$(sha256sum "$input_file" | awk '{print $1}')"
if [[ "$input_lines" -le 0 ]]; then
  echo "输入文件没有完整 JSONL 行" >&2
  exit 6
fi

cp -- "$input_file" "$run_root/flume/spool/events.jsonl"
readonly hdfs_path="/landing/$run_id/raw"
readonly hdfs_uri="hdfs://$HDFS_AUTHORITY$hdfs_path"

cat >"$run_root/flume/flume.conf" <<EOF
a1.sources = r
a1.channels = c
a1.sinks = k

a1.sources.r.type = spooldir
a1.sources.r.spoolDir = $run_root/flume/spool
a1.sources.r.fileHeader = true
a1.sources.r.channels = c

a1.channels.c.type = file
a1.channels.c.checkpointDir = $run_root/flume/checkpoint
a1.channels.c.dataDirs = $run_root/flume/data
a1.channels.c.capacity = 1000
a1.channels.c.transactionCapacity = 100

a1.sinks.k.type = hdfs
a1.sinks.k.channel = c
a1.sinks.k.hdfs.path = $hdfs_uri
a1.sinks.k.hdfs.filePrefix = events
a1.sinks.k.hdfs.fileSuffix = .jsonl
a1.sinks.k.hdfs.fileType = DataStream
a1.sinks.k.hdfs.writeFormat = Text
a1.sinks.k.hdfs.rollInterval = 2
a1.sinks.k.hdfs.rollSize = 0
a1.sinks.k.hdfs.rollCount = 0
a1.sinks.k.hdfs.batchSize = 100
a1.sinks.k.hdfs.useLocalTimeStamp = true
EOF

export JAVA_HOME HADOOP_HOME
export HADOOP_CONF_DIR="$run_root/hadoop-conf"
export HADOOP_PID_DIR="$run_root/hadoop-pids"
export HADOOP_LOG_DIR="$run_root/hadoop-logs"
export HADOOP_OPTS="${HADOOP_OPTS:-} -Djava.net.preferIPv4Stack=true"

setsid bash -c 'export JAVA_HOME="$1" HADOOP_HOME="$2" HADOOP_CONF_DIR="$3" HADOOP_PID_DIR="$4" HADOOP_LOG_DIR="$5"; "$HADOOP_HOME/bin/hdfs" --daemon start namenode && "$HADOOP_HOME/bin/hdfs" --daemon start datanode' \
  _ "$JAVA_HOME" "$HADOOP_HOME" "$HADOOP_CONF_DIR" "$HADOOP_PID_DIR" "$HADOOP_LOG_DIR" \
  </dev/null >"$run_root/logs/hdfs-start.log" 2>&1

ready=0
for _ in $(seq 1 120); do
  report="$("$HADOOP_HOME/bin/hdfs" dfsadmin -report 2>/dev/null || true)"
  safemode="$("$HADOOP_HOME/bin/hdfs" dfsadmin -safemode get 2>/dev/null || true)"
  if grep -q 'Live datanodes (1)' <<<"$report" && grep -q 'Safe mode is OFF' <<<"$safemode"; then
    ready=1
    break
  fi
  sleep 1
done
if [[ "$ready" -ne 1 ]]; then
  echo "单节点 HDFS 在 120 秒内未达到 1 DataNode 且 Safe mode OFF；查看 $HADOOP_LOG_DIR" >&2
  exit 8
fi

if "$HADOOP_HOME/bin/hdfs" dfs -test -e "$hdfs_path"; then
  echo "目标 HDFS 路径已存在；拒绝覆盖: $hdfs_path" >&2
  exit 9
fi
"$HADOOP_HOME/bin/hdfs" dfs -mkdir -p "$hdfs_path"

export FLUME_HOME
export FLUME_JAVA_OPTS="${FLUME_JAVA_OPTS:--Xms64m -Xmx256m} -Djava.net.preferIPv4Stack=true"
"$FLUME_HOME/bin/flume-ng" agent --conf "$FLUME_HOME/conf" \
  --conf-file "$run_root/flume/flume.conf" --name a1 \
  -Dflume.root.logger=INFO,console >"$run_root/logs/flume.log" 2>&1 &
flume_pid=$!
printf '%s\n' "$flume_pid" >"$run_root/flume.pid"

completed=0
for _ in $(seq 1 90); do
  if [[ -f "$run_root/flume/spool/events.jsonl.COMPLETED" ]]; then
    sleep 4 # Allow the HDFS sink's short roll interval to close its output file.
    completed=1
    break
  fi
  if ! kill -0 "$flume_pid" 2>/dev/null; then
    break
  fi
  sleep 1
done
kill -TERM "$flume_pid" 2>/dev/null || true
wait "$flume_pid" 2>/dev/null || true
if [[ "$completed" -ne 1 ]]; then
  echo "Flume 未完成 spool 文件；查看 $run_root/logs/flume.log" >&2
  exit 10
fi

mapfile -t hdfs_files < <("$HADOOP_HOME/bin/hdfs" dfs -ls "$hdfs_path" 2>/dev/null | awk '$1 ~ /^-/ {print $8}')
if [[ "${#hdfs_files[@]}" -ne 1 ]]; then
  echo "HDFS 目标应有且仅有一个输出文件，实际 ${#hdfs_files[@]}；查看 $run_root/logs/flume.log" >&2
  exit 11
fi
hdfs_lines="$("$HADOOP_HOME/bin/hdfs" dfs -cat "${hdfs_files[0]}" | wc -l | tr -d '[:space:]')"
hdfs_sha="$("$HADOOP_HOME/bin/hdfs" dfs -cat "${hdfs_files[0]}" | sha256sum | awk '{print $1}')"
if [[ "$hdfs_lines" != "$input_lines" || "$hdfs_sha" != "$input_sha" ]]; then
  echo "Flume/HDFS 内容校验不匹配：本地行=$input_lines HDFS行=$hdfs_lines" >&2
  exit 12
fi

printf 'runId=%s\ninput=%s\ninputLines=%s\ninputSha256=%s\nhdfsUri=%s\nhdfsLines=%s\nhdfsSha256=%s\nhdfsClusterId=%s\n' \
  "$run_id" "$input_file" "$input_lines" "$input_sha" "$hdfs_files[0]" \
  "$hdfs_lines" "$hdfs_sha" "$EXPECTED_CLUSTER_ID"
echo "HDFS NameNode/DataNode 保持运行以供后续摄取/Spark 联调；停止时只停止本任务启动的进程，不删除目录或数据。"
