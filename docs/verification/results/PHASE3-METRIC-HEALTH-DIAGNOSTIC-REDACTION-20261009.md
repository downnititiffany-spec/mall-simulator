# 阶段 3 指标库健康诊断脱敏复核（2026-10-09）

## 实现

- `MySqlMetricStore.healthCheck()` 在只读查询失败时继续返回不可用状态，但健康 API 详情使用固定说明，不再包含 JDBC/驱动异常消息；日志只保留异常类型。
- `AnalysisService.dimensionRows()` 的可选 ADS 读取故障仍按既有受限空态降级并添加告警；日志不再写底层异常消息。

## 验证

- 命令：`mvn -o -f analytics-server/pom.xml -pl metric-analysis -am -Dtest=MySqlMetricStoreCapabilitiesTest,AnalysisServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：44 tests，0 failures，0 errors，0 skipped；platform-common、metric-analysis reactor `BUILD SUCCESS`。
- `MySqlMetricStoreCapabilitiesTest` 2 项（含异常详情脱敏）；`AnalysisServiceTest` 42 项。
- 仅离线单测/Mockito。未启动服务、未访问数据库或 3306，未做真实 API/浏览器验证。
- 该安全修复不构成阶段 3 或整体验收结论。
