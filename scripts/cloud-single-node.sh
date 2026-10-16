#!/usr/bin/env bash
# Reuse verified binaries and the initialized task HDFS namespace. Secrets exist
# only in this shell and child process environments; no environment snapshot is saved.
set -Eeuo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS=${CLOUD_TOOLCHAIN_ROOT:-/workspace/toolchains}
JAVA_HOME=${CLOUD_JAVA_HOME:-$TOOLS/jdk17}
MYSQL_BASE=${CLOUD_MYSQL_BASE:-$TOOLS/mysql-8.0.41-linux-glibc2.28-x86_64}
MYSQL_RUNTIME_LIBS=${CLOUD_MYSQL_RUNTIME_LIBS:-$TOOLS/mysql-runtime-libs/usr/lib/x86_64-linux-gnu}
HADOOP_HOME=${CLOUD_HADOOP_HOME:-$TOOLS/hadoop-3.3.4}
HIVE_HOME=${CLOUD_HIVE_HOME:-$TOOLS/hive-3.1.3}
SPARK_HOME=${CLOUD_SPARK_HOME:-$TOOLS/spark-3.5.1}
FLUME_HOME=${CLOUD_FLUME_HOME:-$TOOLS/flume-1.11.0}
MAVEN_BIN=${CLOUD_MAVEN_BIN:-$TOOLS/maven/mvn/bin/mvn}
NODE_BIN=${CLOUD_NODE_BIN:-}
HDFS_RUN=${CLOUD_HDFS_RUN:-/workspace/single-node/runs/cloud_20261001_hdfs01}
HADOOP_CONF_DIR=${CLOUD_HADOOP_CONF_DIR:-$HDFS_RUN/conf}
TASK_ROOT=${CLOUD_SINGLE_NODE_ROOT:-/workspace/single-node}
E2E_ROOT="$TASK_ROOT/cloud-e2e"
HDFS_RPC_PORT=${CLOUD_HDFS_RPC_PORT:-19000}
HDFS_DATA_PORT=${CLOUD_HDFS_DATA_PORT:-19010}
HDFS_NN_HTTP_PORT=${CLOUD_HDFS_NN_HTTP_PORT:-19870}
HDFS_DN_HTTP_PORT=${CLOUD_HDFS_DN_HTTP_PORT:-19864}
HDFS_DN_IPC_PORT=${CLOUD_HDFS_DN_IPC_PORT:-19866}
HMS_PORT=${CLOUD_HIVE_METASTORE_PORT:-19083}
ACTION=${1:-start}
LATEST="$E2E_ROOT/current"

command -v timeout >/dev/null 2>&1 || { echo 'The timeout utility is required for safe frontend port probes.' >&2; exit 3; }

cleanup_failed_start() {
  local status=$?
  if [[ "$ACTION" == start && "$status" -ne 0 && -n "${RUN_DIR:-}" && -n "${RUN_ID:-}" \
        && -r "$RUN_DIR/run-id" && "$(<"$RUN_DIR/run-id")" == "$RUN_ID" \
        && -r "$LATEST/run-id" && "$(<"$LATEST/run-id")" == "$RUN_ID" ]]; then
    printf 'Start failed for task RunId %s; stopping only its registered processes and preserving data.\n' "$RUN_ID" >&2
    "$ROOT/scripts/cloud-single-node.sh" stop || printf 'Task cleanup incomplete; inspect only PID files under %s.\n' "$RUN_DIR/pids" >&2
  fi
  return "$status"
}
trap cleanup_failed_start EXIT

port_is_listening() {
  local port=$1
  if ss -ltnH "sport = :$port" | grep -q .; then
    return 0
  fi
  # WSL localhost forwarding exposes Windows Node listeners even when
  # they do not appear in the Linux ss socket table. A TCP connect also catches
  # listeners that are not HTTP servers or do not send an HTTP response.
  if [[ "$port" == 5173 || "$port" == 5174 ]]; then
    timeout 2 bash -c ':</dev/tcp/127.0.0.1/$1' _ "$port" >/dev/null 2>&1
    return $?
  fi
  return 1
}

if [[ "$ACTION" == status ]]; then
  [[ -r "$LATEST/run-id" ]] || {
    printf 'No task run registered under configured root: %s\nSet CLOUD_SINGLE_NODE_ROOT to the task root before status or stop.\n' "$TASK_ROOT" >&2
    exit 1
  }
  printf 'run_id=%s\n' "$(<"$LATEST/run-id")"
  for port in 3307 "$HDFS_RPC_PORT" "$HDFS_DATA_PORT" "$HDFS_NN_HTTP_PORT" "$HDFS_DN_HTTP_PORT" "$HDFS_DN_IPC_PORT" "$HMS_PORT" 8091 8090 8092 5173 5174; do
    if port_is_listening "$port"; then echo "$port LISTEN"; else echo "$port closed"; fi
  done
  exit 0
fi

