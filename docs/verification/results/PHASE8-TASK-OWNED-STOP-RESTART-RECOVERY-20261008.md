# PHASE8 Task-Owned Single-Node Stop and Restart Recovery - 2026-10-08

## Objective

Exercise the frozen single-node script's task identity checks and normal stop/start lifecycle against the isolated current-worktree deployment. No business workflow or final product acceptance was attempted.

## Run sequence

- Before stop, `scripts/cloud-single-node.sh status` identified `cloud_261008_040652_a898e1`; all registered ports (3307, HDFS 19000/19010/19870/19864/19866, Metastore 19083, Platform/Mall/Generator 8091/8090/8092, Web 5173/5174) were listening.
- Process command lines, working directories, RunId PID records, HDFS PID files, Windows Vite PID files, executable path, port and run marker were checked against the task-owned run. Stop was executed only through `scripts/cloud-single-node.sh stop` with explicit matching binary/config paths. Exit 0 confirmed the owned processes stopped and database/HDFS files were preserved; the script did not target any unregistered process.
- The same binary and existing HDFS namespace configuration passed `scripts/cloud-single-node.sh preflight`. Preflight reported no resource or process creation.
- Start returned exit 0 with new task RunId `cloud_261008_041944_41e68e`.

## Recovery checks

- Script status reported all 12 registered ports LISTEN.
- Public checks returned HTTP 200: Platform `/api/v1/metrics/health`, Mall `/`, Generator `/api/v1/scenarios`, Analytics `/login`, Mall UI `/login`.
- Anonymous `GET /api/v1/admin/users/page?page=1&size=10` returned 401, confirming the protected-route boundary after restart only.
- Existing initialized HDFS NameNode and DataNode `clusterID` values remain `GP-SMOKE-20260924`; a retained historical HDFS sample remains readable at 900 bytes, and the new run-specific `/landing/cloud_261008_041944_41e68e` exists.
- The prior RunId MySQL data directory remains present. The new run created its own data directory/schemas. No MySQL 3306 access occurred. No HDFS format or data-directory deletion occurred.

## Result and limits

Task-owned stop/start recovery for this single-node run passed with persistent HDFS identity/data retained. This does not prove database row-level restoration for the old MySQL schema, recovery from process crash or mid-pipeline interruption, full Spark publication recovery, authenticated paging, RuntimeProfile switching, browser employee workflows, performance, or V3.0 final acceptance. Run `cloud_261008_041944_41e68e` remains running for continued work. No commit or push was made.