#!/usr/bin/env bash
# Run the generator's supported CLI against the active isolated task schema.
# The DB credentials are inherited from the generator process in memory and
# redacted from the task-local CLI log; this script never snapshots them.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR=$(readlink -f /workspace/single-node/cloud-e2e/current)
[[ -r "$RUN_DIR/run-id" && -r "$RUN_DIR/pids/generator.pid" ]] || { echo 'No active task generator.' >&2; exit 2; }
PID=$(<"$RUN_DIR/pids/generator.pid")
[[ "$PID" =~ ^[0-9]+$ && -r "/proc/$PID/environ" ]] || { echo 'Task generator process is unavailable.' >&2; exit 2; }
exec python3 - "$PID" "$ROOT/synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar" "$RUN_DIR/logs/generator-cli.log" "$@" <<'PY'
import os, subprocess, sys
pid, jar, logfile, *args = sys.argv[1:]
with open(f"/proc/{pid}/environ", "rb") as f:
    pairs = [p.split(b"=", 1) for p in f.read().split(b"\0") if b"=" in p]
source = {k.decode(errors="replace"): v.decode(errors="replace") for k, v in pairs}
keys = ["JAVA_HOME", "PATH", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
        "SPRING_DATASOURCE_PASSWORD", "GENERATOR_OUTPUT_ROOT", "GENERATOR_LOG_FILE"]
missing = [k for k in keys if not source.get(k)]
if missing:
    print("Generator runtime environment is incomplete.", file=sys.stderr)
    sys.exit(3)
env = {k: source[k] for k in keys}
env["SPRING_MAIN_WEB_APPLICATION_TYPE"] = "none"
cmd = [os.path.join(env["JAVA_HOME"], "bin", "java"), "-Xms64m", "-Xmx384m", "-Dfile.encoding=UTF-8",
       "-jar", jar, *args]
result = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
safe = result.stdout
for k in ("SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"):
    safe = safe.replace(env[k], "[REDACTED]")
os.makedirs(os.path.dirname(logfile), exist_ok=True)
with open(logfile, "a", encoding="utf-8") as f:
    f.write(safe)
os.chmod(logfile, 0o600)
for line in safe.splitlines():
    if any(mark in line for mark in ("plan_id=", "run_id=", '"status"', '"uri"', '"sha256"', "ERROR", "BUILD")):
        print(line[:1000])
sys.exit(result.returncode)
PY
