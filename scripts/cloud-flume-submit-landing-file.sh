#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Submit one completed Mall JSONL file through a task-scoped Flume
# Spooling Directory -> File Channel -> HDFS chain. It reuses the running
# NameNode and never starts, stops, formats, or deletes HDFS data.

readonly TASK_ROOT=${CLOUD_SINGLE_NODE_ROOT:-/home/asus/graduationproject-runs}
readonly E2E_ROOT="$TASK_ROOT/cloud-e2e"
readonly FLUME_HOME=${CLOUD_FLUME_HOME:-/home/asus/.local/opt/apache-flume-1.11.0-bin}

usage() {
  echo "Usage: $0 <current-cloud-run-id> <completed-mall-jsonl>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
run_id=$1
input_file=$2
[[ "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{5,40}$ ]] || usage
readonly run_dir="$E2E_ROOT/$run_id"
readonly run_root_file="$run_dir/run-id"
[[ -r "$run_root_file" && "$(<"$run_root_file")" == "$run_id" ]] || {
  echo 'RunId marker is missing or mismatched; refusing to write.' >&2; exit 3;
}
[[ "$(readlink -f "$E2E_ROOT/current")" == "$(readlink -f "$run_dir")" ]] || {
  echo 'Requested RunId is not the current task stack; refusing to write.' >&2; exit 3;
}

input_real=$(realpath -e -- "$input_file") || { echo 'Input file is missing.' >&2; exit 3; }
mall_events_real=$(realpath -e -- "$run_dir/mall-landing/events") || {
  echo 'Run-scoped Mall events directory is missing.' >&2; exit 3;
}
case "$input_real" in
  "$mall_events_real"/*.jsonl) ;;
  *) echo 'Input must be a JSONL file under this RunId mall-landing/events.' >&2; exit 3 ;;
esac
[[ -f "$input_real" && -r "$input_real" && ! -L "$input_file" ]] || {
  echo 'Input must be a readable regular file, not a symlink.' >&2; exit 3;
}
[[ -x "$FLUME_HOME/bin/flume-ng" ]] || { echo 'Verified Flume executable is unavailable.' >&2; exit 3; }

nn_port=${CLOUD_HDFS_RPC_PORT:-19000}
mapfile -t nn_pids < <(ss -H -ltnp "sport = :$nn_port" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sort -u)
[[ "${#nn_pids[@]}" -eq 1 ]] || { echo 'A unique NameNode listener was not found; refusing to write.' >&2; exit 4; }
nn_pid=${nn_pids[0]}
[[ -r "/proc/$nn_pid/cmdline" && -r "/proc/$nn_pid/environ" ]] || {
  echo 'NameNode process identity is unreadable; refusing to write.' >&2; exit 4;
}
tr '\0' ' ' <"/proc/$nn_pid/cmdline" | grep -Fq 'org.apache.hadoop.hdfs.server.namenode.NameNode' || {
  echo 'Listener is not the expected NameNode process; refusing to write.' >&2; exit 4;
}
nn_env=$(tr '\0' '\n' <"/proc/$nn_pid/environ")
JAVA_HOME=$(sed -n 's/^JAVA_HOME=//p' <<<"$nn_env" | head -n 1)
HADOOP_HOME=$(sed -n 's/^HADOOP_HOME=//p' <<<"$nn_env" | head -n 1)
HADOOP_CONF_DIR=$(sed -n 's/^HADOOP_CONF_DIR=//p' <<<"$nn_env" | head -n 1)
[[ -x "$JAVA_HOME/bin/java" && -x "$HADOOP_HOME/bin/hdfs" && \
   -r "$HADOOP_CONF_DIR/core-site.xml" && -r "$HADOOP_CONF_DIR/hdfs-site.xml" ]] || {
  echo 'Running NameNode toolchain/config is incomplete; refusing to write.' >&2; exit 4;
}
case "$(realpath -e "$HADOOP_CONF_DIR")" in
  "$TASK_ROOT"/cloud-e2e/*/platform-spark-conf) ;;
  *) echo 'NameNode config is outside the verified task HDFS config family.' >&2; exit 4 ;;
esac

export JAVA_HOME HADOOP_HOME HADOOP_CONF_DIR
readonly fs_default=$("$HADOOP_HOME/bin/hdfs" getconf -confKey fs.defaultFS)
[[ "$fs_default" == "hdfs://127.0.0.1:$nn_port" ]] || {
  echo 'NameNode RPC config does not match its listener; refusing to write.' >&2; exit 4;
}
report=$("$HADOOP_HOME/bin/hdfs" dfsadmin -report 2>/dev/null)
safemode=$("$HADOOP_HOME/bin/hdfs" dfsadmin -safemode get 2>/dev/null)
grep -q 'Live datanodes (1)' <<<"$report" && grep -q 'Safe mode is OFF' <<<"$safemode" || {
  echo 'HDFS is not a healthy one-DataNode namespace; refusing to write.' >&2; exit 4;
}

readonly hdfs_root="/landing/$run_id/raw"
"$HADOOP_HOME/bin/hdfs" dfs -test -e "$hdfs_root" || {
  echo 'Run-scoped HDFS Landing root is missing; refusing to create it.' >&2; exit 5;
}
count_line=$("$HADOOP_HOME/bin/hdfs" dfs -count "$hdfs_root" | tail -n 1)
read -r _dir_count file_count _byte_count _path <<<"$count_line"
[[ "${file_count:-invalid}" == 0 ]] || {
  echo 'Run-scoped raw Landing already contains files; refusing this first-file helper.' >&2; exit 5;
}

size_before=$(stat -c %s -- "$input_real")
sleep 1
size_after=$(stat -c %s -- "$input_real")
[[ "$size_before" -gt 0 && "$size_before" == "$size_after" ]] || {
  echo 'Mall JSONL is empty or still changing; refusing to spool it.' >&2; exit 6;
}
input_lines=$(wc -l <"$input_real" | tr -d '[:space:]')
[[ "$input_lines" -gt 0 ]] || { echo 'Mall JSONL has no complete records.' >&2; exit 6; }
python3 - "$input_real" <<'PY'
import json
import pathlib
import sys

for number, line in enumerate(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines(), 1):
    if not line.strip():
        raise SystemExit(f"Input line {number} is empty.")
    try:
        value = json.loads(line)
    except Exception:
        raise SystemExit(f"Input line {number} is not valid JSON.") from None
    if not isinstance(value, dict):
        raise SystemExit(f"Input line {number} is not a JSON object.")
PY
input_sha=$(sha256sum "$input_real" | awk '{print $1}')

readonly agent_root=$(mktemp -d "$run_dir/flume-out/landing-submit.XXXXXX")
readonly spool_dir="$agent_root/spool"
mkdir -p "$spool_dir" "$agent_root/channel/checkpoint" "$agent_root/channel/data" "$agent_root/logs"
readonly spool_file="$spool_dir/events.jsonl"
cp -- "$input_real" "$spool_file"
[[ "$(sha256sum "$spool_file" | awk '{print $1}')" == "$input_sha" ]] || {
  echo 'Spool copy checksum mismatch; Flume was not started.' >&2; exit 7;
}

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
a1.sinks.k.hdfs.path = $fs_default/landing/$run_id/raw/dt=%Y%m%d/hour=%H
a1.sinks.k.hdfs.filePrefix = events-pagination-synthetic
a1.sinks.k.hdfs.fileSuffix = .jsonl
a1.sinks.k.hdfs.fileType = DataStream
a1.sinks.k.hdfs.writeFormat = Text
a1.sinks.k.hdfs.rollInterval = 2
a1.sinks.k.hdfs.rollSize = 0
a1.sinks.k.hdfs.rollCount = 0
a1.sinks.k.hdfs.batchSize = 100
a1.sinks.k.hdfs.useLocalTimeStamp = true
EOF

export FLUME_JAVA_OPTS=${FLUME_JAVA_OPTS:--Xms64m -Xmx256m}
setsid "$FLUME_HOME/bin/flume-ng" agent --conf "$FLUME_HOME/conf" \
  --conf-file "$agent_root/flume.conf" --name a1 \
  -Dflume.root.logger=INFO,console >"$agent_root/logs/flume.log" 2>&1 </dev/null &
flume_pid=$!
printf '%s\n' "$flume_pid" >"$agent_root/flume.pid"
pgid=$(ps -o pgid= -p "$flume_pid" | tr -d '[:space:]')
[[ "$pgid" == "$flume_pid" ]] || { echo 'Flume did not start in its own process group.' >&2; exit 8; }

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
[[ "$completed" -eq 1 ]] || { echo 'Flume did not complete the spool file.' >&2; exit 9; }
sleep 4
stop_flume
trap - EXIT

mapfile -t hdfs_files < <("$HADOOP_HOME/bin/hdfs" dfs -ls -R "$hdfs_root" 2>/dev/null | \
  awk '$1 ~ /^-/ && $8 ~ /\/events-pagination-synthetic/ {print $8}')
[[ "${#hdfs_files[@]}" -eq 1 ]] || { echo 'Expected exactly one Flume HDFS output file.' >&2; exit 10; }
hdfs_lines=$("$HADOOP_HOME/bin/hdfs" dfs -cat "${hdfs_files[0]}" | wc -l | tr -d '[:space:]')
hdfs_sha=$("$HADOOP_HOME/bin/hdfs" dfs -cat "${hdfs_files[0]}" | sha256sum | awk '{print $1}')
[[ "$hdfs_lines" == "$input_lines" && "$hdfs_sha" == "$input_sha" ]] || {
  echo 'Flume HDFS content differs from the Mall JSONL input.' >&2; exit 11;
}

printf 'result=PASS\nrunId=%s\ninputLines=%s\ninputSha256=%s\nhdfsUri=%s\nhdfsLines=%s\nhdfsSha256=%s\nagentEvidence=%s\n' \
  "$run_id" "$input_lines" "$input_sha" "$fs_default${hdfs_files[0]}" \
  "$hdfs_lines" "$hdfs_sha" "$agent_root"
