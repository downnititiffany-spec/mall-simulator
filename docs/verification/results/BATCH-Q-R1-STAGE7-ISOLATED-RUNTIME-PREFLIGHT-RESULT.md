# BATCH-Q-R1-STAGE7-ISOLATED-RUNTIME-PREFLIGHT — Controller Review

> Overall: **PASS**  
> Exact tested SHA: `9b2f18faf00872f364e26a9980b767854e96f9fa`  
> RunId: `stage7q1_20260918_152245`

## 1. Runtime preparation

The governed preparation script successfully created/reused only the run-scoped 3307 objects for mall, generator, analytics meta and analytics metric. The analytics metric account name was deterministically shortened to stay within MySQL's 32-character username limit. No fallback to 3306 was used.

Live probes subsequently reported the same isolated instance for analytics, analytics-meta and analytics-metric, with port `3307` and fingerprint authority `dahaishui:3307`.

## 2. Analytics dual-database gate

`AnalyticsIsolationFlywayIT` passed **1/1** with zero failures/errors/skips. The test verified separate meta/metric connections, write guards, Flyway-managed current versions, idempotent second migrate, and the required physical tables in their owning databases.

The rerun also exposed and fixed one test-only assertion bug: `MigrateResult.targetSchemaVersion` may be null when an already-current schema executes zero migrations. Commit `9b2f18f` replaced that invalid assumption with Flyway-current-version + second-migrate-zero + physical-table checks. The successful Q-R1 run is after that fix.

## 3. Isolated test counts

Same SHA / same runId / same 3307 instance:

- mall: **30/30 PASS**
- generator: **19/19 PASS**
- analytics: **11/11 PASS**
  - `IsolationGuardMySqlIT`: **6/6**
  - `MetricAdsMySqlIT`: **2/2**
  - `MetricPublisherMySqlIT`: **3/3**
- total isolated baseline evidence: **60/60 PASS**
- runner exits: **0**

All three analytics IT classes were present in the actual Maven log; this is not a count-only or zero-test PASS.

## 4. Safety / evidence boundary

- No writes to 3306.
- No manual database/account DDL outside the governed preparation script.
- Passwords were not printed or committed.
- Mall/generator credential refs remained ignored local files.
- Tracked workspace was clean around the verification work.

This PASS proves the current real-MySQL-3307 isolation boundary and the dual-database metric write IT lane. It does **not** prove the later Stage 7 HTTP ingestion → pipeline → Spark/Hive/HDFS chain, browser E2E, or a real LLM provider.

## 5. Post-verification registration

After the 60/60 evidence was obtained, commit `a496434` registered the new unified isolated baseline:

- analytics baseline `6 → 11`;
- total isolated baseline `55 → 60`;
- `run-tests.ps1 -Suite isolated|all` now requires analytics meta/metric secrets, passes `-IncludeAnalyticsWriteIts`, and independently requires all three analytics IT classes to appear in the log.

`a496434` is a registration/orchestration change made **after** the tested `9b2f18f` run and is therefore not misrepresented as the Q-R1 tested SHA.