if [[ "$ACTION" == stop ]]; then
  [[ -r "$LATEST/run-id" ]] || {
    printf 'No task run registered under configured root: %s\nSet CLOUD_SINGLE_NODE_ROOT to the task root before status or stop.\n' "$TASK_ROOT" >&2
    exit 1
  }
  run_id=$(<"$LATEST/run-id"); run_dir="$E2E_ROOT/$run_id"
  [[ -r "$run_dir/run-id" && "$(<"$run_dir/run-id")" == "$run_id" ]] || { echo 'Run identity check failed.' >&2; exit 1; }
  stop_failed=0
  for name in vite-mall vite-web generator mall platform flume metastore mysql; do
    f="$run_dir/pids/$name.pid"; [[ -r "$f" ]] || continue
    if [[ "$name" == vite-web || "$name" == vite-mall ]]; then
      windows_pid_file="$run_dir/pids/$name.windows.pid"
      if [[ -r "$windows_pid_file" ]]; then
        port=5173; [[ "$name" == vite-mall ]] && port=5174
        command -v wslpath >/dev/null && command -v powershell.exe >/dev/null || {
          printf 'Cannot verify Windows Vite PID for %s; keep it running and inspect %s.\n' "$name" "$windows_pid_file" >&2
          continue
        }
        vite_helper_path=$(wslpath -w "$ROOT/scripts/stop-task-vite-process.ps1")
        [[ -r "$ROOT/scripts/stop-task-vite-process.ps1" ]] || {
          printf 'Vite process identity helper is missing; refusing to stop %s.\n' "$name" >&2
          stop_failed=1
          continue
        }
        node_path_file="$run_dir/pids/node-bin.windows-path"
        [[ -r "$node_path_file" ]] || {
          printf 'Windows Node executable identity is missing for %s; refusing to stop it.\n' "$name" >&2
          continue
        }
        expected_node_path=$(<"$node_path_file")
        [[ "$expected_node_path" == *.exe ]] || {
          printf 'Windows Node executable identity is invalid for %s; refusing to stop it.\n' "$name" >&2
          continue
        }
        powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass \
          -File "$vite_helper_path" \
          -PidFile "$(wslpath -w "$windows_pid_file")" \
          -ExpectedNodePath "$expected_node_path" \
          -RunId "$run_id" -Port "$port" || {
            printf 'Refused or failed to stop Windows Vite PID for %s; inspect task-owned PID file.\n' "$name" >&2
            stop_failed=1
          }
        continue
      fi
    fi
    pid=$(<"$f"); [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || continue
    cmd=$(tr '\0' ' ' <"/proc/$pid/cmdline")
    cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null || true)
    case "$name:$cmd" in
      vite-mall:*mall-frontend*|vite-web:*web/node_modules/.bin/vite*|generator:*synthetic-data-generator*|mall:*mall-simulator*|platform:*platform-app*|metastore:*metastore*|metastore:*HiveMetaStore*|mysql:*mysqld*) kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true ;;
      flume:*) ;;
      *) printf 'Refused mismatched PID %s for %s\n' "$pid" "$name" >&2 ;;
    esac
    if [[ "$name" == vite-web && "$cwd" == "$ROOT/web" && "$cmd" == *vite* ]] || [[ "$name" == vite-mall && "$cwd" == "$ROOT/mall-frontend" && "$cmd" == *vite* ]]; then
      kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    fi
    if [[ "$name" == flume && "$cmd" == *org.apache.flume.node.Application* \
          && "$cmd" == *"--conf-file $run_dir/flume.conf"* ]]; then
      pgid=$(ps -o pgid= -p "$pid" | tr -d ' ')
      if [[ "$pgid" == "$pid" ]]; then
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
      else
        printf 'Refused Flume PID %s: it is not the task process-group leader.\n' "$pid" >&2
        stop_failed=1
      fi
    elif [[ "$name" == flume ]]; then
      printf 'Refused mismatched PID %s for flume; expected this RunId config path.\n' "$pid" >&2
      stop_failed=1
    fi
  done
  export JAVA_HOME HADOOP_HOME HADOOP_CONF_DIR HADOOP_PID_DIR="$run_dir/pids/hadoop"
  "$HADOOP_HOME/bin/hdfs" --daemon stop datanode >/dev/null 2>&1 || true
  "$HADOOP_HOME/bin/hdfs" --daemon stop namenode >/dev/null 2>&1 || true
  remaining_ports=''
  for _ in $(seq 1 60); do
    remaining_ports=''
    for port in 3307 "$HDFS_RPC_PORT" "$HDFS_DATA_PORT" "$HDFS_NN_HTTP_PORT" "$HDFS_DN_HTTP_PORT" "$HDFS_DN_IPC_PORT" "$HMS_PORT" 8091 8090 8092 5173 5174; do
      if port_is_listening "$port"; then
        remaining_ports+=" $port"
      fi
    done
    [[ -n "$remaining_ports" ]] || break
    sleep 1
  done
  if [[ -n "$remaining_ports" ]]; then
    printf 'Task ports remain occupied after stop request:%s. No additional processes were terminated.\n' "$remaining_ports" >&2
    stop_failed=1
  fi
  if [[ "$stop_failed" -ne 0 ]]; then
    printf 'Stop incomplete for %s; inspect only the named task PID files and process identities. Data is preserved.\n' "$run_id" >&2
    exit 1
  fi
  echo "Stopped task-owned processes for $run_id; database and HDFS files are preserved."
  exit 0
