# RuntimeProfile checkSpark Current-Source Method Test - 2026-10-08

## Scope

Added an opt-in `RuntimeProfileSparkHealthCheckIT` that invokes the current private `RuntimeProfileServiceImpl.checkSpark()` method for a `SINGLE_NODE` profile configured with the installed Spark 3.5.1 CLI. The probe uses the service's normal `LocalProcessSparkSubmitter.healthCheck()` path and asserts the returned status does not expose the configured executable path.

## Verification

- Windows JDK 17.0.12, Maven 3.9.14, offline seven-module reactor:

```text
mvn -o -f analytics-server/pom.xml -pl platform-app -am \
  -Druntime.profile.sparkSubmitPath=D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd \
  -Dtest=RuntimeProfileSparkHealthCheckIT,RuntimeProfileProbeRedactionTest,RuntimeProfileHiveProbeRedactionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- Fresh Surefire XML: `RuntimeProfileProbeRedactionTest` 7/7, `RuntimeProfileHiveProbeRedactionTest` 3/3, `RuntimeProfileSparkHealthCheckIT` 1/1; **11/11 passed**, 0 failure/error/skipped; reactor `BUILD SUCCESS`.
- Direct CLI confirmation printed Spark 3.5.1, Scala 2.12.18, Java 17.0.12.

## Limits

The successful probe used the Windows Spark 3.5.1 installation. A WSL Maven attempt could not resolve the uncached Spring Boot 3.2.5 parent POM because Maven Central reset the connection. This result does not establish the WSL Spark path, a database-backed RuntimeProfile, Spring HTTP behavior, profile activation, or a submitted Spark job. It did not connect to any database or modify Spark/Hive data. Phase 4 remains partial.
