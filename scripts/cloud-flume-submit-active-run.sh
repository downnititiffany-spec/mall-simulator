#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Submit one completed Mall JSONL through the active RunId's existing
# Spooling Directory -> File Channel -> HDFS pipeline. Never starts, stops,
# formats, or deletes a service or persistent data.
readonly TASK_ROOT=${CLOUD_SINGLE_NODE_ROOT:-/home/asus/graduationproject-runs}
readonly E2E_ROOT="$TASK_ROOT/cloud-e2e"

usage() {
  echo "Usage: $0 <current-run-id> <completed-mall-jsonl>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
readonly RUN_ID=$1
readonly INPUT=$2
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{5,40}$ ]] || usage
readonly RUN_DIR="$E2E_ROOT/$RUN_ID"
[[ -r "$RUN_DIR/run-id" && "$(<"$RUN_DIR/run-id")" == "$RUN_ID" ]] || {
  echo 'RunId marker is missing or mismatched.' >&2; exit 3;
}
[[ "$(readlink -f "$E2E_ROOT/current")" == "$(readlink -f "$RUN_DIR")" ]] || {
  echo 'Requested RunId is not the active task stack.' >&2; exit 3;
}

readonly INPUT_REAL=$(realpath -e -- "$INPUT") || { echo 'Mall JSONL file is missing.' >&2; exit 3; }
readonly EVENTS_REAL=$(realpath -e -- "$RUN_DIR/mall-landing/events") || {
  echo 'Run-scoped Mall events directory is missing.' >&2; exit 3;
}
case "$INPUT_REAL" in
  "$EVENTS_REAL"/*.jsonl) ;;
  *) echo 'Input must be a completed JSONL directly under this RunId events directory.' >&2; exit 3 ;;
esac
[[ -f "$INPUT_REAL" && -r "$INPUT_REAL" && ! -L "$INPUT" ]] || {
  echo 'Input must be a readable regular file, not a symlink.' >&2; exit 3;
}

readonly FLUME_PID=$(<"$RUN_DIR/pids/flume.pid")
[[ "$FLUME_PID" =~ ^[0-9]+$ && -r "/proc/$FLUME_PID/environ" && -r "/proc/$FLUME_PID/cmdline" ]] || {
  echo 'Active RunId Flume process is unavailable.' >&2; exit 4;
}
kill -0 "$FLUME_PID" 2>/dev/null || { echo 'Active RunId Flume process is not running.' >&2; exit 4; }

process_env_value() {
  tr '\0' '\n' <"/proc/$FLUME_PID/environ" | sed -n "s/^$1=//p" | head -n 1
}
readonly PROCESS_TASK_ROOT=$(process_env_value CLOUD_SINGLE_NODE_ROOT)
readonly HADOOP_HOME=$(process_env_value HADOOP_HOME)
readonly HADOOP_CONF_DIR=$(process_env_value HADOOP_CONF_DIR)
readonly FLUME_HOME=$(process_env_value FLUME_HOME)
[[ "$(realpath -e "$PROCESS_TASK_ROOT")" == "$(realpath -e "$TASK_ROOT")" ]] || {
  echo 'Flume process belongs to a different task root.' >&2; exit 4;
}
[[ "$(realpath -e "$HADOOP_CONF_DIR")" == "$(realpath -e "$RUN_DIR/platform-spark-conf")" ]] || {
  echo 'Flume is not using this RunId Hadoop configuration.' >&2; exit 4;
}
[[ -x "$HADOOP_HOME/bin/hdfs" && -x "$FLUME_HOME/bin/flume-ng" ]] || {
  echo 'Flume or Hadoop runtime from the active process is unavailable.' >&2; exit 4;
}
tr '\0' '\n' <"/proc/$FLUME_PID/cmdline" | grep -Fxq "$RUN_DIR/flume.conf" || {
  echo 'Flume command line does not reference this RunId configuration.' >&2; exit 4;
}

readonly FLUME_CONF="$RUN_DIR/flume.conf"
grep -Fqx "a1.sources.r.spoolDir = $RUN_DIR/flume-input" "$FLUME_CONF" || {
  echo 'Flume spool directory does not match this RunId.' >&2; exit 5;
}
grep -Fqx 'a1.channels.c.type = file' "$FLUME_CONF" || {
  echo 'Active Flume channel is not File Channel.' >&2; exit 5;
}
grep -Fqx "a1.channels.c.checkpointDir = $RUN_DIR/flume-channel/checkpoint" "$FLUME_CONF" || {
  echo 'Flume checkpoint directory is outside this RunId.' >&2; exit 5;
}
grep -Fqx "a1.channels.c.dataDirs = $RUN_DIR/flume-channel/data" "$FLUME_CONF" || {
  echo 'Flume data directory is outside this RunId.' >&2; exit 5;
}
grep -Fq "/landing/$RUN_ID/raw/" "$FLUME_CONF" || {
  echo 'Flume sink does not target this RunId Landing path.' >&2; exit 5;
}

export HADOOP_HOME HADOOP_CONF_DIR
readonly HDFS="$HADOOP_HOME/bin/hdfs"
readonly FS_DEFAULT=$("$HDFS" getconf -confKey fs.defaultFS)
[[ "$FS_DEFAULT" =~ ^hdfs://127\.0\.0\.1:[0-9]+$ ]] || {
  echo 'Active Flume HDFS authority is not the verified loopback namespace.' >&2; exit 6;
}
report=$("$HDFS" dfsadmin -report 2>/dev/null)
safemode=$("$HDFS" dfsadmin -safemode get 2>/dev/null)
grep -q 'Live datanodes (1)' <<<"$report" && grep -q 'Safe mode is OFF' <<<"$safemode" || {
  echo 'HDFS is not a healthy one-DataNode namespace.' >&2; exit 6;
}
readonly LANDING="/landing/$RUN_ID/raw"
"$HDFS" dfs -test -e "$LANDING" || { echo 'RunId Landing directory is missing.' >&2; exit 6; }
count_line=$("$HDFS" dfs -count "$LANDING" | tail -n 1)
read -r _dir_count file_count _byte_count _path <<<"$count_line"
[[ "${file_count:-invalid}" == 0 ]] || {
  echo 'RunId raw Landing is not empty; refusing first-file delivery.' >&2; exit 6;
}

readonly SPOOL_DIR="$RUN_DIR/flume-input"
find "$SPOOL_DIR" -maxdepth 1 -type f ! -name '*.COMPLETED' ! -name '*.tmp' ! -name 'manifest.json' -print -quit | grep -q . && {
  echo 'Flume spool already contains an unfinished file.' >&2; exit 7;
}
readonly SIZE_BEFORE=$(stat -c %s -- "$INPUT_REAL")
sleep 1
readonly SIZE_AFTER=$(stat -c %s -- "$INPUT_REAL")
[[ "$SIZE_BEFORE" -gt 0 && "$SIZE_BEFORE" == "$SIZE_AFTER" ]] || {
  echo 'Mall JSONL is empty or still changing.' >&2; exit 7;
}
readonly INPUT_LINES=$(wc -l <"$INPUT_REAL" | tr -d '[:space:]')
[[ "$INPUT_LINES" =~ ^[1-9][0-9]*$ ]] || { echo 'Mall JSONL has no complete lines.' >&2; exit 7; }
python3 - "$INPUT_REAL" <<'PY'
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

readonly INPUT_SHA=$(sha256sum "$INPUT_REAL" | awk '{print $1}')
readonly SPOOL_FILE="$SPOOL_DIR/landing-$RUN_ID-$(basename "$INPUT_REAL")"
[[ ! -e "$SPOOL_FILE" && ! -e "$SPOOL_FILE.COMPLETED" ]] || {
  echo 'This source file was already submitted or is being processed; refusing replay.' >&2; exit 8;
}
cp -- "$INPUT_REAL" "$SPOOL_FILE"
[[ "$(sha256sum "$SPOOL_FILE" | awk '{print $1}')" == "$INPUT_SHA" ]] || {
  echo 'Spool copy checksum mismatch; do not replay.' >&2; exit 8;
}

completed=0
content_matches=0
hdfs_file_count=0
for _ in $(seq 1 90); do
  [[ -f "$SPOOL_FILE.COMPLETED" ]] && completed=1
  mapfile -t hdfs_files < <("$HDFS" dfs -ls -R "$LANDING" 2>/dev/null | awk '$1 ~ /^-/ {print $8}')
  hdfs_file_count=${#hdfs_files[@]}
  if [[ "$completed" -eq 1 && "$hdfs_file_count" -gt 0 ]]; then
    actual_lines=$("$HDFS" dfs -cat "${hdfs_files[@]}" 2>/dev/null | wc -l | tr -d '[:space:]')
    if [[ "$actual_lines" == "$INPUT_LINES" ]]; then
      sleep 3
      mapfile -t hdfs_files < <("$HDFS" dfs -ls -R "$LANDING" 2>/dev/null | awk '$1 ~ /^-/ {print $8}')
      hdfs_file_count=${#hdfs_files[@]}
      verification=$(python3 - "$INPUT_REAL" "$HDFS" "${hdfs_files[@]}" <<'PY'
import collections
import pathlib
import subprocess
import sys

source = collections.Counter(pathlib.Path(sys.argv[1]).read_bytes().splitlines())
result = subprocess.run([sys.argv[2], "dfs", "-cat", *sys.argv[3:]],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
if result.returncode:
    raise SystemExit(2)
landing = collections.Counter(result.stdout.splitlines())
if source != landing:
    raise SystemExit(1)
print("match")
PY
) && content_matches=1
      [[ "$content_matches" -eq 1 ]] && break
    fi
  fi
  kill -0 "$FLUME_PID" 2>/dev/null || { echo 'Active Flume stopped during delivery; source/spool preserved, no replay.' >&2; exit 9; }
  sleep 1
done
[[ "$completed" -eq 1 && "$content_matches" -eq 1 ]] || {
  echo 'Flume delivery could not be reconciled; source/spool preserved, no replay.' >&2; exit 9;
}

printf 'result=PASS\nrunId=%s\ninputLines=%s\ninputSha256=%s\nhdfsFileCount=%s\ncontentMatches=true\nlanding=%s\nactiveFlumePid=%s\n' \
  "$RUN_ID" "$INPUT_LINES" "$INPUT_SHA" "$hdfs_file_count" "$FS_DEFAULT$LANDING" "$FLUME_PID"
