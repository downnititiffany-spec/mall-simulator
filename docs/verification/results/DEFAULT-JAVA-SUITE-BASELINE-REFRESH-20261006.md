# Unified default Java suite baseline refresh — 2026-10-06

## Outcome

The earlier default run (`dev003c_20261006_164319_275a62`) completed with zero failures/errors but exit 7 because its exact test totals exceeded the registered counts. After reviewing the full per-tree Maven summaries, the strict default baselines were updated from analytics-server 1195 to 1234 and generator 111 to 125; mall remained 14. The runner's strict matching behavior remains enabled; `-AllowCountDrift` was not used.

A fresh rerun passed all checks:

| Tree | Tests | Failures | Errors | Skipped | Baseline | Result |
|---|---:|---:|---:|---:|---:|---|
| analytics-server | 1234 | 0 | 0 | 2 | 1234 | MATCH |
| mall-simulator | 14 | 0 | 0 | 0 | 14 | MATCH |
| synthetic-data-generator | 125 | 0 | 0 | 0 | 125 | MATCH |
| Total | 1373 | 0 | 0 | 2 | 1373 | PASS |

## Fresh verification run

- RunId: `dev003c_20261006_165335_12bb44`
- Log directory: `C:\Users\ASUS\AppData\Local\Temp\v25tests-dev003c_20261006_165335_12bb44`
- Command: `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default -MavenCmd 'D:\apache-maven-3.9.14\bin\mvn.cmd' -JdkDefault 'D:\Develop\JAVA17' -MavenRepoLocal 'D:\maven_repository'`
- Environment: JDK 17, Maven 3.9.14, offline local repository.
- All three Maven trees exited 0; the unified runner printed `[PASS exit=0]`.

## Scope

This closes the default Java suite's count-baseline discrepancy for the current worktree only. It does not add excluded integration suites, validate a production database, or replace web, Spark, isolated-database, browser, or full product acceptance gates. Spark remains a separate 332-test suite.