fi
if [[ "$ACTION" == recover-web ]]; then
  [[ -r "$LATEST/run-id" ]] || {
    printf 'No task run registered under configured root: %s\n' "$TASK_ROOT" >&2
    exit 1
  }
  run_id=$(<"$LATEST/run-id")
  run_dir="$E2E_ROOT/$run_id"
  [[ "$run_id" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{5,40}$ && -r "$run_dir/run-id" \
      && "$(<"$run_dir/run-id")" == "$run_id" ]] || { echo 'Run identity check failed.' >&2; exit 1; }
  [[ -d "$ROOT/web" && -r "$ROOT/web/node_modules/vite/bin/vite.js" \
      && -d "$run_dir/pids" && -d "$run_dir/logs" ]] || {
    echo 'Task-owned Web frontend or RunId directories are missing; refusing recovery.' >&2
    exit 3
  }
  [[ -n "$NODE_BIN" && "$NODE_BIN" == *.exe && -x "$NODE_BIN" ]] || {
    echo 'Set CLOUD_NODE_BIN to the Windows Node executable used by this task.' >&2
    exit 3
  }
  command -v wslpath >/dev/null && command -v powershell.exe >/dev/null || {
    echo 'Windows path/process identity tools are unavailable; refusing recovery.' >&2
    exit 3
  }
  vite_helper_path=$(wslpath -w "$ROOT/scripts/stop-task-vite-process.ps1")
  [[ -r "$ROOT/scripts/stop-task-vite-process.ps1" ]] || {
    echo 'Vite process identity helper is missing; refusing recovery.' >&2
    exit 3
  }
  "$NODE_BIN" --version >/dev/null 2>&1 || { echo 'Configured Windows Node executable cannot run.' >&2; exit 3; }

  win_pid_file="$run_dir/pids/vite-web.windows.pid"
  win_pid_path=$(wslpath -w "$win_pid_file")
  node_path_file="$run_dir/pids/node-bin.windows-path"
  node_windows_path=$(wslpath -w "$NODE_BIN")
  if [[ -r "$node_path_file" ]]; then
    registered_node_path=$(tr -d '\r' <"$node_path_file")
    [[ "${registered_node_path,,}" == "${node_windows_path,,}" ]] || {
      echo 'Registered Windows Node executable differs from CLOUD_NODE_BIN; refusing recovery.' >&2
      exit 3
    }
  else
    printf '%s\n' "$node_windows_path" >"$node_path_file"
    chmod 600 "$node_path_file"
  fi

  verify_windows_vite() {
    local result
    result=$(powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass \
      -File "$vite_helper_path" \
      -PidFile "$win_pid_path" -ExpectedNodePath "$node_windows_path" \
      -RunId "$run_id" -Port 5173 -Action verify) || return 1
    [[ "$result" == *'Verified task-owned Vite PID'* ]]
  }
  if [[ -e "$win_pid_file" ]]; then
    if verify_windows_vite; then
      if port_is_listening 5173; then
        echo "Task Web frontend is already running for $run_id."
        exit 0
      fi
      echo 'The verified task Web process exists but port 5173 is not accepting connections; refusing a duplicate.' >&2
      exit 5
    fi
    if ! powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass \
        -File "$vite_helper_path" \
        -PidFile "$win_pid_path" -ExpectedNodePath "$node_windows_path" \
        -RunId "$run_id" -Port 5173 -Action stop; then
      echo 'Windows Vite PID file is stale or mismatched; guarded cleanup refused, so recovery stopped.' >&2
      exit 5
    fi
  fi

  linux_pid_file="$run_dir/pids/vite-web.pid"
  if [[ -r "$linux_pid_file" ]]; then
    old_pid=$(<"$linux_pid_file")
    if [[ "$old_pid" =~ ^[0-9]+$ && -r "/proc/$old_pid/cmdline" ]]; then
      old_cmd=$(tr '\0' ' ' <"/proc/$old_pid/cmdline")
      if [[ "$old_cmd" == *'node.exe'* && "$old_cmd" == *'vite.js'* && "$old_cmd" == *'--port 5173'* ]]; then
        echo "Task Web frontend proxy PID $old_pid still exists; refusing duplicate recovery." >&2
        exit 5
      else
        # The Windows Vite helper above has already verified/stopped the task-owned
        # Windows process. A live Linux interop wrapper can remain after that process
        # exits; never signal a PID whose current identity is not Vite. Forget only
        # this RunId's stale registration and let the port guard below decide.
        echo "Recorded Web PID $old_pid is no longer a verified Vite process; clearing only the task PID registration."
        rm -f -- "$linux_pid_file"
      fi
    else
      rm -f -- "$linux_pid_file"
    fi
  fi
  port_is_listening 5173 && { echo 'Port 5173 is occupied; refusing recovery.' >&2; exit 5; }

  relative_vite='./node_modules/vite/bin/vite.js'
  run_marker="cloud-single-node-run-id:$run_id"
  node_bootstrap="const fs=require('node:fs');const path=require('node:path');const {pathToFileURL}=require('node:url');const [pidFile,marker,vite,port]=process.argv.slice(1);fs.writeFileSync(pidFile,String(process.pid),{mode:0o600});process.argv=[process.execPath,vite,'--host','127.0.0.1','--port',port];import(pathToFileURL(path.resolve(vite)).href);"
  (cd "$ROOT/web" && exec setsid "$NODE_BIN" -e "$node_bootstrap" "$win_pid_path" "$run_marker" "$relative_vite" 5173) \
    </dev/null >"$run_dir/logs/vite-web.log" 2>&1 &
  echo $! >"$linux_pid_file"
  chmod 600 "$linux_pid_file"
  ready=0
  for _ in $(seq 1 30); do
    if port_is_listening 5173 && [[ -r "$win_pid_file" ]] && verify_windows_vite; then
      ready=1
      break
    fi
    sleep 1
  done
  [[ "$ready" -eq 1 ]] || {
    powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass \
      -File "$vite_helper_path" \
      -PidFile "$win_pid_path" -ExpectedNodePath "$node_windows_path" \
      -RunId "$run_id" -Port 5173 -Action stop || \
      echo 'Guarded cleanup refused; keep the process and inspect its task PID identity.' >&2
    echo "Web frontend did not become ready with verified RunId identity; inspect $run_dir/logs/vite-web.log and PID files." >&2
    exit 8
  }
  echo "Recovered Web frontend for $run_id on 5173; database/HDFS services were not changed."
  exit 0
