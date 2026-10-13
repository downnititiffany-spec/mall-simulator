# Unified default Java suite retest — count drift — 2026-10-06

## Outcome

All selected Maven tests completed with zero failures and zero errors, but the unified `default` gate **FAILED (exit 7)** because actual test totals no longer match the registered baselines. The runner's strict count gate remains active; no drift override was used.

| Tree | Tests run | Failures | Errors | Skipped | Registered baseline | Gate |
|---|---:|---:|---:|---:|---:|---|
| analytics-server | 1234 | 0 | 0 | 2 | 1195 | DRIFT |
| mall-simulator | 14 | 0 | 0 | 0 | 14 | MATCH |
| synthetic-data-generator | 125 | 0 | 0 | 0 | 111 | DRIFT |
| Total | 1373 | 0 | 0 | 2 | 1320 | FAIL: count drift |

The six analytics-server module totals were `118+389+209+141+175+202`. Maven exited 0 for each tree; the gate rejected analytics-server and generator only on counts.

## Run identity

- RunId: `dev003c_20261006_164319_275a62`
- Log directory: `C:\Users\ASUS\AppData\Local\Temp\v25tests-dev003c_20261006_164319_275a62`
- Command: `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default -MavenCmd 'D:\apache-maven-3.9.14\bin\mvn.cmd' -JdkDefault 'D:\Develop\JAVA17' -MavenRepoLocal 'D:\maven_repository'`
- Environment: JDK 17, Maven 3.9.14, offline local repository.

## Follow-up

The runner comments say baseline/count drift may only be accepted after total-control approval. Keep this result as a failed gate and preserve the registered baseline until that approval exists. Do not use `-AllowCountDrift` as a substitute for the decision. This run did not access a database or start a service.