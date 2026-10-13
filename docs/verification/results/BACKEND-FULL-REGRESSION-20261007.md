# Backend Full Regression — 2026-10-07

## Result

The current `analytics-server` working tree passed its full offline Maven reactor on Windows with JDK 17.0.12 and Maven 3.9.14.

Command:

```text
mvn -o -f analytics-server/pom.xml test
```

Result: `BUILD SUCCESS`; 167 test suites, 1,275 tests, 0 failures, 0 errors, and 2 skipped.

| Module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| platform-common | 11 | 119 | 0 | 0 | 0 |
| connection-ingestion | 50 | 402 | 0 | 0 | 2 |
| warehouse-pipeline | 17 | 209 | 0 | 0 | 0 |
| metric-analysis | 23 | 150 | 0 | 0 | 0 |
| ai-decision | 22 | 183 | 0 | 0 | 0 |
| platform-app | 44 | 212 | 0 | 0 | 0 |
| **Total** | **167** | **1,275** | **0** | **0** | **2** |

The two skipped cases exercise symbolic-link rejection (`SampleRefPolicyTest.symbolicLinkIsRejected` and `SourceProfileFileSymlinkTest.rejectsSymlinkedProfile`); this Windows run did not execute those POSIX-dependent cases.

`git diff --check` completed with exit code 0. Git emitted only existing LF-to-CRLF conversion warnings for modified files.

## Evidence boundary

This is a full backend unit/component-test regression for the current working tree. It does not cover the mall simulator, generator, Spark suite, Web tests/build, real MySQL/Hive/HDFS, browser-authenticated pagination, RuntimeProfile switching, or final product acceptance. No service was started and no database was accessed; port 3306 was not contacted.

The shared WSL preflight was also run read-only and refused before creating task state because `/workspace/toolchains/jdk17/bin/java` is absent. It is therefore not evidence of runtime deployment readiness.
