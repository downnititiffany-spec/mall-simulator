#!/usr/bin/env bash
# Reuse verified binaries and the initialized task HDFS namespace. Secrets exist
# only in this shell and child process environments; no environment snapshot is saved.
set -Eeuo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS=/workspace/toolchains
HDFS_RUN=/workspace/single-node/runs/cloud_20261001_hdfs01
ACTION=${1:-start}
LATEST=/workspace/single-node/cloud-e2e/current

if [[ "$ACTION" == status ]]; then
  [[ -r "$LATEST/run-id" ]] || { echo 'No task run registered.'; exit 1; }
  printf 'run_id=%s\n' "$(<"$LATEST/run-id")"
  for port in 3307 19000 19010 19083 8091 8090 8092 5173 5174; do
    if ss -ltnH "sport = :$port" | grep -q .; then echo "$port LISTEN"; else echo "$port closed"; fi
  done
  exit 0
fi

if [[ "$ACTION" == stop ]]; then
  [[ -r "$LATEST/run-id" ]] || { echo 'No task run registered.'; exit 1; }
  run_id=$(<"$LATEST/run-id"); run_dir="/workspace/single-node/cloud-e2e/$run_id"
  [[ -r "$run_dir/run-id" && "$(<"$run_dir/run-id")" == "$run_id" ]] || { echo 'Run identity check failed.' >&2; exit 1; }
  for name in vite-mall vite-web generator mall platform flume metastore mysql; do
    f="$run_dir/pids/$name.pid"; [[ -r "$f" ]] || continue
    pid=$(<"$f"); [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || continue
    cmd=$(tr '\0' ' ' <"/proc/$pid/cmdline")
    cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null || true)
    case "$name:$cmd" in
      vite-mall:*mall-frontend*|vite-web:*web/node_modules/.bin/vite*|generator:*synthetic-data-generator*|mall:*mall-simulator*|platform:*platform-app*|flume:*flume-ng*|metastore:*metastore*|metastore:*HiveMetaStore*|mysql:*mysqld*) kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true ;;
      *) printf 'Refused mismatched PID %s for %s\n' "$pid" "$name" >&2 ;;
    esac
    if [[ "$name" == vite-web && "$cwd" == "$ROOT/web" && "$cmd" == *vite* ]] || [[ "$name" == vite-mall && "$cwd" == "$ROOT/mall-frontend" && "$cmd" == *vite* ]]; then
      kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    fi
  done
  export JAVA_HOME="$TOOLS/jdk17" HADOOP_HOME="$TOOLS/hadoop-3.3.4" HADOOP_CONF_DIR="$HDFS_RUN/conf" HADOOP_PID_DIR="$run_dir/pids/hadoop"
  "$HADOOP_HOME/bin/hdfs" --daemon stop datanode >/dev/null 2>&1 || true
  "$HADOOP_HOME/bin/hdfs" --daemon stop namenode >/dev/null 2>&1 || true
  echo "Stopped task-owned processes for $run_id; database and HDFS files are preserved."
  exit 0
fi
[[ "$ACTION" == start ]] || { echo 'usage: cloud-single-node.sh [start|status|stop]' >&2; exit 2; }

for required in "$TOOLS/mysql-8.0.41-linux-glibc2.28-x86_64/bin/mysqld" "$TOOLS/hadoop-3.3.4/bin/hdfs" "$TOOLS/hive-3.1.3/bin/hive" "$TOOLS/spark-3.5.1/bin/spark-submit" "$TOOLS/maven/mvn/bin/mvn" "$ROOT/analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar" "$ROOT/mall-simulator/target/mall-simulator-0.1.0-SNAPSHOT.jar" "$ROOT/synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar" "$ROOT/web/node_modules/.bin/vite" "$ROOT/mall-frontend/node_modules/.bin/vite"; do
  [[ -e "$required" ]] || { printf 'Verified component/artifact missing: %s\n' "$required" >&2; exit 3; }
done
[[ -r "$HDFS_RUN/name/current/VERSION" && -r "$HDFS_RUN/data/current/VERSION" ]] || { echo 'Initialized HDFS namespace missing; refusing format or recreation.' >&2; exit 4; }
for port in 3307 19000 19010 19083 8091 8090 8092 5173 5174; do
  if ss -ltnH "sport = :$port" | grep -q .; then printf 'Port %s occupied; refusing takeover.\n' "$port" >&2; exit 5; fi
