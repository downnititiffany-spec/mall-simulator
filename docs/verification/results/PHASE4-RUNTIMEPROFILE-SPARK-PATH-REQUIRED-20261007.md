# Phase 4 RuntimeProfile Spark Path Requirement — 2026-10-07

## Change

RuntimeProfile activation preflight (`RuntimeProfileServiceImpl.checkSpark`) previously substituted a hard-coded Windows `spark-submit.cmd` path when a LOCAL/SINGLE_NODE profile left `spark_submit_path` blank. The actual `JobSubmitterFactory` already rejected that omission under §8.4. Preflight now fails closed with a configuration-specific detail and does not instantiate or start a process. Configured paths and REMOTE_CLUSTER SSH health checks retain their existing behavior.

## Verification

- Targeted `RuntimeProfileProbeRedactionTest`: 7/7 passed, 0 failures/errors/skips.
- Full `connection-ingestion` reactor command: `mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am test`.
- Surefire XML: `connection-ingestion` 50 suites / 403 tests, 0 failures, 0 errors, 2 skipped; `platform-common` 11 suites / 119 tests, 0 failures/errors/skips. Total: 522 tests, 0 failures/errors, 2 skips; Maven `BUILD SUCCESS`.
- The two skips are existing POSIX symbolic-link cases not run on Windows.
- `git diff --check` exit code 0; only existing LF-to-CRLF warnings were emitted.

## Evidence boundary

This is source and offline test evidence. No platform service, Spark process, database, or RuntimeProfile activation was run. It does not prove the configured Spark executable works in the current WSL runtime or close the stage 4 live health-probe gate. No database or port 3306 was accessed.
