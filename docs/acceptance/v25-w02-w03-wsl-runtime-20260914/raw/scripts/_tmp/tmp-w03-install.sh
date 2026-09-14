#!/usr/bin/env bash
# V25-W03 | install Hadoop/Spark/Hive/JDK17 + Flume, explicit JAVA_HOME, NO .bashrc dependency.
# Does NOT format NameNode. Does NOT start HDFS/HMS/Spark/HiveServer2. Does NOT touch /opt/jdk-8u351, /opt/spark-3.3.2-*.
set -u
DL=/mnt/d/Develop/dl/v25
JDK17=/opt/jdk-17.0.12
J8=/opt/jdk-8u351
export JAVA_HOME_8=$J8
echo "### PHASE A: versions locked (see README compat matrix)"
date; free -m | head -2; df -h / | tail -1

echo "### PHASE B: JDK17 install (host-matching Temurin 17.0.12)"
if [ ! -x "$JDK17/bin/java" ]; then
  sudo mkdir -p /opt
  sudo tar -xzf "$DL/temurin17.dl" -C /opt
  EX=$(ls -d /opt/jdk-17.0.12* 2>/dev/null | head -1)
  echo "extracted: $EX"
  if [ -n "$EX" ] && [ "$EX" != "$JDK17" ]; then sudo mv "$EX" "$JDK17"; fi
  sudo chown -R asus:asus "$JDK17"
fi
echo "--- JDK17 explicit path probe ---"
JAVA_HOME=$JDK17 $JDK17/bin/java -version 2>&1
echo "--- JDK8 explicit path probe (pre-existing, untouched) ---"
JAVA_HOME=$J8 $J8/bin/java -version 2>&1

echo "### PHASE C: Hadoop 3.3.4 install"
if [ ! -x /opt/hadoop-3.3.4/bin/hadoop ]; then
  sudo tar -xzf "$DL/hadoop-3.3.4.dl" -C /opt
  sudo chown -R asus:asus /opt/hadoop-3.3.4
fi
echo "--- hadoop version (JAVA_HOME=JDK8 explicit) ---"
JAVA_HOME=$J8 HADOOP_HOME=/opt/hadoop-3.3.4 /opt/hadoop-3.3.4/bin/hadoop version 2>&1 | head -8

echo "### PHASE D: Spark 3.5.1 (for Hadoop 3.3) install"
if [ ! -x /opt/spark-3.5.1-bin-hadoop3/bin/spark-submit ]; then
  sudo tar -xzf "$DL/spark-3.5.1.dl" -C /opt
  sudo chown -R asus:asus /opt/spark-3.5.1-bin-hadoop3
fi
echo "--- RELEASE ---"; head -3 /opt/spark-3.5.1-bin-hadoop3/RELEASE
echo "--- bundled hive metastore client jars ---"
ls /opt/spark-3.5.1-bin-hadoop3/jars/ | grep -iE 'hive-metastore|hive-exec|hive-common|datanucleus' | head -12
echo "--- bundled scala ---"; ls /opt/spark-3.5.1-bin-hadoop3/jars/ | grep -i 'scala-library' | head -3

echo "### PHASE E: Hive 3.1.3 install (chosen: NOT 4.x — see compat matrix)"
if [ ! -x /opt/hive-3.1.3/bin/hive ]; then
  sudo tar -xzf "$DL/hive-3.1.3.dl" -C /opt
  EX=$(ls -d /opt/apache-hive-3.1.3-bin 2>/dev/null | head -1)
  if [ -n "$EX" ]; then sudo mv "$EX" /opt/hive-3.1.3; fi
  sudo chown -R asus:asus /opt/hive-3.1.3
fi
echo "--- hive version (JAVA_HOME=JDK8 explicit, HIVE_HOME explicit) ---"
JAVA_HOME=$J8 HIVE_HOME=/opt/hive-3.1.3 HADOOP_HOME=/opt/hadoop-3.3.4 \
  /opt/hive-3.1.3/bin/hive --version 2>&1 | head -8
echo "--- hive lib counts ---"; ls /opt/hive-3.1.3/lib | wc -l
echo "--- guava versions in hive lib ---"; ls /opt/hive-3.1.3/lib | grep -i guava