done

RUN_ID=${CLOUD_RUN_ID:-"cloud_$(date -u +%y%m%d_%H%M%S)_$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')"}
[[ "$RUN_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{5,40}$ ]] || { echo 'CLOUD_RUN_ID shape rejected.' >&2; exit 2; }
RUN_DIR="/workspace/single-node/cloud-e2e/$RUN_ID"
[[ ! -e "$RUN_DIR" ]] || { echo 'Run directory exists; refusing reuse.' >&2; exit 5; }
mkdir -p "$RUN_DIR"/{pids,logs,mysql-data,mysql-tmp,flume-input,flume-out,generator-output,mall-landing,spark-warehouse,metric-staging,platform-spark-conf}
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
export JAVA_HOME="$TOOLS/jdk17" PATH="$TOOLS/jdk17/bin:$PATH" LD_LIBRARY_PATH="$TOOLS/mysql-runtime-libs/usr/lib/x86_64-linux-gnu"
export HADOOP_HOME="$TOOLS/hadoop-3.3.4" HADOOP_CONF_DIR="$HDFS_RUN/conf" HADOOP_PID_DIR="$RUN_DIR/pids/hadoop"
export HIVE_HOME="$TOOLS/hive-3.1.3" HIVE_CONF_DIR="$HDFS_RUN/hive-conf" HIVE_AUX_JARS_PATH="$TOOLS/hive-3.1.3/lib"
export SPARK_HOME="$TOOLS/spark-3.5.1" FLUME_HOME="$TOOLS/flume-1.11.0"
mkdir -p "$HADOOP_PID_DIR"

MYSQL_BASE="$TOOLS/mysql-8.0.41-linux-glibc2.28-x86_64"; MYSQL="$MYSQL_BASE/bin/mysql"
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
for _ in $(seq 1 120); do "$HADOOP_HOME/bin/hdfs" dfsadmin -report >"$RUN_DIR/logs/hdfs-health.log" 2>&1 && grep -q 'Live datanodes (1)' "$RUN_DIR/logs/hdfs-health.log" && break; sleep 1; done
grep -q 'Live datanodes (1)' "$RUN_DIR/logs/hdfs-health.log" || { echo 'HDFS health check failed.' >&2; exit 7; }
"$HADOOP_HOME/bin/hdfs" dfs -mkdir -p "/landing/$RUN_ID/raw" "/landing/$RUN_ID/accepted" "/landing/$RUN_ID/quarantine" "/landing/$RUN_ID/manifests" "/warehouse/$RUN_ID" >/dev/null 2>&1 || { echo 'Run-scoped HDFS directories could not be created.' >&2; exit 7; }

# Thrift HMS is a standalone service backed by its own embedded Derby catalog.
# LOCAL/SINGLE_NODE pipeline runs below keep a different embedded Derby directory.
if [[ -r "$HDFS_RUN/pids/hive-metastore.pid" ]] && kill -0 "$(<"$HDFS_RUN/pids/hive-metastore.pid")" 2>/dev/null; then echo 'Existing Derby metastore owner is alive; refusing a second writer.' >&2; exit 5; fi
setsid bash -c 'export JAVA_HOME="$1" HADOOP_HOME="$2" HADOOP_CONF_DIR="$3" HIVE_HOME="$4" HIVE_CONF_DIR="$5" HIVE_AUX_JARS_PATH="$6"; exec "$HIVE_HOME/bin/hive" --service metastore' _ "$JAVA_HOME" "$HADOOP_HOME" "$HADOOP_CONF_DIR" "$HIVE_HOME" "$HIVE_CONF_DIR" "$HIVE_AUX_JARS_PATH" </dev/null >"$RUN_DIR/logs/hive-metastore.log" 2>&1 &
echo $! >"$RUN_DIR/pids/metastore.pid"
for _ in $(seq 1 90); do ss -ltnH 'sport = :19083' | grep -q . && break; sleep 1; done
ss -ltnH 'sport = :19083' | grep -q . || { echo 'Hive Metastore Thrift health check failed.' >&2; exit 7; }

cp "$HDFS_RUN/conf/core-site.xml" "$RUN_DIR/platform-spark-conf/core-site.xml"
cp "$HDFS_RUN/conf/hdfs-site.xml" "$RUN_DIR/platform-spark-conf/hdfs-site.xml"
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

(cd "$ROOT/web" && exec setsid ./node_modules/.bin/vite --host 127.0.0.1 --port 5173) </dev/null >"$RUN_DIR/logs/vite-web.log" 2>&1 & echo $! >"$RUN_DIR/pids/vite-web.pid"
(cd "$ROOT/mall-frontend" && exec setsid ./node_modules/.bin/vite --host 127.0.0.1 --port 5174) </dev/null >"$RUN_DIR/logs/vite-mall.log" 2>&1 & echo $! >"$RUN_DIR/pids/vite-mall.pid"

cat >"$RUN_DIR/flume.conf" <<EOF
a1.sources = r
a1.sources.r.type = spooldir
a1.sources.r.spoolDir = $RUN_DIR/flume-input
a1.sources.r.fileHeader = true
a1.sources.r.ignorePattern = ^.*\\.(COMPLETED|tmp|manifest\\.json)$
a1.sources.r.channels = c
a1.channels = c
a1.channels.c.type = memory
a1.channels.c.capacity = 2000
a1.channels.c.transactionCapacity = 500
a1.sinks = k
a1.sinks.k.type = hdfs
a1.sinks.k.hdfs.path = hdfs://127.0.0.1:19000/landing/$RUN_ID/raw/dt=%Y%m%d/hour=%H
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
export JAVA_HOME="$TOOLS/jdk17" HADOOP_HOME="$TOOLS/hadoop-3.3.4" HADOOP_CONF_DIR="$HDFS_RUN/conf"
export FLUME_JAVA_OPTS=-Xmx256m
setsid "$TOOLS/flume-1.11.0/bin/flume-ng" agent --conf "$TOOLS/flume-1.11.0/conf" --conf-file "$RUN_DIR/flume.conf" --name a1 -Dflume.root.logger=INFO,console \
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

profile_sql="UPDATE runtime_profile SET type='SINGLE_NODE', status='ACTIVE', landing_uri='hdfs://127.0.0.1:19000/landing/$RUN_ID', landing_layout='FLUME_RAW', spark_master='local[1]', deploy_mode='client', spark_submit_path='$TOOLS/spark-3.5.1/bin/spark-submit', spark_job_jar_uri='$ROOT/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar', hive_jdbc_url=NULL, version=version+1 WHERE profile_code='local-dev';"
MYSQL_PWD="$META_PASSWORD" "$MYSQL" --no-defaults --protocol=TCP --host=127.0.0.1 --port=3307 --user="$META_USER" "$META_DB" < <(printf '%s\n' "$profile_sql") >/dev/null 2>&1 || { echo 'Task Linux RuntimeProfile setup failed.' >&2; exit 8; }
cat >"$RUN_DIR/runtime.json" <<EOF
{
  "run_id":"$RUN_ID",
  "ports":{"mysql":3307,"hdfs_rpc":19000,"hive_metastore_thrift":19083,"platform":8091,"mall":8090,"generator":8092,"analytics_vite":5173,"mall_vite":5174},
  "schemas":{"meta":"$META_DB","metric":"$METRIC_DB","mall":"$MALL_DB","generator":"$GEN_DB"},
  "hdfs_root":"hdfs://127.0.0.1:19000/landing/$RUN_ID",
  "profile":"SINGLE_NODE / FLUME_RAW / Spark local[1]",
  "pipeline_metastore":"embedded Derby at task-owned PLATFORM_SPARK_METASTORE_DIR",
  "standalone_hms":"Thrift 127.0.0.1:19083; backend Derby at $HDFS_RUN/derby/metastore_db",
  "credentials":"generated in-process; not stored"
}
EOF
chmod 600 "$RUN_DIR/runtime.json" "$RUN_DIR/my.cnf"
printf 'Started isolated Linux single-node runtime. run_id=%s\n' "$RUN_ID"
printf 'Task run directory: %s\n' "$RUN_DIR"
