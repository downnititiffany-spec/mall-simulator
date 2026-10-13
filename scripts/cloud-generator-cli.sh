#!/usr/bin/env bash
# Run the generator's supported CLI against the active isolated task schema.
# The DB credentials are inherited from the generator process in memory and
# redacted from the task-local CLI log; this script never snapshots them.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_ROOT=${CLOUD_SINGLE_NODE_ROOT:-}
if [[ -z "$TASK_ROOT" ]]; then
  TASK_ROOT=$(python3 - <<'PY'
import glob
import os
import sys

matches = set()
for path in glob.glob("/proc/[0-9]*/environ"):
    pid = path.split("/")[2]
    try:
        with open(path, "rb") as stream:
            env = dict(item.split(b"=", 1) for item in stream.read().split(b"\0") if b"=" in item)
        root = env.get(b"CLOUD_SINGLE_NODE_ROOT", b"").decode()
        if not root or not all(env.get(key) for key in
                               (b"GENERATOR_LOG_FILE", b"GENERATOR_OUTPUT_ROOT",
                                b"SPRING_DATASOURCE_URL", b"SPRING_DATASOURCE_PASSWORD")):
            continue
        run_dir = os.path.realpath(os.path.join(root, "cloud-e2e", "current"))
        with open(os.path.join(run_dir, "pids", "generator.pid"), encoding="ascii") as pid_file:
            if pid_file.read().strip() == pid:
                matches.add(os.path.realpath(root))
    except (OSError, UnicodeError, ValueError):
        continue

if len(matches) != 1:
    sys.exit(2)
print(matches.pop())
PY
) || { echo 'Could not identify one active task generator; set CLOUD_SINGLE_NODE_ROOT explicitly.' >&2; exit 2; }
fi
TASK_ROOT=$(realpath -e -- "$TASK_ROOT") || { echo 'Task root is unavailable.' >&2; exit 2; }
RUN_DIR=$(readlink -f "$TASK_ROOT/cloud-e2e/current")
[[ -r "$RUN_DIR/run-id" && -r "$RUN_DIR/pids/generator.pid" ]] || { echo 'No active task generator.' >&2; exit 2; }
PID=$(<"$RUN_DIR/pids/generator.pid")
[[ "$PID" =~ ^[0-9]+$ && -r "/proc/$PID/environ" ]] || { echo 'Task generator process is unavailable.' >&2; exit 2; }
exec python3 - "$PID" "$TASK_ROOT" "$ROOT/synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar" "$RUN_DIR/logs/generator-cli.log" "$@" <<'PY'
import os, subprocess, sys
pid, expected_root, jar, logfile, *args = sys.argv[1:]
use_mall_token = "--with-mall-token" in args
args = [arg for arg in args if arg != "--with-mall-token"]
with open(f"/proc/{pid}/environ", "rb") as f:
    pairs = [p.split(b"=", 1) for p in f.read().split(b"\0") if b"=" in p]
source = {k.decode(errors="replace"): v.decode(errors="replace") for k, v in pairs}
if os.path.realpath(source.get("CLOUD_SINGLE_NODE_ROOT", "")) != os.path.realpath(expected_root):
    print("Task generator identity does not match the selected task root.", file=sys.stderr)
    sys.exit(3)
keys = ["JAVA_HOME", "PATH", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
        "SPRING_DATASOURCE_PASSWORD", "GENERATOR_OUTPUT_ROOT", "GENERATOR_LOG_FILE"]
missing = [k for k in keys if not source.get(k)]
if missing:
    print("Generator runtime environment is incomplete.", file=sys.stderr)
    sys.exit(3)
env = {k: source[k] for k in keys}
if use_mall_token:
    token = os.environ.get("CLOUD_MALL_TOKEN")
    if not token:
        print("Mall API auth token is not available in the current process environment.", file=sys.stderr)
        sys.exit(3)
    env["CLOUD_MALL_TOKEN"] = token
env["SPRING_MAIN_WEB_APPLICATION_TYPE"] = "none"
cmd = [os.path.join(env["JAVA_HOME"], "bin", "java"), "-Xms64m", "-Xmx384m", "-Dfile.encoding=UTF-8",
       "-jar", jar, *args]
result = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
safe = result.stdout
for secret in (env.get("SPRING_DATASOURCE_USERNAME"), env.get("SPRING_DATASOURCE_PASSWORD"),
               env.get("CLOUD_MALL_TOKEN")):
    if secret:
        safe = safe.replace(secret, "[REDACTED]")
os.makedirs(os.path.dirname(logfile), exist_ok=True)
with open(logfile, "a", encoding="utf-8") as f:
    f.write(safe)
os.chmod(logfile, 0o600)
for line in safe.splitlines():
    if any(mark in line for mark in ("plan_id=", "run_id=", '"status"', '"uri"', '"sha256"', "ERROR", "BUILD")):
        print(line[:1000])
sys.exit(result.returncode)
PY
