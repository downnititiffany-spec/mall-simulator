# PHASE4 / PHASE8 Live Run Health Check - 2026-10-08

## Baseline and build

- Worktree: `D:\Develop_code\GraduationProject-wt\v3-dev`
- Branch / HEAD: `codex/cloud-v3-development` / `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`
- `git fetch origin codex/cloud-v3-development` completed; ahead/behind was `0/0`.
- The existing worktree changes were preserved. It had 714 status entries at this check.
- Existing backend JARs were copied to `/home/asus/graduationproject-runs/artifact-preservation-before-repackage-20261008/` and SHA-256 checksums recorded in `SHA256SUMS` before packaging.
- `mvn -o -f analytics-server/pom.xml -pl platform-app -am package -DskipTests` completed with `BUILD SUCCESS`. This packaged the current V36 migration and current AI module. The resulting platform JAR SHA-256 is `3fff4e229ec1b09700ded87b86c1ecfe077e7215d2e678cdee48e72d898a1ad5`.
- Test execution for the working tree is recorded separately in `BACKEND-WORKTREE-REGRESSION-1311-20261008.md`; package execution itself skipped tests.

## Run and safety scope

- Task-owned RunId: `cloud_261008_040652_a898e1`.
- Runtime directory: `/home/asus/graduationproject-runs/cloud-e2e/cloud_261008_040652_a898e1`.
- The unified script preflight passed immediately before startup. It used the existing initialized HDFS storage `/home/asus/.cache/graduation-hdfs-smoke-20260924` (`clusterID=GP-SMOKE-20260924`) and a prior configuration that points to the same name/data directories and RPC port 19000.
- The script created fresh RunId-scoped schemas and application/HDFS paths. It did not format HDFS or reuse a prior MySQL schema. The platform meta schema migrated to V36; metric schema to V13; Mall to V9; generator to V1.
- No MySQL 3306 access occurred. No existing run directory or database was deleted. The task-owned stack remains running for continuation.

## Live checks

All checks below were performed after the startup script returned exit 0:

| Surface | Result |
|---|---:|
| `GET :8091/api/v1/metrics/health` | 200 |
| `GET :8090/` Mall | 200 |
| `GET :8092/api/v1/scenarios` | 200 |
| `GET :5173/login` Analytics UI | 200 |
| `GET :5174/login` Mall UI | 200 |
| Registered task ports 3307, 19000, 19010, 19870, 19864, 19866, 19083, 8091, 8090, 8092, 5173, 5174 | LISTEN |

The analytics login page was opened with headless Chromium at 1440×900 and awaited `networkidle`. It rendered a visible login form with two inputs and one button, with zero console errors, page errors, or failed requests. The live capture exposed stale footer copy (“V2.2 · 阶段7 看板”); `web/src/views/Login.vue` now says “电商数据链路 · 经营分析”, and `web/tests/loginFormSemantics.test.js` guards against the stale text. After Vite hot reload, the updated page returned HTTP 200 with zero console/page/request errors. Screenshot: `target/v25-it/phase4-live-cloud_261008_040652_a898e1/analytics-login-copy-update.png`.

After this copy correction, `npm run verify` exited 0; `npm test` reported 401 tests passed, 0 failed/cancelled/skipped, and the Vite production build succeeded.

Four protected requests (admin user page, decision page, AI call page, RuntimeProfile route) returned HTTP 401 without a session. This confirms the unauthenticated rejection path only; it does not prove authenticated access or data pagination.

## Remaining limits

- `GRADUATION_TEST_ADMIN_PASSWORD` was absent in both the current PowerShell task process and WSL process environment. No password was read or printed. Therefore real authenticated non-empty pagination and RuntimeProfile switching were not tested.
- The fresh application databases contain startup migrations/seeds only. No generation, ingestion, Spark pipeline, metric publication, or AI Provider call was triggered.
- Startup and public health checks are limited PHASE4/PHASE8 evidence. They do not close either phase, prove recovery/stop lifecycle, prove a real HiveServer2 RuntimeProfile probe, or establish full V3.0 acceptance.
- Google Drive knowledge base search failed with a connector transport error, so its index/governance rules could not be read and no Drive update was made.
