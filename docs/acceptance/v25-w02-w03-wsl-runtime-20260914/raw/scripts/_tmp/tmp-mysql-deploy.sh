#!/usr/bin/env bash
# V25-W03 | isolated MySQL 8.0.41 instance inside WSL Ubuntu (port 3307, 127.0.0.1 only)
# Read-only w.r.t. host MySQL. Never touches 3306. Never drops/truncates anything.
set -u
echo "### PHASE 0: preflight"
date; id; nproc; free -m | head -2; df -h / /data 2>/dev/null | head -4

BASE=/opt/mysql-8.0.41
DDIR=/data/mysql-isolated/data
RDIR=/data/mysql-isolated/run
LOGD=/data/mysql-isolated/log
PORT=3307
SOCK=$RDIR/mysql3307.sock
TARBALL=/mnt/d/Develop/dl/v25/mysql-8.0.41-linux.tar.xz

echo "### PHASE 1: dirs (all on WSL ext4, never /mnt/*)"
sudo mkdir -p /data/mysql-isolated/data /data/mysql-isolated/run /data/mysql-isolated/log /opt
sudo chown -R asus:asus /data/mysql-isolated
ls -ld /data /data/mysql-isolated /data/mysql-isolated/data

echo "### PHASE 2: extract tarball ($(stat -c%s "$TARBALL") bytes)"
if [ ! -x "$BASE/bin/mysqld" ]; then
  sudo tar -xJf "$TARBALL" -C /opt
  EXTRACTED=$(ls -d /opt/mysql-8.0.41-linux-glibc2.28-x86_64 2>/dev/null || true)
  echo "extracted dir: $EXTRACTED"
  if [ -n "$EXTRACTED" ] && [ "$EXTRACTED" != "$BASE" ]; then sudo mv "$EXTRACTED" "$BASE"; fi
  sudo chown -R asus:asus "$BASE"
else
  echo "already extracted, skipping"
fi
echo "--- layout ---"; ls "$BASE" | head -20
echo "--- version ---"; "$BASE/bin/mysqld" --version
echo "--- mysqld realpath ---"; realpath "$BASE/bin/mysqld"

echo "### PHASE 3: initialize datadir (insecure root, password set later)"
if [ ! -d "$DDIR/mysql" ]; then
  rm -rf "$DDIR"; mkdir -p "$DDIR"
  "$BASE/bin/mysqld" --no-defaults --initialize-insecure \
    --basedir="$BASE" --datadir="$DDIR" \
    --log-error="$LOGD/init-error.log" 2>&1 | tail -20
  echo "mysqld --initialize-insecure exit=$?"
else
  echo "datadir already initialized, skipping"
fi
echo "--- init-error.log tail ---"; tail -15 "$LOGD/init-error.log" 2>/dev/null || echo "(no init log)"
echo "--- datadir contents ---"; ls "$DDIR" | head -20

echo "### PHASE 4: start isolated instance on 127.0.0.1:$PORT (bind to loopback only)"
if ! ss -lnt 2>/dev/null | grep -q ":$PORT "; then
  nohup "$BASE/bin/mysqld" --no-defaults \
    --basedir="$BASE" --datadir="$DDIR" \
    --port=$PORT --bind-address=127.0.0.1 --mysqlx=OFF \
    --socket="$SOCK" --pid-file="$RDIR/mysql.pid" \
    --log-error="$LOGD/error.log" \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
    --skip-name-resolve \
    >/dev/null 2>&1 &
  echo "launched pid=$!"
else
  echo "port $PORT already listening, skipping launch"
fi
for i in $(seq 1 40); do
  if "$BASE/bin/mysqladmin" --no-defaults -h127.0.0.1 -P$PORT -uroot ping >/dev/null 2>&1; then
    echo "mysqld UP after ${i}s (root no-password ping ok)"; break
  fi
  sleep 1
done
echo "--- error.log tail ---"; tail -20 "$LOGD/error.log" 2>/dev/null || echo "(no error log)"
echo "--- port state ---"; ss -lntp 2>/dev/null | grep -E ":($PORT|3306) " || echo "grep done"

echo "### PHASE 5: set root password + create isolation accounts"
"$BASE/bin/mysql" --no-defaults -h127.0.0.1 -P$PORT -uroot --connect-expired-password -e "
  ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '123456';
  CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '123456';
  GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
  CREATE DATABASE IF NOT EXISTS analytics_metric       CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE DATABASE IF NOT EXISTS analytics_meta         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE DATABASE IF NOT EXISTS mall_simulator         CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  FLUSH PRIVILEGES;
" 2>&1
echo "ALTER/CREATE exit=$?"

echo "### PHASE 6: verification with password (read-only SELECT/SHOW)"
"$BASE/bin/mysql" --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw --default-character-set=utf8mb4 \
  -e "SELECT @@version AS version, @@port AS port, @@datadir AS datadir, @@socket AS socket, @@bind_address AS bind_addr;" 2>&1
echo "--- SHOW DATABASES ---"
"$BASE/bin/mysql" --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 --batch --raw --default-character-set=utf8mb4 \
  -e "SHOW DATABASES;" 2>&1
echo "--- TCP connectivity 127.0.0.1:3307 ---"
"$BASE/bin/mysql" --no-defaults -h127.0.0.1 -P$PORT -uroot -p123456 -e "SELECT 1 AS tcp_ok;" 2>&1
echo "--- socket connectivity ---"
"$BASE/bin/mysql" --no-defaults -S"$SOCK" -uroot -p123456 -e "SELECT 1 AS sock_ok, @@port AS p;" 2>&1
echo "--- listening sockets ---"; ss -lntp 2>/dev/null | grep -E ':(3306|3307) ' || echo "none matched"
echo "### PHASE 7: artifacts"
ls -l "$RDIR" "$LOGD"
du -sh "$BASE" /data/mysql-isolated 2>/dev/null
echo "### DONE rc=0"