fi
[[ "$ACTION" == start || "$ACTION" == preflight ]] || { echo 'usage: cloud-single-node.sh [preflight|start|status|stop|recover-web]' >&2; exit 2; }

for required in "$JAVA_HOME/bin/java" "$MYSQL_BASE/bin/mysqld" "$HADOOP_HOME/bin/hdfs" "$HIVE_HOME/bin/hive" "$HIVE_HOME/bin/schematool" "$SPARK_HOME/bin/spark-submit" "$FLUME_HOME/bin/flume-ng" "$MAVEN_BIN" "$ROOT/analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar" "$ROOT/mall-simulator/target/mall-simulator-0.1.0-SNAPSHOT.jar" "$ROOT/synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar" "$ROOT/web/node_modules/.bin/vite" "$ROOT/mall-frontend/node_modules/.bin/vite"; do
  [[ -e "$required" ]] || { printf 'Verified component/artifact missing: %s\n' "$required" >&2; exit 3; }
done
if [[ -n "$NODE_BIN" ]]; then
  [[ -x "$NODE_BIN" ]] || { printf 'Node executable is not executable: %s\n' "$NODE_BIN" >&2; exit 3; }
  NODE_VERSION=$("$NODE_BIN" --version 2>/dev/null) || { echo 'Configured Node executable cannot run in this environment.' >&2; exit 3; }
  export PATH="$(dirname "$NODE_BIN"):$PATH"
else
  NODE_BIN=$(command -v node || true)
  [[ -n "$NODE_BIN" ]] || { echo 'Node.js is required to run the Vite frontends; set CLOUD_NODE_BIN to a Node executable runnable from this environment.' >&2; exit 3; }
  NODE_VERSION=$(node --version 2>/dev/null) || { echo 'Node.js is not runnable in this environment.' >&2; exit 3; }
fi
printf 'Frontend runtime: Node %s\n' "$NODE_VERSION"
[[ -r "$HDFS_RUN/name/current/VERSION" && -r "$HDFS_RUN/data/current/VERSION" ]] || { echo 'Initialized HDFS namespace missing; refusing format or recreation.' >&2; exit 4; }
[[ -r "$HADOOP_CONF_DIR/core-site.xml" && -r "$HADOOP_CONF_DIR/hdfs-site.xml" ]] || { echo 'Hadoop config missing; refusing to guess or format HDFS.' >&2; exit 4; }
nn_cluster=$(sed -n 's/^clusterID=//p' "$HDFS_RUN/name/current/VERSION" | head -n 1)
dn_cluster=$(sed -n 's/^clusterID=//p' "$HDFS_RUN/data/current/VERSION" | head -n 1)
[[ -n "$nn_cluster" && "$nn_cluster" == "$dn_cluster" ]] || { echo 'HDFS NameNode/DataNode identity mismatch; refusing startup.' >&2; exit 4; }
grep -Fq "hdfs://127.0.0.1:$HDFS_RPC_PORT" "$HADOOP_CONF_DIR/core-site.xml" || { echo 'HDFS RPC address does not match the configured port.' >&2; exit 4; }
grep -Fq "$HDFS_RUN/name" "$HADOOP_CONF_DIR/hdfs-site.xml" && grep -Fq "$HDFS_RUN/data" "$HADOOP_CONF_DIR/hdfs-site.xml" || { echo 'HDFS config does not point to the initialized namespace directories.' >&2; exit 4; }
for port in 3307 "$HDFS_RPC_PORT" "$HDFS_DATA_PORT" "$HDFS_NN_HTTP_PORT" "$HDFS_DN_HTTP_PORT" "$HDFS_DN_IPC_PORT" "$HMS_PORT" 8091 8090 8092 5173 5174; do
  if port_is_listening "$port"; then printf 'Port %s occupied; refusing takeover.\n' "$port" >&2; exit 5; fi
done
if [[ "$ACTION" == preflight ]]; then
  echo 'Preflight passed: dependencies, task paths, HDFS identity/config and required ports are ready.'
  echo 'No directories, credentials, databases, HDFS paths, or processes were created.'
  exit 0
fi

