# Spark local full suite retest — 2026-10-06

## Result

PASS: the unified Spark suite completed 332 tests across 42 suites; 332 succeeded, 0 failed, 0 canceled, 0 ignored, 0 pending, and 0 suites aborted. Maven exited 0 and reported BUILD SUCCESS. The runner confirmed the ScalaTest `TestSuite.txt` was freshly written during this run and its test count matched the configured baseline of 332.

## Run identity and environment

- RunId: `dev003c_20261006_163358_db4e93`
- Log directory: `C:\Users\ASUS\AppData\Local\Temp\v25tests-dev003c_20261006_163358_db4e93`
- Unified command: `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark -MavenCmd 'D:\apache-maven-3.9.14\bin\mvn.cmd' -JdkSpark 'D:\Develop\JDK1.8' -JdkDefault 'D:\Develop\JAVA17' -MavenRepoLocal 'D:\maven_repository'`
- Runtime: JDK 1.8.0_202; Spark local[1] and in-memory catalog as configured by the suite.
- Fresh `TestSuite.txt`: mtime 2026-10-06 16:36:49 +08:00; 39,643 bytes; SHA-256 prefix `CBE578B988DB20B3`.
- Surefire `Tests run: 0` was not treated as success evidence; the ScalaTest aggregate supplied the 332-test result.

## Evidence boundary

This establishes only that the current Spark source passed the repository's local[1]/in-memory suite. It does not establish a production Spark cluster, Hive/Thrift metastore, database integration, browser workflow, or full product acceptance. The unified summary also excludes `SourceRegistryMigrationMySqlIT`, `SparkStageExecutorSmokeIT`, and `AnalysisGoldenMySqlIT` under existing scope decisions. No database or cluster was contacted during this run.

## Artifacts

- Runner logs: `C:\Users\ASUS\AppData\Local\Temp\v25tests-dev003c_20261006_163358_db4e93`
- ScalaTest report: `spark-jobs/target/surefire-reports/TestSuite.txt`