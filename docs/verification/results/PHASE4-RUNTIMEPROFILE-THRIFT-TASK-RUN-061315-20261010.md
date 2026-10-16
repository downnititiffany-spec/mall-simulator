# 阶段4 当前 task-owned RuntimeProfile Thrift HiveServer2 探测（2026-10-10）

- RunId：`cloud_261010_061315_23e7b3`。专用 Hive 3.1.3 HiveServer2 监听 10016，认证模式 NONE，专用 embedded Derby 与本 RunId 的 warehouse/config 路径；Web UI 使用10018以避开另一RunId占用的10002。未复用共享 Metastore 19083，也未使用另一RunId的 HS2 10015。
- 通过已授权的管理员业务 API 创建 DRAFT profile ID 2，JDBC 指向 `jdbc:hive2://127.0.0.1:10016/default`，credential reference 为空。随后调用 `POST /api/v1/runtime-profiles/2/test`。
- 真实应用 JDBC 探测：Landing PASS（探测通过）；Hive PASS（`SELECT 1 ok (HiveServer2)`）；Metric PASS（metricStore query ok）；Spark FAIL（当前候选未配置 `spark_submit_path`，按规则不硬编码路径）。因此 `allPassed=false`，该 DRAFT 保持 DRAFT；唯一 ACTIVE ID 1 前后未变，未激活、切换或编辑连接配置。
- 范围限制：证明真实 RuntimeProfile API 经 HiveServer2 Thrift JDBC 执行 `SELECT 1`；不证明全检查通过、HDFS warehouse、共享 Derby Metastore、认证型 Hive 或阶段4整体验收。操作通过应用 API；未直连数据库，未连接3306。
