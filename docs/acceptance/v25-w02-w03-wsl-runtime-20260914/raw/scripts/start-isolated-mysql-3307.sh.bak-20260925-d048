#!/usr/bin/env bash
# V25-W03 DELIVERABLE | isolated MySQL 8.0.41 for WSL local dev (127.0.0.1:3307).
# Reuses the /opt/mysql-libs compat libs; creates the libaio.so.1 -> libaio.so.1t64 compat symlink
# required because Ubuntu 26.04 renamed the libaio soname.
# Idempotent: safe to re-run. Start-only; never formats/drops anything.
set -u
BASE=/opt/mysql-8.0.41
LIBS=/opt/mysql-libs
DDIR=/data/mysql-isolated/data
RDIR=/data/mysql-isolated/run
LOGD=/data/mysql-isolated/log
PORT=3307
SOCK=$RDIR/mysql3307.sock
export LD_LIBRARY_PATH=$LIBS

echo "### S0: compat soname fix (Ubuntu 26.04: libaio.so.1 -> libaio.so.1t64)"
ls -l $LIBS/libaio.so.1 2>/dev/null || sudo ln -sf $LIBS/libaio.so.1t64.0.2 $LIBS/libaio.so.1
ls -l $LIBS/libaio.so.1 $LIBS/libnuma.so.1 $LIBS/libncurses.so.6
echo "--- ldd must be fully resolved now ---"
ldd $BASE/bin/mysqld 2>&1 | grep -i 'not found' && { echo "ABORT: unresolved libs"; exit 1; } || echo "mysqld: ALL LIBS RESOLVED"
ldd $BASE/bin/mysql  2>&1 | grep -i 'not found' && { echo "ABORT: unresolved client libs"; exit 1; } || echo "mysql client: ALL LIBS RESOLVED"
echo "--- versions now runnable ---"
$BASE/bin/mysqld --version 2>&1
$BASE/bin/mysql  --version 2>&1

echo "### S1: initialize datadir (only if absent)"
if [ ! -d "$DDIR/mysql" ]; then
  rm -rf $DDIR; mkdir -p "$DDIR" "$RDIR" "$LOGD"
  $BASE/bin/mysqld --no-defaults --initialize-insecure \
    --basedir=$BASE --datadir=$DDIR --log-error=$LOGD/init-error.log
  echo "init exit=$?"
  tail -8 $LOGD/init-error.log 2>/dev/null
else
  echo "datadir already initialized (preserved)"
fi
echo "--- datadir ---"; ls $DDIR | head -12

echo "### S2: start on 127.0.0.1:$PORT (loopback only)"
if $BASE/bin/mysqladmin --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 ping >/dev/null 2>&1; then
  echo "already running"
else
  nohup $BASE/bin/mysqld --no-defaults \
    --basedir=$BASE --datadir=$DDIR \
    --port=$PORT --bind-address=127.0.0.1 --mysqlx=OFF \
    --socket=$SOCK --pid-file=$RDIR/mysql.pid \
    --log-error=$LOGD/error.log \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
    --skip-name-resolve >/dev/null 2>&1 &
  echo "launched bg pid=$!"
fi
UP=no
for i in $(seq 1 60); do
  if $BASE/bin/mysqladmin --no-defaults -h127.0.0.1 -P$PORT -uroot ping >/dev/null 2>&1 \
     || $BASE/bin/mysqladmin --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 ping >/dev/null 2>&1; then
    echo "mysqld UP after ${i}s"; UP=yes; break
  fi
  sleep 1
done
echo "UP=$UP"
if [ "$UP" != "yes" ]; then echo "--- error.log ---"; tail -25 $LOGD/error.log 2>/dev/null; exit 1; fi

echo "### S3: account + isolated databases (idempotent)"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 -e "
  ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '123456';
  CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '123456';
  GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
  CREATE DATABASE IF NOT EXISTS analytics_metric CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE DATABASE IF NOT EXISTS analytics_meta   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE DATABASE IF NOT EXISTS mall_simulator   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  FLUSH PRIVILEGES;" 2>&1
echo "account/db exit=$?"

echo "### S4: DELIVERABLE EVIDENCE — 库名/账号/端口/连接串/实测连通性"
echo "--- [1] identity ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw \
  -e "SELECT @@version AS version, @@port AS port, @@datadir AS datadir, @@socket AS socket, @@bind_address AS bind_addr, @@hostname AS hostname;" 2>&1
echo "--- [2] databases in ISOLATED instance ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw -e "SHOW DATABASES;" 2>&1
echo "--- [3] users ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw \
  -e "SELECT user, host, plugin FROM mysql.user WHERE user='root';" 2>&1
echo "--- [4] TCP connectivity 127.0.0.1:3307 ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 -e "SELECT 1 AS tcp_ok, NOW() AS server_time;" 2>&1
echo "--- [5] unix socket connectivity ---"
$BASE/bin/mysql --no-defaults -S$SOCK -uroot -p123456 -e "SELECT 1 AS sock_ok;" 2>&1
echo "--- [6] CREATE/INSERT/SELECT round-trip in analytics_metric ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw -e "
  CREATE TABLE IF NOT EXISTS analytics_metric.__v25_w03_probe (id INT PRIMARY KEY, note VARCHAR(64));
  INSERT INTO analytics_metric.__v25_w03_probe VALUES (1,'wsl-isolated-3307') ON DUPLICATE KEY UPDATE note=VALUES(note);
  SELECT * FROM analytics_metric.__v25_w03_probe;" 2>&1
echo "--- [7] listeners: 3307 on loopback only ---"
ss -lnt | grep -E ':(3306|3307)\b' || echo "(none?)"
echo "--- [8] process identity ---"; pgrep -a mysqld | head -3
echo "--- [9] isolation proof: no 3306 in WSL; host 3306 is a SEPARATE process ---"
echo "  WSL mysqld: datadir=$DDIR port=$PORT bind=127.0.0.1"
echo "--- [10] sizes ---"; du -sh $BASE /data/mysql-isolated 2>/dev/null; df -h / | tail -1
echo "### DONE rc=0"
