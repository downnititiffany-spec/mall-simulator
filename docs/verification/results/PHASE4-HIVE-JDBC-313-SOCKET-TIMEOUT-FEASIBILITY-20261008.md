# Hive JDBC 3.1.3 Thrift timeout feasibility

Date: 2026-10-08

## Finding

The platform currently packages Apache `hive-jdbc:3.1.3` at runtime. Its upstream `rel/release-3.1.3` `HiveConnection` calls `setupLoginTimeout()` during construction, and that method sets the Thrift timeout from `DriverManager.getLoginTimeout()`. The same source branch contains no `socketTimeout` parameter (`socketTimeout` is absent from `HiveConnection`). Therefore adding a per-connection `socketTimeout` URL option is not a supported fix for the current driver.

ASF HIVE-12371 records the JDBC timeout connection parameter and gives fix version `4.0.0-alpha-1`. This establishes that a newer client has a separate path worth evaluating, but does not prove that a Hive 4 client is compatible with this project's HiveServer2 or that all transport modes honor the parameter.

## Next validation needed

Keep the current Hive 3.1.3 behavior and its documented JVM-global timeout limitation until a replacement is verified. A safe driver change needs an isolated compatibility check against the project's actual HiveServer2, including binary Thrift transport, successful `SELECT 1`, connect timeout, query/read timeout, credential handling, and dependency/security review. Alternatively, evaluate a separate-process probe. Do not mark the global timeout concern resolved based only on parameter availability in a newer release.

## Evidence

- Apache Hive 3.1.3 source: https://github.com/apache/hive/blob/rel/release-3.1.3/jdbc/src/java/org/apache/hive/jdbc/HiveConnection.java
- ASF HIVE-12371: https://issues.apache.org/jira/browse/HIVE-12371
- Existing current-source HiveServer2 `SELECT 1` result: `PHASE4-RUNTIMEPROFILE-HIVESERVER2-CURRENT-SOURCE-20261008.md` (method-level only).

No code, driver dependency, or running service was changed in this investigation.