echo "### PHASE F: MySQL Connector/J for HMS (download)"
CJ=/opt/hive-3.1.3/lib/mysql-connector-j-8.0.33.jar
if [ ! -f "$CJ" ]; then
  curl -sSL --fail -o "$CJ" https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar \
    && echo "downloaded $(stat -c%s "$CJ") bytes" || echo "CONNECTOR_DOWNLOAD_FAILED"
fi
ls -l /opt/hive-3.1.3/lib/mysql-connector-j-*.jar 2>/dev/null

echo "### PHASE G: data dirs for hdfs/metastore/spark-scratch (ext4 only)"
sudo mkdir -p /data/hdfs/name /data/hdfs/data /data/metastore /data/spark-scratch
sudo chown -R asus:asus /data/hdfs /data/metastore /data/spark-scratch
ls -ld /data/hdfs/name /data/hdfs/data /data/metastore /data/spark-scratch

echo "### PHASE H: apt installs (nodejs/npm, flume); project-scope dev deps"
sudo apt-get install -y -q nodejs npm 2>&1 | tail -6
echo "--- node/npm ---"; node --version 2>&1; npm --version 2>&1
echo "--- pnpm via npm ---"; sudo npm install -g pnpm 2>&1 | tail -4; pnpm --version 2>&1
echo "--- flume available? ---"
apt-cache policy flume 2>&1 | head -5
sudo apt-get install -y -q flume 2>&1 | tail -6
command -v flume-ng && flume-ng version 2>&1 | head -4 || echo "FLUME_NOT_AVAILABLE_VIA_APT"

echo "### PHASE I: read-only probes (NO service start, NO NameNode format)"
echo "--- 1) hadoop version ---"
JAVA_HOME=$J8 /opt/hadoop-3.3.4/bin/hadoop version 2>&1 | head -6
echo "--- 2) hdfs / yarn executability ---"
JAVA_HOME=$J8 /opt/hadoop-3.3.4/bin/hdfs version 2>&1 | head -3
JAVA_HOME=$J8 /opt/hadoop-3.3.4/bin/yarn version 2>&1 | head -3
echo "--- 3) spark-submit --version (JAVA_HOME=JDK17 explicit) ---"
JAVA_HOME=$JDK17 SPARK_HOME=/opt/spark-3.5.1-bin-hadoop3 /opt/spark-3.5.1-bin-hadoop3/bin/spark-submit --version 2>&1 | head -16
echo "--- 3b) spark-submit with JDK8 ---"
JAVA_HOME=$J8 SPARK_HOME=/opt/spark-3.5.1-bin-hadoop3 /opt/spark-3.5.1-bin-hadoop3/bin/spark-submit --version 2>&1 | head -6
echo "--- 4) hive --version ---"
JAVA_HOME=$J8 HIVE_HOME=/opt/hive-3.1.3 /opt/hive-3.1.3/bin/hive --version 2>&1 | head -5
echo "--- 5) two java paths ---"
JAVA_HOME=$JDK17 $JDK17/bin/java -version 2>&1
JAVA_HOME=$J8 $J8/bin/java -version 2>&1
echo "--- 6) scala warning check (spark 2.12.18 vs project 2.12.19) ---"
ls /opt/spark-3.5.1-bin-hadoop3/jars/ | grep -i 'scala-library\|scala-compiler\|scala-reflect'
echo "--- 7) previously-present installs untouched? ---"
ls -d /opt/jdk-8u351 /opt/spark-3.3.2-bin-hadoop3 2>&1
echo "--- 8) no service started check ---"
ss -lnt 2>/dev/null | grep -E ':(8020|9870|9083|10000|4040|8088) ' || echo "no HDFS/HMS/HiveServer2 ports listening (as required)"
pgrep -a 'NameNode|DataNode|metastore|HiveServer2|spark' 2>/dev/null || echo "no hadoop/hive/spark process running (as required)"
echo "### PHASE J: sizes"
du -sh /opt/hadoop-3.3.4 /opt/spark-3.5.1-bin-hadoop3 /opt/hive-3.1.3 /opt/jdk-17.0.12 2>/dev/null
df -h / | tail -1
echo "### DONE rc=0"
