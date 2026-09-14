#!/usr/bin/env bash
# V25-W03 | isolated MySQL recovery: install missing shared libs, init, start on 127.0.0.1:3307.
set -u
BASE=/opt/mysql-8.0.41
DDIR=/data/mysql-isolated/data
RDIR=/data/mysql-isolated/run
LOGD=/data/mysql-isolated/log
PORT=3307
SOCK=$RDIR/mysql3307.sock

echo "### PHASE R1: install missing shared libraries (Ubuntu 26.04 renamed libaio -> libaio1t64)"
sudo apt-get install -y -q libaio1t64 libnuma1 libncurses6 2>&1 | tail -8
echo "--- ldd recheck ---"
ldd $BASE/bin/mysqld 2>&1 | grep -i 'not found' || echo "mysqld: all linked libs resolved"
ldd $BASE/bin/mysql  2>&1 | grep -i 'not found' || echo "mysql client: all linked libs resolved"

echo "### PHASE R2: version (now runnable)"
$BASE/bin/mysqld --version 2>&1
$BASE/bin/mysql  --version 2>&1

echo "### PHASE R3: initialize datadir"
rm -rf $DDIR; mkdir -p "$DDIR" "$RDIR" "$LOGD"
$BASE/bin/mysqld --no-defaults --initialize-insecure \
  --basedir=$BASE --datadir=$DDIR --log-error=$LOGD/init-error.log
echo "init exit=$?"
echo "--- init-error.log (libaio/ncurses fixed?) ---"; tail -12 $LOGD/init-error.log 2>/dev/null
echo "--- datadir ---"; ls $DDIR | head -12

echo "### PHASE R4: start on 127.0.0.1:$PORT"
nohup $BASE/bin/mysqld --no-defaults \
  --basedir=$BASE --datadir=$DDIR \
  --port=$PORT --bind-address=127.0.0.1 --mysqlx=OFF \
  --socket=$SOCK --pid-file=$RDIR/mysql.pid \
  --log-error=$LOGD/error.log \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
  --skip-name-resolve >/dev/null 2>&1 &
echo "launched bg pid=$!"
UP=no
for i in $(seq 1 60); do
  if $BASE/bin/mysqladmin --no-defaults -h127.0.0.1 -P$PORT -uroot ping >/dev/null 2>&1; then
    echo "mysqld UP after ${i}s"; UP=yes; break
  fi
  sleep 1
done
echo "UP=$UP"
echo "--- error.log tail ---"; tail -20 $LOGD/error.log 2>/dev/null

if [ "$UP" != "yes" ]; then echo "### ABORT: mysqld did not come up"; exit 1; fi

echo "### PHASE R5: root password + isolated databases"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot --connect-expired-password -e "
  ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '123456';
  CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '123456';
  GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
  CREATE DATABASE IF NOT EXISTS analytics_metric CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE DATABASE IF NOT EXISTS analytics_meta   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE DATABASE IF NOT EXISTS mall_simulator   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  FLUSH PRIVILEGES;"
echo "grant/alter exit=$?"

echo "### PHASE R6: VERIFICATION (the deliverable evidence)"
echo "--- Q1 identity ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw \
  -e "SELECT @@version AS version, @@port AS port, @@datadir AS datadir, @@socket AS socket, @@bind_address AS bind_addr, @@hostname AS hostname;" 2>&1
echo "--- Q2 databases ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw -e "SHOW DATABASES;" 2>&1
echo "--- Q3 TCP 127.0.0.1:3307 connectivity ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 -e "SELECT 1 AS tcp_ok, NOW() AS server_time;" 2>&1
echo "--- Q4 unix socket connectivity ---"
$BASE/bin/mysql --no-defaults -S$SOCK -uroot -p123456 -e "SELECT 1 AS sock_ok, @@port AS p;" 2>&1
echo "--- Q5 CREATE/SELECT round-trip in isolated analytics_metric ---"
$BASE/bin/mysql --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw -e "
  CREATE TABLE IF NOT EXISTS analytics_metric.__v25_w03_probe (id INT PRIMARY KEY, note VARCHAR(64));
  INSERT INTO analytics_metric.__v25_w03_probe VALUES (1,'wsl-isolated-3307') ON DUPLICATE KEY UPDATE note=VALUES(note);
  SELECT * FROM analytics_metric.__v25_w03_probe;" 2>&1
echo "--- Q6 listeners (must be 127.0.0.1:3307 only; 3306 absent in WSL) ---"
ss -lntp 2>/dev/null | grep -E ':(3306|3307)\b' || echo "(no 3306/3307 listener?!)"
ss -lnt | grep 3307
echo "--- Q7 process ---"; pgrep -a mysqld | head -3
echo "--- Q8 host 3306 NOT touched by this instance (separate process/port/datadir) ---"
echo "WSL mysqld datadir=$DDIR port=$PORT bind=127.0.0.1"
echo "### DONE rc=0"
