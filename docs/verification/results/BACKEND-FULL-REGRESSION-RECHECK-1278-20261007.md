# Backend Full Regression Recheck — 2026-10-07

## Result

The current `analytics-server` working tree passed the full offline Maven reactor on Windows with JDK 17.0.12 and Maven 3.9.14.

Command:

```text
mvn -o -f analytics-server/pom.xml test
```

Result: `BUILD SUCCESS`; 168 test suites, 1,278 tests, 0 failures, 0 errors, and 2 skipped.

| Module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| platform-common | 11 | 119 | 0 | 0 | 0 |
| connection-ingestion | 50 | 402 | 0 | 0 | 2 |
| warehouse-pipeline | 17 | 209 | 0 | 0 | 0 |
| metric-analysis | 23 | 152 | 0 | 0 | 0 |
| ai-decision | 22 | 183 | 0 | 0 | 0 |
| platform-app | 45 | 213 | 0 | 0 | 0 |
| **Total** | **168** | **1,278** | **0** | **0** | **2** |

The skipped cases are `SampleRefPolicyTest.symbolicLinkIsRejected` and `SourceProfileFileSymlinkTest.rejectsSymlinkedProfile`; both POSIX symbolic-link tests were not run on Windows.

`git diff --check` completed with exit code 0; Git emitted existing LF-to-CRLF conversion warnings for modified files.

## Evidence boundary

This run covers the complete `analytics-server` Maven reactor unit/component-test suite for the current working tree. It does not cover mall/generator/Spark/Web suites, live services, database integration, browser-authenticated pagination, actual RuntimeProfile switching, or final product acceptance. No service was started and no database was accessed.
