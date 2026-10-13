# Phase 4 RuntimeProfile Spark executable availability smoke — 2026-10-07

## Scope

Verified that the installed WSL Spark 3.5.1 executable can launch its version command under the installed JDK 17. This is the same executable argument (`spark-submit --version`) used by `LocalProcessSparkSubmitter.healthCheck()`.

## Command and result

Environment used for this process only:

- `JAVA_HOME=/opt/jdk-17.0.12`
- `SPARK_HOME=/opt/spark-3.5.1-bin-hadoop3`
- Executable: `/opt/spark-3.5.1-bin-hadoop3/bin/spark-submit`
- `timeout 30 .../spark-submit --version`: exit **0**
- Reported Spark **3.5.1**, Scala 2.12.18, OpenJDK 17.0.12

The command did not submit a Spark job, start a daemon, access a database, or contact HDFS. Spark emitted a hostname-to-loopback warning and selected `10.255.255.254`; the version command still completed successfully.

## Evidence boundary

This proves the locally installed candidate executable can run its version probe. It does not prove that any persisted RuntimeProfile points to this exact path, that RuntimeProfileService/HTTP activation health succeeds, or that Spark can submit a real warehouse job. The isolated service stack is not running; current administrator credential is unavailable to this task process. The unified start script preflight remains safely blocked because its required HDFS namespace/config pair is absent at the script's configured default paths. No service was started or stopped; no database or port 3306 was accessed. Stage 4 remains incomplete.
