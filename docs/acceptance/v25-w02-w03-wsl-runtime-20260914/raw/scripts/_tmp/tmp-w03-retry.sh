#!/usr/bin/env bash
# V25-W03 retry | JDK17 + Hadoop 3.3.4 install (tarballs had not finished downloading during first pass),
# then all read-only probes with explicit JAVA_HOME. No service start. No NameNode format.
set -u
DL=/mnt/d/Develop/dl/v25
JDK17=/opt/jdk-17.0.12
J8=/opt/jdk-8u351

echo "### PHASE A: JDK17 17.0.12 (Temurin) — retry"
if [ ! -x "$JDK17/bin/java" ]; then
  sudo tar -xzf "$DL/temurin17.dl" -C /opt
  EX=$(ls -d /opt/jdk-17.0.12* 2>/dev/null | head -1); echo "extracted: $EX"
  if [ -n "$EX" ] && [ "$EX" != "$JDK17" ]; then sudo mv "$EX" "$JDK17"; fi
  sudo chown -R asus:asus "$JDK17"
fi
echo "--- java17 (explicit JAVA_HOME) ---"; JAVA_HOME=$JDK17 $JDK17/bin/java -version 2>&1
echo "--- javac17 ---"; JAVA_HOME=$JDK17 $JDK17/bin/javac -version 2>&1

echo "### PHASE B: Hadoop 3.3.4 — retry"
if [ ! -x /opt/hadoop-3.3.4/bin/hadoop ]; then
  sudo tar -xzf "$DL/hadoop-3.3.4.dl" -C /opt
  sudo chown -R asus:asus /opt/hadoop-3.3.4
fi
echo "--- hadoop version ---"
JAVA_HOME=$J8 HADOOP_HOME=/opt/hadoop-3.3.4 /opt/hadoop-3.3.4/bin/hadoop version 2>&1 | head -8

echo "### PHASE C: READ-ONLY PROBES (no start, no format)"
echo "--- 1) hadoop version ---"
JAVA_HOME=$J8 /opt/hadoop-3.3.4/bin/hadoop version 2>&1 | head -6
echo "--- 2) hdfs executability ---"
JAVA_HOME=$J8 /opt/hadoop-3.3.4/bin/hdfs version 2>&1 | head -4
echo "--- 2b) yarn executability ---"
JAVA_HOME=$J8 /opt/hadoop-3.3.4/bin/yarn version 2>&1 | head -4
echo "--- 3) spark-submit --version (JAVA_HOME=JDK17) ---"
JAVA_HOME=$JDK17 SPARK_HOME=/opt/spark-3.5.1-bin-hadoop3 /opt/spark-3.5.1-bin-hadoop3/bin/spark-submit --version 2>&1 | head -18
echo "--- 3b) spark-submit --version (JAVA_HOME=JDK8) ---"
JAVA_HOME=$J8 SPARK_HOME=/opt/spark-3.5.1-bin-hadoop3 /opt/spark-3.5.1-bin-hadoop3/bin/spark-submit --version 2>&1 | head -8
echo "--- 4) hive --version (HADOOP_HOME now present) ---"
JAVA_HOME=$J8 HIVE_HOME=/opt/hive-3.1.3 HADOOP_HOME=/opt/hadoop-3.3.4 \
  /opt/hive-3.1.3/bin/hive --version 2>&1 | head -8
echo "--- 5) two java -version ---"
echo "[JDK17]"; JAVA_HOME=$JDK17 $JDK17/bin/java -version 2>&1
echo "[JDK8 ]"; JAVA_HOME=$J8 $J8/bin/java -version 2>&1
echo "--- 6) T3 dual-track: default (no JAVA_HOME, non-interactive) ---"
java -version 2>&1 | head -2
echo "  ^ note: T2 trap — .bashrc does not apply in non-interactive shell"
echo "--- 7) T1 trap re-proof: /mnt/d hadoop vs WSL-native hadoop ---"
echo "command -v hadoop -> $(command -v hadoop 2>/dev/null || echo '(none in this PATH)')"
echo "native -> $(ls -d /opt/hadoop-3.3.4 2>/dev/null)"
echo "--- 8) scala jars in spark 3.5.1 ---"
ls /opt/spark-3.5.1-bin-hadoop3/jars/ | grep -iE 'scala-(library|compiler|reflect)'
echo "--- 9) pre-existing installs preserved ---"
ls -d /opt/jdk-8u351 /opt/spark-3.3.2-bin-hadoop3 /opt/hive-3.1.3 /opt/hadoop-3.3.4 "$JDK17" 2>&1
echo "--- 10) .bashrc lines 123-126 untouched ---"
sed -n '123,126p' $HOME/.bashrc
echo "--- 11) NO service started (required) ---"
ss -lnt 2>/dev/null | grep -E ':(8020|9870|9864|9083|10000|10002|4040|8088|8042|7077|9000) ' || echo "no HDFS/YARN/HMS/HiveServer2 port listening (as required)"
pgrep -a 'NameNode|DataNode|ResourceManager|NodeManager|metastore|HiveServer2|org.apache.spark' 2>/dev/null || echo "no hadoop/hive/spark process (as required)"
echo "--- 12) sizes ---"
du -sh /opt/hadoop-3.3.4 /opt/spark-3.5.1-bin-hadoop3 /opt/hive-3.1.3 "$JDK17" 2>/dev/null
df -h / | tail -1; free -m | head -2
echo "### DONE rc=0"
