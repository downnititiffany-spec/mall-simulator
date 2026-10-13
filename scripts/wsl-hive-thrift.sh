#!/usr/bin/env bash
set -Eeuo pipefail

# Run-scoped Hive Metastore Thrift + HiveServer2. Its Derby catalog is distinct
# from the platform's embedded Spark catalog. Never delete/reinitialize data.
HIVE_HOME=${CLOUD_HIVE_HOME:-/opt/hive-3.1.3}
JAVA_HOME=${CLOUD_JAVA_HOME:-/opt/jdk-8u351}
HADOOP_HOME=${CLOUD_HADOOP_HOME:-/opt/hadoop-3.3.4}
HDFS_AUTHORITY=${CLOUD_HIVE_HDFS_AUTHORITY:-}
METASTORE_PORT=${CLOUD_HIVE_METASTORE_PORT:-19083}
HIVESERVER2_PORT=${CLOUD_HIVE_SERVER2_PORT:-10015}
RUNS_ROOT=${CLOUD_HIVE_RUNS_ROOT:-/home/asus/graduationproject-runs}

if [[ $# -ne 2 || ! "$1" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{2,63}$ || ! "$2" =~ ^(start|status|stop)$ ]]; then
  echo "用法: $0 <run-id> <start|status|stop>" >&2; exit 2
fi
run_id="$1"; action="$2"
readonly run_root="$RUNS_ROOT/$run_id"
HADOOP_CONF_DIR=${CLOUD_HADOOP_CONF_DIR:-$run_root/hadoop-conf}
readonly conf_dir="$run_root/hive-conf" derby_dir="$run_root/hive-metastore/derby"
readonly log_dir="$run_root/hive-logs" pid_dir="$run_root/hive-pids"
warehouse=""
port_open() { ss -H -ltn "sport = :$1" 2>/dev/null | grep -q .; }

if [[ "$action" == status ]]; then
  printf 'metastore_%s=%s\nhiveserver2_%s=%s\n' \
    "$METASTORE_PORT" \
    "$(port_open "$METASTORE_PORT" && echo LISTENING || echo STOPPED)" \
    "$HIVESERVER2_PORT" \
    "$(port_open "$HIVESERVER2_PORT" && echo LISTENING || echo STOPPED)"
  exit 0
fi

hive_process_identity_matches_run() {
  local pid="$1" service="$2" expected_class expected_cwd actual_cwd actual_exe pgid
  case "$service" in
    metastore) expected_class='org.apache.hadoop.hive.metastore.HiveMetaStore' ;;
    hiveserver2) expected_class='org.apache.hive.service.server.HiveServer2' ;;
    *) return 1 ;;
  esac
  [[ -r "/proc/$pid/cmdline" && -r "/proc/$pid/environ" && -e "/proc/$pid/cwd" && -e "/proc/$pid/exe" ]] || return 1
  expected_cwd="$(realpath -m "$run_root")"
  actual_cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" || return 1
  actual_exe="$(readlink -f "/proc/$pid/exe" 2>/dev/null)" || return 1
  [[ "$actual_cwd" == "$expected_cwd" && "$actual_exe" == "$(realpath -m "$JAVA_HOME/bin/java")" ]] || return 1
  tr '\0' '\n' <"/proc/$pid/cmdline" | grep -Fqx "$expected_class" || return 1
  tr '\0' '\n' <"/proc/$pid/environ" | grep -Fqx "HIVE_CONF_DIR=$conf_dir" || return 1
  tr '\0' '\n' <"/proc/$pid/environ" | grep -Fqx "HADOOP_CONF_DIR=$HADOOP_CONF_DIR" || return 1
  pgid="$(ps -o pgid= -p "$pid" | tr -d '[:space:]')"
  [[ "$pgid" == "$pid" ]]
}
if [[ ! -d "$HADOOP_CONF_DIR" || ! -r "$HADOOP_CONF_DIR/core-site.xml" || \
      ! -x "$HIVE_HOME/bin/hive" || ! -x "$HADOOP_HOME/bin/hdfs" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "本 RunId 的 HDFS 环境或 Hive/JDK 工具链缺失" >&2; exit 3
fi

if [[ "$action" == start ]]; then
  export JAVA_HOME HADOOP_HOME HADOOP_CONF_DIR HIVE_HOME
  default_fs="$("$HADOOP_HOME/bin/hdfs" getconf -confKey fs.defaultFS 2>/dev/null)" || {
    echo "无法从指定 Hadoop 配置读取 fs.defaultFS；拒绝创建 Hive 数据" >&2; exit 3;
  }
  if [[ ! "$default_fs" =~ ^hdfs://([a-zA-Z0-9._-]+):([0-9]{1,5})$ ]]; then
    echo "指定配置的 fs.defaultFS 不是明确的单节点 HDFS URI：$default_fs；拒绝创建 Hive 数据" >&2; exit 3
  fi
  configured_authority="${BASH_REMATCH[1]}:${BASH_REMATCH[2]}"
  if [[ -n "$HDFS_AUTHORITY" && "$HDFS_AUTHORITY" != "$configured_authority" ]]; then
    echo "显式 HDFS authority 与配置不一致；拒绝创建 Hive 数据" >&2; exit 3
  fi
  HDFS_AUTHORITY="$configured_authority"
  warehouse="hdfs://$HDFS_AUTHORITY/warehouse/$run_id/hive-warehouse"
  hdfs_rpc_port="${HDFS_AUTHORITY##*:}"
  for port in "$hdfs_rpc_port" "$METASTORE_PORT" "$HIVESERVER2_PORT"; do
    if [[ ! "$port" =~ ^[0-9]{1,5}$ ]] || ((port < 1024 || port > 65535)); then
      echo "无效服务端口；拒绝创建 Hive 数据" >&2; exit 3
    fi
  done
  if ! port_open "$hdfs_rpc_port"; then
    echo "配置中的 HDFS RPC 端口未监听；拒绝创建 Hive 数据" >&2; exit 3
  fi
  if ! "$HADOOP_HOME/bin/hdfs" dfs -stat '%n' / >/dev/null 2>&1; then
    echo "无法只读访问配置指定的 HDFS 根目录；拒绝创建 Hive 数据" >&2; exit 3
  fi
  if port_open "$METASTORE_PORT" || port_open "$HIVESERVER2_PORT"; then
    echo "Hive 服务端口已占用；拒绝连接或终止未知服务" >&2; exit 4
  fi
  mkdir -p "$conf_dir" "$run_root/hive-metastore" "$run_root/hive-tmp" "$log_dir" "$pid_dir"
  cat >"$conf_dir/hive-site.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <property><name>fs.defaultFS</name><value>hdfs://$HDFS_AUTHORITY</value></property>
  <property><name>hive.metastore.uris</name><value>thrift://127.0.0.1:$METASTORE_PORT</value></property>
  <property><name>hive.metastore.port</name><value>$METASTORE_PORT</value></property>
  <!-- Hive 3.1.3 local single-user test catalog: allow HS2's notification poll. -->
  <property><name>hive.metastore.event.db.notification.api.auth</name><value>false</value></property>
  <property><name>hive.metastore.warehouse.dir</name><value>$warehouse</value></property>
  <property><name>javax.jdo.option.ConnectionURL</name><value>jdbc:derby:$derby_dir;create=true</value></property>
  <property><name>javax.jdo.option.ConnectionDriverName</name><value>org.apache.derby.jdbc.EmbeddedDriver</value></property>
  <property><name>hive.metastore.schema.verification</name><value>false</value></property>
  <property><name>datanucleus.schema.autoCreateAll</name><value>false</value></property>
  <property><name>hive.server2.thrift.bind.host</name><value>127.0.0.1</value></property>
  <property><name>hive.server2.thrift.port</name><value>$HIVESERVER2_PORT</value></property>
  <property><name>hive.server2.authentication</name><value>NONE</value></property>
  <property><name>hive.server2.enable.doAs</name><value>false</value></property>
  <property><name>hadoop.tmp.dir</name><value>$run_root/hive-tmp</value></property>
  <property><name>mapreduce.framework.name</name><value>local</value></property>
</configuration>
EOF
  export HIVE_CONF_DIR="$conf_dir"
  export HIVE_AUX_JARS_PATH="$HIVE_HOME/lib"
  cd "$run_root"
  if [[ -e "$derby_dir" ]]; then
    if [[ ! -d "$derby_dir" ]]; then
      echo "Derby catalog 路径存在但不是目录；拒绝修改" >&2; exit 5
    fi
    if ! "$HIVE_HOME/bin/schematool" -dbType derby -info >"$log_dir/schematool-info.log" 2>&1 || \
       ! grep -Fq "jdbc:derby:$derby_dir;create=true" "$log_dir/schematool-info.log" || \
       ! grep -Fq "schemaTool completed" "$log_dir/schematool-info.log"; then
      echo "已有 Derby catalog 未通过只读 schema/路径校验；拒绝重初始化或改写，请查看 $log_dir/schematool-info.log" >&2; exit 5
    fi
  else
    "$HIVE_HOME/bin/schematool" -dbType derby -initSchema >"$log_dir/schematool.log" 2>&1 || {
      echo "本 RunId Derby schema 初始化失败；查看 $log_dir/schematool.log" >&2; exit 6
    }
  fi
  "$HADOOP_HOME/bin/hdfs" dfs -mkdir -p "/warehouse/$run_id/hive-warehouse"
  setsid bash -c 'cd "$1"; export JAVA_HOME="$2" HADOOP_HOME="$3" HADOOP_CONF_DIR="$4" HIVE_HOME="$5" HIVE_CONF_DIR="$6" HIVE_AUX_JARS_PATH="$7"; exec "$HIVE_HOME/bin/hive" --service metastore' \
    _ "$run_root" "$JAVA_HOME" "$HADOOP_HOME" "$HADOOP_CONF_DIR" "$HIVE_HOME" "$conf_dir" "$HIVE_HOME/lib" \
    </dev/null >"$log_dir/metastore.log" 2>&1 &
  printf '%s\n' "$!" >"$pid_dir/metastore.pid"
  for _ in $(seq 1 90); do port_open "$METASTORE_PORT" && break; sleep 1; done
  if ! port_open "$METASTORE_PORT"; then echo "Thrift Metastore 未就绪；见 $log_dir/metastore.log" >&2; exit 7; fi
  setsid bash -c 'cd "$1"; export JAVA_HOME="$2" HADOOP_HOME="$3" HADOOP_CONF_DIR="$4" HIVE_HOME="$5" HIVE_CONF_DIR="$6" HIVE_AUX_JARS_PATH="$7"; exec "$HIVE_HOME/bin/hive" --service hiveserver2' \
    _ "$run_root" "$JAVA_HOME" "$HADOOP_HOME" "$HADOOP_CONF_DIR" "$HIVE_HOME" "$conf_dir" "$HIVE_HOME/lib" \
    </dev/null >"$log_dir/hiveserver2.log" 2>&1 &
  printf '%s\n' "$!" >"$pid_dir/hiveserver2.pid"
  for _ in $(seq 1 120); do port_open "$HIVESERVER2_PORT" && break; sleep 1; done
  if ! port_open "$HIVESERVER2_PORT"; then echo "HiveServer2 未就绪；见 $log_dir/hiveserver2.log" >&2; exit 8; fi
  printf 'runId=%s\nmetastoreUri=thrift://127.0.0.1:%s\nhiveJdbcUrl=jdbc:hive2://127.0.0.1:%s/default\nderbyCatalog=run-scoped embedded Derby\nwarehouse=%s\n' \
    "$run_id" "$METASTORE_PORT" "$HIVESERVER2_PORT" "$warehouse"
  exit 0
fi

declare -A stop_pids=()
for service in hiveserver2 metastore; do
  pid_file="$pid_dir/$service.pid"
  port="$HIVESERVER2_PORT"; [[ "$service" == metastore ]] && port="$METASTORE_PORT"
  if [[ ! -f "$pid_file" ]]; then
    if port_open "$port"; then echo "端口 $port 正在监听但缺少本 RunId PID 文件；拒绝终止未知服务" >&2; exit 9; fi
    continue
  fi
  pid="$(<"$pid_file")"
  if [[ ! "$pid" =~ ^[0-9]+$ ]]; then echo "本 RunId 的 $service PID 文件无效；拒绝终止任何服务" >&2; exit 9; fi
  if kill -0 "$pid" 2>/dev/null; then
    if ! hive_process_identity_matches_run "$pid" "$service"; then
      echo "PID $pid 不匹配本 RunId 的 $service 进程身份；拒绝终止任何服务" >&2; exit 9
    fi
    stop_pids[$service]="$pid"
  elif port_open "$port"; then
    echo "端口 $port 正在监听但本 RunId PID 已失效；拒绝终止未知服务" >&2; exit 9
  fi
done
for service in hiveserver2 metastore; do
  if [[ -n "${stop_pids[$service]:-}" ]]; then
    kill -TERM -- "-${stop_pids[$service]}"
  fi
done
for _ in $(seq 1 30); do
  if ! port_open "$METASTORE_PORT" && ! port_open "$HIVESERVER2_PORT"; then echo "本 RunId Hive 服务已停止；Derby/HDFS 数据保留。"; exit 0; fi
  sleep 1
done
echo "Hive 服务停止等待超时；请检查 $log_dir" >&2
exit 10
