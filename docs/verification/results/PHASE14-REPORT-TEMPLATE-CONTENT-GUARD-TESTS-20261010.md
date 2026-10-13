# 阶段14 报告模板内容结构守卫定向回归（2026-10-10）

## 范围

针对 `ReportInstanceService.artifact()` 六模板守卫构造公共 Evidence/Lineage 完整且 SHA-256 正确的离线制品夹具。覆盖 OPS_DAILY、OPS_WEEKLY、ORDER_ANALYSIS、PRODUCT_ANOMALY、USER_OPERATIONS、DECISION_EFFECT 的合法结构读取；并覆盖必需字段缺失、错误 JSON 类型、周报非七日/日期窗口不匹配、决策结果非法和基线窗口不匹配，断言统一 fail-closed 为 `REPORT_ARTIFACT_INVALID`。

## 结果

- `ReportInstanceServiceTest`：15 tests，0 failures，0 errors，0 skipped。
- Maven 离线七模块 Reactor BUILD SUCCESS：`mvn -o -pl platform-app -am '-Dtest=ReportInstanceServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 首次运行曾暴露测试夹具把 OPS_DAILY 的 JSON null 强转为 ObjectNode；修复测试夹具类型后最终通过。生产代码未因该测试失败修改。

## 证据边界

仅离线服务单测，不证明已存历史制品、V37/V38、HTTP 认证 API、真实浏览器或端到端部署。task-owned RunId `cloud_261010_061315_23e7b3` 经正确的运行根目录实时查询为 12/12 目标端口 LISTEN；本轮未启停服务、未连接数据库，3306 未接触。阶段14及项目总验收仍开放。