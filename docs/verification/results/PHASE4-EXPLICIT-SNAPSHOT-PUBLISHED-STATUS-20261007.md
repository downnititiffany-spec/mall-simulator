# 阶段 4 显式快照读取仅允许已发布状态

日期：2026-10-07

## 发现与修复

G-03 裁决 D-059 允许用户通过精确 `snapshotId` 回读跨 RuntimeProfile 历史快照。复核发现只读指标值查询此前仅按 `snapshot_id` 过滤；若 `BUILDING`、`VERIFYING` 或 `FAILED` 快照残留了指标值，普通 `/metrics/overview?snapshotId=...` 路径也可能返回这些未发布值。分析专题的 `pin` 同样只检查快照行存在，随后可能继续读取 ADS。

- `MySqlMetricStore.query` 现在通过 `metric_snapshot` 关联并限制状态为 `ACTIVE` 或 `ARCHIVED`。仍按传入的精确 ID 查询，不从当前 profile 推断历史归属。
- `AnalysisService.pin` 仅接受 `ACTIVE`/`ARCHIVED`；不存在或未发布的显式快照返回 `UNKNOWN_SNAPSHOT` 空信封，不读 ADS，也不回退 ACTIVE。
- `UNKNOWN_SNAPSHOT` 中文提示更新为“快照不存在或尚未发布”，避免把存在但不可读的状态说成不存在。

## 验证

- JDK 17 离线 Maven：`mvn -o -f analytics-server/pom.xml -pl metric-analysis,ai-decision -am '-Dtest=AnalysisServiceTest,MySqlMetricStoreActiveProfileTest,MySqlMetricStorePaginationTest,EvidenceBuilderTest,EnvelopeWarningCodeMirrorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：`AnalysisServiceTest` 42/42、`EnvelopeWarningCodeMirrorTest` 4/4、`MySqlMetricStoreActiveProfileTest` 4/4、`MySqlMetricStorePaginationTest` 3/3、`EvidenceBuilderTest` 7/7；合计 **60/60 PASS**，0 failure/error/skipped，五模块 reactor `BUILD SUCCESS`。
- Web 文案/信封定向回归：`node --test tests/envelope.test.js`，**13/13 PASS**。

## 证据边界

仅离线单元与文案测试。未连接 MySQL、未部署或验证 `metric_read` 权限账号对关联查询的真实授权，也未验证真实 profile 切换或未发布快照的数据库残留数据。阶段 4 和项目整体仍未关闭。未访问 3306。