RUN_ID=${CLOUD_RUN_ID:-"cloud_$(date -u +%y%m%d_%H%M%S)_$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')"}
[[ "$RUN_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{5,40}$ ]] || { echo 'CLOUD_RUN_ID shape rejected.' >&2; exit 2; }
RUN_DIR="$E2E_ROOT/$RUN_ID"
[[ ! -e "$RUN_DIR" ]] || { echo 'Run directory exists; refusing reuse.' >&2; exit 5; }
mkdir -p "$RUN_DIR"/{pids,logs,mysql-data,mysql-tmp,flume-input,flume-out,flume-channel/checkpoint,flume-channel/data,generator-output,mall-landing,spark-warehouse,metric-staging,platform-spark-conf}
chmod 700 "$RUN_DIR" "$RUN_DIR/pids" "$RUN_DIR/logs" "$RUN_DIR/mysql-data"
printf '%s\n' "$RUN_ID" >"$RUN_DIR/run-id"
mkdir -p "$(dirname "$LATEST")"; ln -s "$RUN_DIR" "$LATEST.next"; mv -Tf "$LATEST.next" "$LATEST"

META_DB="${RUN_ID}_meta"; METRIC_DB="${RUN_ID}_metric"; MALL_DB="${RUN_ID}_mall"; GEN_DB="${RUN_ID}_gen"
META_USER="${RUN_ID}_m"; PUBLISH_USER="${RUN_ID}_p"; READ_USER="${RUN_ID}_r"; MALL_USER="${RUN_ID}_s"; GEN_USER="${RUN_ID}_g"
DB_ROOT=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
META_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
PUBLISH_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
READ_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
MALL_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
GEN_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH" LD_LIBRARY_PATH="$MYSQL_RUNTIME_LIBS"
export HADOOP_HOME HADOOP_CONF_DIR HADOOP_PID_DIR="$RUN_DIR/pids/hadoop"
export HIVE_HOME HIVE_CONF_DIR="$RUN_DIR/hive-conf" HIVE_AUX_JARS_PATH="$HIVE_HOME/lib"
export SPARK_HOME FLUME_HOME
mkdir -p "$HADOOP_PID_DIR"

MYSQL="$MYSQL_BASE/bin/mysql"
cat >"$RUN_DIR/my.cnf" <<EOF
[mysqld]
basedir=$MYSQL_BASE
datadir=$RUN_DIR/mysql-data
tmpdir=$RUN_DIR/mysql-tmp
socket=$RUN_DIR/mysql.sock
pid-file=$RUN_DIR/pids/mysql.pid
log-error=$RUN_DIR/logs/mysql-error.log
port=3307
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
local-infile=OFF
secure-file-priv=NULL
innodb-buffer-pool-size=256M
EOF
[[ ! -e "$RUN_DIR/mysql-data/auto.cnf" ]] || { echo 'Refusing to initialize non-empty MySQL directory.' >&2; exit 5; }
"$MYSQL_BASE/bin/mysqld" --defaults-file="$RUN_DIR/my.cnf" --initialize-insecure >"$RUN_DIR/logs/mysql-initialize.log" 2>&1
setsid "$MYSQL_BASE/bin/mysqld" --defaults-file="$RUN_DIR/my.cnf" </dev/null >"$RUN_DIR/logs/mysql-console.log" 2>&1 &
echo $! >"$RUN_DIR/pids/mysql.launcher.pid"
for _ in $(seq 1 90); do [[ -S "$RUN_DIR/mysql.sock" ]] && break; sleep 1; done
[[ -S "$RUN_DIR/mysql.sock" ]] || { echo 'Task MySQL did not create its socket.' >&2; exit 6; }
"$MYSQL" --no-defaults --protocol=SOCKET --socket="$RUN_DIR/mysql.sock" --user=root -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$DB_ROOT';" >/dev/null 2>&1 || { echo 'Task MySQL bootstrap failed.' >&2; exit 6; }
sql="CREATE DATABASE \`$META_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE \`$METRIC_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE \`$MALL_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE \`$GEN_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$META_USER'@'127.0.0.1' IDENTIFIED BY '$META_PASSWORD';
CREATE USER '$PUBLISH_USER'@'127.0.0.1' IDENTIFIED BY '$PUBLISH_PASSWORD';
CREATE USER '$READ_USER'@'127.0.0.1' IDENTIFIED BY '$READ_PASSWORD';
CREATE USER '$MALL_USER'@'127.0.0.1' IDENTIFIED BY '$MALL_PASSWORD';
CREATE USER '$GEN_USER'@'127.0.0.1' IDENTIFIED BY '$GEN_PASSWORD';
GRANT ALL PRIVILEGES ON \`$META_DB\`.* TO '$META_USER'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`$METRIC_DB\`.* TO '$PUBLISH_USER'@'127.0.0.1';
GRANT SELECT ON \`$METRIC_DB\`.* TO '$READ_USER'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`$MALL_DB\`.* TO '$MALL_USER'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`$GEN_DB\`.* TO '$GEN_USER'@'127.0.0.1';"
MYSQL_PWD="$DB_ROOT" "$MYSQL" --no-defaults --protocol=SOCKET --socket="$RUN_DIR/mysql.sock" --user=root < <(printf '%s\n' "$sql") >/dev/null 2>&1 || { echo 'Task schema bootstrap failed.' >&2; exit 6; }

setsid bash -c 'export JAVA_HOME="$1" HADOOP_HOME="$2" HADOOP_CONF_DIR="$3" HADOOP_PID_DIR="$4"; "$HADOOP_HOME/bin/hdfs" --daemon start namenode && "$HADOOP_HOME/bin/hdfs" --daemon start datanode' _ "$JAVA_HOME" "$HADOOP_HOME" "$HADOOP_CONF_DIR" "$HADOOP_PID_DIR" </dev/null >"$RUN_DIR/logs/hdfs-start.log" 2>&1
for _ in $(seq 1 120); do
  if "$HADOOP_HOME/bin/hdfs" dfsadmin -report >"$RUN_DIR/logs/hdfs-health.log" 2>&1 \
      && grep -q 'Live datanodes (1)' "$RUN_DIR/logs/hdfs-health.log" \
      && "$HADOOP_HOME/bin/hdfs" dfsadmin -safemode get >"$RUN_DIR/logs/hdfs-safemode.log" 2>&1 \
      && grep -q 'Safe mode is OFF' "$RUN_DIR/logs/hdfs-safemode.log"; then
    break
  fi
  sleep 1
done
grep -q 'Live datanodes (1)' "$RUN_DIR/logs/hdfs-health.log" \
  && grep -q 'Safe mode is OFF' "$RUN_DIR/logs/hdfs-safemode.log" \
  || { echo 'HDFS did not reach one live DataNode with safe mode off.' >&2; exit 7; }
if "$HADOOP_HOME/bin/hdfs" dfs -test -e "/landing/$RUN_ID"; then
  echo 'Run-scoped HDFS path already exists; refusing reuse.' >&2
  exit 5
fi
"$HADOOP_HOME/bin/hdfs" dfs -mkdir -p "/landing/$RUN_ID/raw" "/landing/$RUN_ID/accepted" "/landing/$RUN_ID/quarantine" "/landing/$RUN_ID/manifests" "/warehouse/$RUN_ID" >/dev/null 2>&1 || { echo 'Run-scoped HDFS directories could not be created.' >&2; exit 7; }

# Thrift HMS is task-owned and has a separate embedded Derby catalog from the
# platform SINGLE_NODE pipeline catalog below.
mkdir -p "$HIVE_CONF_DIR"
cp "$HADOOP_CONF_DIR/core-site.xml" "$HIVE_CONF_DIR/core-site.xml"
cp "$HADOOP_CONF_DIR/hdfs-site.xml" "$HIVE_CONF_DIR/hdfs-site.xml"
cat >"$HIVE_CONF_DIR/hive-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
 <property><name>javax.jdo.option.ConnectionURL</name><value>jdbc:derby:$RUN_DIR/metastore-derby;create=true</value></property>
 <property><name>javax.jdo.option.ConnectionDriverName</name><value>org.apache.derby.jdbc.EmbeddedDriver</value></property>
 <property><name>hive.metastore.uris</name><value></value></property>
 <property><name>hive.metastore.port</name><value>$HMS_PORT</value></property>
 <property><name>hive.metastore.schema.verification</name><value>false</value></property>
 <property><name>hive.metastore.warehouse.dir</name><value>hdfs://127.0.0.1:$HDFS_RPC_PORT/warehouse/$RUN_ID</value></property>
</configuration>
EOF
[[ ! -e "$RUN_DIR/metastore-derby" ]] || { echo 'Task metastore Derby directory exists; refusing reuse.' >&2; exit 5; }
"$HIVE_HOME/bin/schematool" -dbType derby -initSchema >"$RUN_DIR/logs/hive-schema-init.log" 2>&1 || { echo 'Task Hive Metastore schema initialization failed; inspect task-local schema log.' >&2; exit 7; }
setsid bash -c 'export JAVA_HOME="$1" HADOOP_HOME="$2" HADOOP_CONF_DIR="$3" HIVE_HOME="$4" HIVE_CONF_DIR="$5" HIVE_AUX_JARS_PATH="$6"; exec "$HIVE_HOME/bin/hive" --service metastore' _ "$JAVA_HOME" "$HADOOP_HOME" "$HADOOP_CONF_DIR" "$HIVE_HOME" "$HIVE_CONF_DIR" "$HIVE_AUX_JARS_PATH" </dev/null >"$RUN_DIR/logs/hive-metastore.log" 2>&1 &
echo $! >"$RUN_DIR/pids/metastore.pid"
for _ in $(seq 1 90); do ss -ltnH "sport = :$HMS_PORT" | grep -q . && break; sleep 1; done
ss -ltnH "sport = :$HMS_PORT" | grep -q . || { echo 'Hive Metastore Thrift health check failed.' >&2; exit 7; }

cp "$HADOOP_CONF_DIR/core-site.xml" "$RUN_DIR/platform-spark-conf/core-site.xml"
cp "$HADOOP_CONF_DIR/hdfs-site.xml" "$RUN_DIR/platform-spark-conf/hdfs-site.xml"
cat >"$RUN_DIR/platform-spark-conf/hive-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
 <property><name>javax.jdo.option.ConnectionURL</name><value>jdbc:derby:$RUN_DIR/derby-embedded;create=true</value></property>
 <property><name>javax.jdo.option.ConnectionDriverName</name><value>org.apache.derby.jdbc.EmbeddedDriver</value></property>
 <property><name>hive.metastore.uris</name><value></value></property>
 <property><name>hive.metastore.schema.verification</name><value>false</value></property>
 <property><name>hive.metastore.warehouse.dir</name><value>file://$RUN_DIR/spark-warehouse</value></property>
 <property><name>hadoop.tmp.dir</name><value>$RUN_DIR/spark-tmp</value></property>
</configuration>
EOF

export PLATFORM_META_URL="jdbc:mysql://127.0.0.1:3307/$META_DB?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
export PLATFORM_META_USER="$META_USER" PLATFORM_META_PASSWORD="$META_PASSWORD"
export PLATFORM_METRIC_PUBLISH_URL="jdbc:mysql://127.0.0.1:3307/$METRIC_DB?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
export PLATFORM_METRIC_PUBLISH_USER="$PUBLISH_USER" PLATFORM_METRIC_PUBLISH_PASSWORD="$PUBLISH_PASSWORD"
export PLATFORM_METRIC_READ_URL="$PLATFORM_METRIC_PUBLISH_URL" PLATFORM_METRIC_READ_USER="$READ_USER" PLATFORM_METRIC_READ_PASSWORD="$READ_PASSWORD"
export PLATFORM_LANDING_LOCAL_ROOT="$RUN_DIR/mall-landing" PLATFORM_SOURCE_PROFILE_ROOT="$ROOT"
export PLATFORM_SPARK_WAREHOUSE_DIR="$RUN_DIR/spark-warehouse" PLATFORM_SPARK_METASTORE_DIR="$RUN_DIR/derby-embedded"
# Pin Spark and Hadoop to the platform configuration generated for this RunId.
# Without both variables Spark falls back to SPARK_HOME/conf/hive-site.xml, and
# an inherited HADOOP_CONF_DIR can point at a previous run's Derby/metastore.
export HADOOP_CONF_DIR="$RUN_DIR/platform-spark-conf" SPARK_CONF_DIR="$RUN_DIR/platform-spark-conf"
export PLATFORM_SPARK_JOB_TIMEOUT_MS=900000 PLATFORM_SPARK_POLL_INTERVAL_MS=1000
export PLATFORM_MAPPING_CONTRACT_PATH="$ROOT/contract-specs/schemas/canonical-event.v1.schema.json" PLATFORM_MAPPING_SAMPLE_ROOT="$ROOT/sample-data"
setsid java -Xms256m -Xmx768m -Dfile.encoding=UTF-8 -Dplatform.metric.publish.export-dir="$RUN_DIR/metric-staging" \
  -jar "$ROOT/analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar" </dev/null >"$RUN_DIR/logs/platform.log" 2>&1 &
echo $! >"$RUN_DIR/pids/platform.pid"

export MALL_LANDING_PATH="$RUN_DIR/mall-landing" MALL_DB_USER="$MALL_USER" MALL_DB_PASSWORD="$MALL_PASSWORD"
setsid java -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -jar "$ROOT/mall-simulator/target/mall-simulator-0.1.0-SNAPSHOT.jar" \
  --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/$MALL_DB?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8" \
  --spring.datasource.username="$MALL_USER" \
  </dev/null >"$RUN_DIR/logs/mall.log" 2>&1 &
echo $! >"$RUN_DIR/pids/mall.pid"

export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3307/$GEN_DB?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
export SPRING_DATASOURCE_USERNAME="$GEN_USER" SPRING_DATASOURCE_PASSWORD="$GEN_PASSWORD"
export GENERATOR_OUTPUT_ROOT="$RUN_DIR/generator-output" GENERATOR_LOG_FILE="$RUN_DIR/logs/generator.log"
setsid java -Xms128m -Xmx512m -Dfile.encoding=UTF-8 -jar \
  "$ROOT/synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar" </dev/null >"$RUN_DIR/logs/generator-console.log" 2>&1 &
echo $! >"$RUN_DIR/pids/generator.pid"

if [[ "$NODE_BIN" == *.exe ]]; then
  wslpath -w "$NODE_BIN" >"$RUN_DIR/pids/node-bin.windows-path"
  for name in vite-web vite-mall; do
    app_dir="$ROOT/web"; port=5173
    [[ "$name" == vite-mall ]] && app_dir="$ROOT/mall-frontend" && port=5174
    win_pid_file="$RUN_DIR/pids/$name.windows.pid"
    win_pid_path=$(wslpath -w "$win_pid_file")
    relative_vite='./node_modules/vite/bin/vite.js'
    run_marker="cloud-single-node-run-id:$RUN_ID"
    node_bootstrap="const fs=require('node:fs');const path=require('node:path');const {pathToFileURL}=require('node:url');const [pidFile,marker,vite,port]=process.argv.slice(1);fs.writeFileSync(pidFile,String(process.pid),{mode:0o600});process.argv=[process.execPath,vite,'--host','127.0.0.1','--port',port];import(pathToFileURL(path.resolve(vite)).href);"
    (cd "$app_dir" && exec setsid "$NODE_BIN" -e "$node_bootstrap" "$win_pid_path" "$run_marker" "$relative_vite" "$port") \
      </dev/null >"$RUN_DIR/logs/$name.log" 2>&1 & echo $! >"$RUN_DIR/pids/$name.pid"
  done
else
  (cd "$ROOT/web" && exec setsid "$NODE_BIN" ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173) </dev/null >"$RUN_DIR/logs/vite-web.log" 2>&1 & echo $! >"$RUN_DIR/pids/vite-web.pid"
  (cd "$ROOT/mall-frontend" && exec setsid "$NODE_BIN" ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5174) </dev/null >"$RUN_DIR/logs/vite-mall.log" 2>&1 & echo $! >"$RUN_DIR/pids/vite-mall.pid"
fi

cat >"$RUN_DIR/flume.conf" <<EOF
a1.sources = r
a1.sources.r.type = spooldir
a1.sources.r.spoolDir = $RUN_DIR/flume-input
a1.sources.r.fileHeader = true
a1.sources.r.ignorePattern = ^.*\\.(COMPLETED|tmp|manifest\\.json)$
a1.sources.r.channels = c
a1.channels = c
a1.channels.c.type = file
a1.channels.c.checkpointDir = $RUN_DIR/flume-channel/checkpoint
a1.channels.c.dataDirs = $RUN_DIR/flume-channel/data
a1.channels.c.useDualCheckpoints = false
a1.channels.c.checkpointInterval = 30000
a1.channels.c.capacity = 2000
a1.channels.c.transactionCapacity = 500
a1.channels.c.keep-alive = 3
a1.channels.c.write-timeout = 3
a1.sinks = k
a1.sinks.k.type = hdfs
a1.sinks.k.hdfs.path = hdfs://127.0.0.1:$HDFS_RPC_PORT/landing/$RUN_ID/raw/dt=%Y%m%d/hour=%H
a1.sinks.k.hdfs.filePrefix = events-
a1.sinks.k.hdfs.fileSuffix = .jsonl
a1.sinks.k.hdfs.fileType = DataStream
a1.sinks.k.hdfs.writeFormat = Text
a1.sinks.k.hdfs.rollInterval = 4
a1.sinks.k.hdfs.rollSize = 0
a1.sinks.k.hdfs.rollCount = 0
a1.sinks.k.hdfs.batchSize = 100
a1.sinks.k.hdfs.useLocalTimeStamp = true
a1.sinks.k.channel = c
EOF
export JAVA_HOME HADOOP_HOME HADOOP_CONF_DIR
export FLUME_JAVA_OPTS=-Xmx256m
setsid "$FLUME_HOME/bin/flume-ng" agent --conf "$FLUME_HOME/conf" --conf-file "$RUN_DIR/flume.conf" --name a1 -Dflume.root.logger=INFO,console \
  </dev/null >"$RUN_DIR/logs/flume.log" 2>&1 &
echo $! >"$RUN_DIR/pids/flume.pid"

wait_http() {
  local url=$1; local tries=${2:-90}
  local code
  for _ in $(seq 1 "$tries"); do
    code=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 2 "$url" 2>/dev/null || true)
    [[ "$code" =~ ^[234][0-9][0-9]$ ]] && return 0
    sleep 1
  done
  return 1
}
wait_http http://127.0.0.1:8091/api/v1/metrics/health || { echo 'Platform health check failed; inspect task-local logs.' >&2; exit 8; }
wait_http http://127.0.0.1:8090/ || { echo 'Mall HTTP health check failed; inspect task-local logs.' >&2; exit 8; }
wait_http http://127.0.0.1:8092/api/v1/scenarios || { echo 'Generator health check failed; inspect task-local logs.' >&2; exit 8; }
wait_http http://127.0.0.1:5173/login || { echo 'Analytics frontend health check failed.' >&2; exit 8; }
wait_http http://127.0.0.1:5174/login || { echo 'Mall frontend health check failed.' >&2; exit 8; }

profile_sql="UPDATE runtime_profile SET type='SINGLE_NODE', status='ACTIVE', landing_uri='hdfs://127.0.0.1:$HDFS_RPC_PORT/landing/$RUN_ID', landing_layout='FLUME_RAW', spark_master='local[1]', deploy_mode='client', spark_submit_path='$SPARK_HOME/bin/spark-submit', spark_job_jar_uri='$ROOT/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar', hive_jdbc_url=NULL, version=version+1 WHERE profile_code='local-dev';"
MYSQL_PWD="$META_PASSWORD" "$MYSQL" --no-defaults --protocol=TCP --host=127.0.0.1 --port=3307 --user="$META_USER" "$META_DB" < <(printf '%s\n' "$profile_sql") >/dev/null 2>&1 || { echo 'Task Linux RuntimeProfile setup failed.' >&2; exit 8; }
cat >"$RUN_DIR/runtime.json" <<EOF
{
  "run_id":"$RUN_ID",
  "ports":{"mysql":3307,"hdfs_rpc":$HDFS_RPC_PORT,"hdfs_namenode_http":$HDFS_NN_HTTP_PORT,"hdfs_datanode":$HDFS_DATA_PORT,"hive_metastore_thrift":$HMS_PORT,"platform":8091,"mall":8090,"generator":8092,"analytics_vite":5173,"mall_vite":5174},
  "schemas":{"meta":"$META_DB","metric":"$METRIC_DB","mall":"$MALL_DB","generator":"$GEN_DB"},
  "hdfs_root":"hdfs://127.0.0.1:$HDFS_RPC_PORT/landing/$RUN_ID",
  "profile":"SINGLE_NODE / FLUME_RAW / Spark local[1]",
  "pipeline_metastore":"embedded Derby at task-owned PLATFORM_SPARK_METASTORE_DIR",
  "standalone_hms":"Thrift 127.0.0.1:$HMS_PORT; task-owned backend Derby at $RUN_DIR/metastore-derby",
  "credentials":"generated in-process; not stored"
}
EOF
chmod 600 "$RUN_DIR/runtime.json" "$RUN_DIR/my.cnf"
printf 'Started isolated Linux single-node runtime. run_id=%s\n' "$RUN_ID"
printf 'Task run directory: %s\n' "$RUN_DIR"
