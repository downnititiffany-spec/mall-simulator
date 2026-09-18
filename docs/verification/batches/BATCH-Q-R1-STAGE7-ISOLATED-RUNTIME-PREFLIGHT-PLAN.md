# BATCH-Q-R1-STAGE7-ISOLATED-RUNTIME-PREFLIGHT — Verification Plan

## 1. Purpose

Rerun the Stage 7 MySQL isolation preflight after the historical Batch Q `BLOCKED_ENV`, using the latest code that contains analytics dual-database provisioning, Flyway preparation, and the formally enrolled metric write MySQL ITs.

This rerun proves the governed 3307 isolation lane only. It does not prove HTTP ingestion → pipeline, Spark/Hive/HDFS, browser E2E, or a real LLM provider.

## 2. Exact tested baseline

- Branch: `feature/v3-development`
- Exact tested SHA: `9b2f18faf00872f364e26a9980b767854e96f9fa`
- RunId: `stage7q1_20260918_152245`
- Historical predecessor: `BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT` = `BLOCKED_ENV`

The later baseline-registration change `a496434` is post-verification administration and is not retroactively treated as the tested SHA.

## 3. Safety boundary

1. Never write to MySQL 3306.
2. Isolation instance is `127.0.0.1:3307` only.
3. Databases/accounts are runId-scoped.
4. Database/account creation goes only through `scripts/it-prepare-isolation.ps1`.
5. Root/admin password is process-only and never printed or committed.
6. Analytics meta/metric passwords are process-only; mall/generator credentials may be loaded from their ignored `credref-*.properties` files without printing values.
7. No manual CREATE/DROP/GRANT outside the governed preparation script.
8. A test failure after preparation is FAIL, not `BLOCKED_ENV`.

## 4. Registered target counts

- mall: **30**
- generator: **19**
- analytics: **11** = `IsolationGuardMySqlIT` 6 + `MetricAdsMySqlIT` 2 + `MetricPublisherMySqlIT` 3
- total: **60**
- analytics schema gate: `AnalyticsIsolationFlywayIT` **1/1** before write IT execution

## 5. Execution shape

Because the connected MCP intentionally filters secret-looking process environment variables, the same-run evidence is collected as two governed invocations against the same SHA, runId, 3307 instance, and run-scoped databases:

1. interactive PowerShell process that owns the analytics secrets:
   - governed preparation with `-IncludeAnalytics -Confirm -AllowRootOnIsolated`;
   - `run-isolated-tests.ps1 -Module analytics -IncludeAnalyticsWriteIts -Confirm`;
2. controller/coding-tool process:
   - loads mall/generator passwords from ignored credref files without printing them;
   - `run-isolated-tests.ps1 -Module both -Confirm`.

This is accepted as one Q-R1 evidence set because the lower-level governed runner, guard logic, exact SHA, runId, instance identity and run-scoped objects are identical. A later source registration change updates the top-level unified entrypoint so future `run-tests.ps1 -Suite isolated` automatically includes the analytics write ITs.

## 6. PASS criteria

PASS only if all are true:

- governed preparation succeeds on 3307 and no 3306 target is used;
- analytics/meta/metric live probes identify port 3307 and the same isolated instance;
- `AnalyticsIsolationFlywayIT` = 1/1, F/E/S = 0;
- `IsolationGuardMySqlIT` = 6/6;
- `MetricAdsMySqlIT` = 2/2;
- `MetricPublisherMySqlIT` = 3/3;
- mall = 30/30;
- generator = 19/19;
- total = 60/60;
- all governed runner invocations exit 0;
- tracked workspace remains clean after execution.

