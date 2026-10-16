# RuntimeProfile HiveServer2 Current Source Retest - 2026-10-08

## Scope

Revalidated the current working-tree `RuntimeProfileServiceImpl.checkHive()` method against a task-scoped HiveServer2 using Apache Hive 3.1.3 JDBC. The test is method-level evidence only; it does not cover Spring HTTP, database-backed RuntimeProfile loading, real credential resolution, profile activation, or end-to-end timeout enforcement.

## Run and isolation

- Main isolated application stack remained on task RunId `cloud_261008_040652_a898e1`; its HDFS was reused read-only for the Hive test warehouse.
- Started a separate Hive run `hiveprobe_rt_20261008_153000` with embedded Derby catalog under its own run directory, Metastore `127.0.0.1:19084`, HiveServer2 `127.0.0.1:10015`, and HDFS warehouse `/warehouse/hiveprobe_rt_20261008_153000/hive-warehouse`.
- The `wsl-hive-thrift.sh` syntax check passed. The initial read-only `hdfs getconf` attempt used an unsupported `-conf` argument and made no changes; rerun with `HADOOP_CONF_DIR` confirmed `fs.defaultFS=hdfs://127.0.0.1:19000`. Hive startup preflight confirmed the isolated ports were free and the existing HDFS root readable.
- No MySQL connection was made; port 3306 was not probed or contacted. No existing HDFS or Derby data was formatted or deleted.

## Verification

Command:

```text
mvn -o -f analytics-server/pom.xml -pl platform-app -am "-Dtest=RuntimeProfileHiveServer2IT" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: `RuntimeProfileHiveServer2IT` **1/1 PASS**, 0 failures, 0 errors, 0 skipped; seven-module Maven reactor `BUILD SUCCESS`. The current source test used Hive JDBC 3.1.3 to execute `SELECT 1` through the RuntimeProfile Hive probe.

After the test, the task-specific Hive script stopped only this Hive run. Ports 19084 and 10015 were confirmed stopped. The run-scoped Derby directory and HDFS warehouse were confirmed retained. The main cloud RunId remained independently registered and its services were not stopped.

## Limits

This confirms only the current method-level JDBC probe against a real HiveServer2. It is not evidence for Spring API behavior, database-backed profile loading, credentials, profile switching, slow-connect timeout limits, shared Hive Metastore acceptance, or Phase 4/project acceptance.