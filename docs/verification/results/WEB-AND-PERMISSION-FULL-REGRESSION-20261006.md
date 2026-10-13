# Web and endpoint permission regression — 2026-10-06

## Finding and correction

The first `npm run verify` run exposed two stale source guards (398 tests: 396 passed, 2 failed). The permission cross-tree guard had not added five newly wired pagination GET routes to its frozen endpoint-to-permission map. Inspection of the production annotations confirmed their existing permissions: admin users → `USER_MANAGE`, AI history/call audit → `AI_AUDIT_VIEW`, quality results → `OPS_LOG_VIEW`, and snapshots → `DASHBOARD_VIEW`. The Java permission coverage table now includes those five entries.

The pipeline operation test still expected `runOnce` to call `load()` directly. Production code now calls `refreshRunsAfterSubmission()`, which catches list-refresh errors so they cannot overwrite a successful RunId submission; the assertion now covers that behavior.

## Verification

- `cd web && npm run verify`: **398/398 tests PASS** (0 failed/canceled/skipped) and Vite production build PASS (680 modules).
- Java `ControllerPermissionCoverageTest`: **5/5 PASS**, offline seven-module Maven reactor BUILD SUCCESS, JDK 17, Maven 3.9.14.
- No application service or database was started or accessed for these checks.

## Evidence boundary

This verifies frontend source contracts, production bundling, and permission-annotation coverage. It does not establish live authenticated pagination against populated data or product acceptance.