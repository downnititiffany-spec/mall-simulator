# Phase 4/8 isolated stack rebuild and live health — 2026-10-07

## Run identity and startup

- Branch `codex/cloud-v3-development`, source HEAD `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`; all pre-existing worktree changes were retained.
- New task RunId: `cloud_261007_082925_605e5a`.
- Run directory: `/home/asus/graduationproject-runs/cloud-e2e/cloud_261007_082925_605e5a`.
- A first default preflight safely refused the absent default JDK path and made no changes. Read-only inventory then grounded explicit overrides to installed JDK17, MySQL8.0.41, Hadoop3.3.4, Hive3.1.3, Spark3.5.1, Flume1.11.0, Maven3.9.12, Windows Node24.16.0, and a verified initialized HDFS namespace/config pair. Preflight passed twice; no path guard was bypassed.
- The unified `cloud-single-node.sh start` created a new task-owned RunId and returned success. It generated task database credentials in process and did not persist or print them.

## Fresh builds and tests

All packages were rebuilt from this worktree with JDK17.0.12 and Maven3.9.14, offline:

- `mvn -o -f analytics-server/pom.xml package`: 167 suites, 1,283 tests, 0 failures, 0 errors, 2 skips; `BUILD SUCCESS`. Skips are the two POSIX symlink tests on Windows.
- `mvn -o -f mall-simulator/pom.xml package`: 6 suites, 22 tests, 0 failures/errors/skips; `BUILD SUCCESS`.
- `mvn -o -f synthetic-data-generator/pom.xml package`: 23 suites, 134 tests, 0 failures/errors/skips; `BUILD SUCCESS`.

## Live checks

- `cloud-single-node.sh status`: RunId above registered; MySQL 3307, HDFS 19000/19010/19870/19864/19866, Metastore 19083, platform 8091, mall 8090, generator 8092, web 5173 and mall web 5174 all listening.
- The script's guarded verification confirmed Windows Vite PIDs 239816 (5173) and 259876 (5174) match the task RunId, expected Node executable, and ports.
- HTTP GET returned 200 for platform metrics health, mall root, generator scenarios, and both `/login` pages.
- Unauthenticated GET to admin users, decisions, and metric snapshot page endpoints returned 401, confirming the protected routes require a session.

## Evidence limits and safety

The task process and WSL environment both lack `GRADUATION_TEST_ADMIN_PASSWORD`; no secret was read or printed. No authenticated nonempty pagination or RuntimeProfile profile-switch test was attempted. The new RunId is a real fresh isolated application/database runtime, but this turn did not query database rows directly, run real Spark/Hive jobs, or validate the full business chain. Stage 4 and Stage 8 remain incomplete; this is not product acceptance. Port 3306 was not accessed. The task stack remains running for continued authorized work; no service was force-stopped and no data directory was cleaned.

`git diff --check` passed with existing LF/CRLF conversion warnings. No commit or push.
