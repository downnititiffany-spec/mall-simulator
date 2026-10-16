# Phase 4 live stack reconciliation — 2026-10-06

## Corrected RunId discovery

An invocation of `cloud-single-node.sh status` without its configured task root returned `No task run registered`. That was not the state of the host: the script defaulted to `/workspace/single-node`, while the already running stack uses `CLOUD_SINGLE_NODE_ROOT=/home/asus/graduationproject-runs`.

Repeating the read-only status command with that root reported RunId `cloud_261006_074130_535d3b` and all expected listeners: MySQL 3307; HDFS 19000/19010/19870/19864/19866; metastore 19083; mall/platform/generator 8090/8091/8092; Vite 5173/5174. HiveServer2 10015 was closed.

Process identity corroboration (all children of supervisor PID 500): PID 634 mysqld working under this RunId's `mysql-data`; PIDs 711/773 HDFS NameNode/DataNode; PID 1604 Hive Metastore; PIDs 1821/1822/1823 platform/mall/generator; PID 1825 Vite under this repository's `mall-frontend`. No process was started, stopped, or adopted.

## Read-only application checks

- `GET http://127.0.0.1:8091/api/v1/health`: HTTP 200, response identifies `analytics-server` and code `OK`.
- Unauthenticated GET to each protected pagination endpoint returned HTTP 401: admin users, AI audit history, AI call audit, quality results, and metric snapshots.
- `GRADUATION_TEST_ADMIN_PASSWORD` is not present in this task process; no credential value was read or displayed. Authenticated nonempty pagination remains unverified.
- The `scripts/cloud-single-node.sh preflight` default invocation safely refused before side effects because its default JDK path `/workspace/toolchains/jdk17/bin/java` is absent. Do not start a new stack over the existing one.
- 3306 was not probed or contacted. No database rows were read or written in these checks.

## Evidence boundary

This confirms the task-owned single-node stack is live and the anonymous authorization boundary returns 401. It does not prove authenticated/nonempty pagination, HiveServer2 health, or Phase 4 completion.