# BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT — Verification Plan

## 1. Purpose

Begin Stage 7 with the smallest real-runtime boundary that already has a governed repository entrypoint:

- create a fresh runId-scoped isolation target on local WSL MySQL 3307;
- prove the isolation preparation gate refuses formal 3306 semantics;
- run the repository's unified isolated test lane against that fresh target;
- require mall / generator / analytics isolated suites to execute for real and match their registered counts.

This batch intentionally does **not** run the full HTTP ingestion → pipeline → metrics chain yet. It first establishes that the 3307 runtime boundary is currently available and trustworthy.

## 2. Exact code baseline

Use the latest accepted production/test source SHA:

`2f3e79f676e1b614fe9a57e71e7ecad68106a51f`

Accepted predecessor:
- `BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS`
- PASS
- Web baseline: 307/307.

Branch context: `feature/v3-development`.

No production or test source changes are introduced by this Batch Q plan.

## 3. Safety boundary

Mandatory rules:

1. **Never write to MySQL 3306.**
2. Isolation instance port must be **3307**.
3. Use a new runId: `stage7q_20260918_1100`.
4. Derived databases/accounts must contain that runId.
5. Application/test connections use generated restricted accounts only. Root is permitted only inside `it-prepare-isolation.ps1` for fresh 3307 isolation-object creation and only through its explicit `-AllowRootOnIsolated` gate.
6. Do not hand-write `CREATE/DROP/GRANT` commands outside the repository script.
7. Do not delete historical 3307 objects in this batch.
8. Credentials remain in ignored `credref-*.properties` files / process environment only; never commit or print plaintext credentials.
9. Do not modify source/tests/docs during verification.
10. If 3307 is unavailable, record **BLOCKED_ENV** and stop. Never fall back to 3306.

## 4. Registered current isolated baseline

Current `scripts/run-tests.ps1` registers:

- mall: **30**
- generator: **19**
- analytics: **6**

Expected total: **55/55**.

Analytics must genuinely execute `IsolationGuardMySqlIT`; zero selected tests or a missing class execution is failure.

## 5. Commands

### 5.1 Checkout

```powershell
git fetch origin
git checkout --detach 2f3e79f676e1b614fe9a57e71e7ecad68106a51f
git rev-parse HEAD
git status --short
```

Expected exact SHA and clean workspace.

### 5.2 Preparation dry-run

```powershell
$runId = 'stage7q_20260918_1100'
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId $runId -Port 3307 -DryRun
```

The printed target list must contain only:
- `stage7q_20260918_1100_mall`
- `stage7q_20260918_1100_generator`
- `stage7q_20260918_1100_mallapp`
- `stage7q_20260918_1100_genapp`
- port 3307.

It must not target port 3306, formal databases, or formal application accounts.

### 5.3 Real isolation preparation

Only if dry-run is correct and 3307 is available:

```powershell
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 `
  -RunId $runId `
  -Port 3307 `
  -Confirm `
  -AllowRootOnIsolated
```

The repository script owns database/account creation. Do not replace it with manual SQL.

If the command cannot reach/administer isolated 3307, classify as `BLOCKED_ENV`, preserve evidence, and stop.

### 5.4 Load restricted credentials into process environment

Do not print passwords.

```powershell
$mallCred = Get-Content "mall-simulator/credref-$runId-mall.properties" |
  Where-Object { $_ -and -not $_.StartsWith('#') } |
  ConvertFrom-StringData
$genCred = Get-Content "synthetic-data-generator/credref-$runId-generator.properties" |
  Where-Object { $_ -and -not $_.StartsWith('#') } |
  ConvertFrom-StringData

$env:IT_GUARD_PASSWORD_MALL = $mallCred.password
$env:IT_GUARD_PASSWORD_GENERATOR = $genCred.password
```

### 5.5 Unified isolated lane

```powershell
pwsh -NoProfile -File scripts/run-tests.ps1 `
  -Suite isolated `
  -RunId $runId `
  -Confirm
```

After execution:

```powershell
Remove-Item Env:\IT_GUARD_PASSWORD_MALL -ErrorAction SilentlyContinue
Remove-Item Env:\IT_GUARD_PASSWORD_GENERATOR -ErrorAction SilentlyContinue
```

### 5.6 Final workspace check

```powershell
git rev-parse HEAD
git status --short
```

Ignored `credref-*.properties` files may exist locally but must not appear as tracked changes.

## 6. PASS criteria

PASS only if:

1. exact checked-out SHA = `2f3e79f676e1b614fe9a57e71e7ecad68106a51f`;
2. dry-run names only fresh runId-scoped 3307 objects;
3. real preparation executes only on 3307 through the governed script;
4. unified isolated runner exits 0;
5. mall = **30/30**;
6. generator = **19/19**;
7. analytics = **6/6**;
8. total = **55/55**;
9. failures/errors/skips for these suites = 0;
10. analytics log proves `IsolationGuardMySqlIT` actually executed;
11. isolation guard live facts prove port 3307 and expected isolated instance identity;
12. no formal database/account target is used;
13. source workspace remains clean;
14. no repair/source modification occurs during verification.

## 7. BLOCKED_ENV criteria

Record `Overall: BLOCKED_ENV` rather than FAIL if the only blocker is external runtime availability, such as:

- WSL MySQL 3307 not listening;
- WSL/mysql client unavailable;
- isolated-instance administrator authentication unavailable.

When blocked:
- never fall back to 3306;
- do not modify code/tests to bypass the environment;
- record exact failed command, exit code and observable environment fact;
- stop before the isolated lane if preparation cannot complete.

A test failure after isolation preparation succeeds is **FAIL**, not BLOCKED_ENV.

## 8. Evidence boundary

A PASS proves:
- fresh run-scoped isolation preparation works through governed scripts;
- current mall/generator/analytics isolated integration suites run against real MySQL 3307;
- isolation guards are live runtime gates, not only source-characterization tests.

A PASS does not yet prove:
- three-program real HTTP chain;
- ingestion → pipeline → Spark → publish E2E;
- real spark-submit / Hive metastore / HDFS;
- browser E2E;
- AI real provider;
- 3306 production behavior.

## 9. Result persistence

Write:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md
```

Include:
- Overall = PASS / FAIL / BLOCKED_ENV;
- exact tested SHA;
- dry-run target summary;
- preparation exit code;
- isolated runner exit code;
- mall/generator/analytics counts;
- live port/fingerprint facts;
- workspace status before/after;
- explicit statement that no 3306 fallback occurred.

## 10. Next gate

Only after Batch Q PASS should the controller open the next Stage 7 batch for the real HTTP/pipeline chain.

Do not run the full HTTP ingestion/pipeline chain inside Batch Q.
