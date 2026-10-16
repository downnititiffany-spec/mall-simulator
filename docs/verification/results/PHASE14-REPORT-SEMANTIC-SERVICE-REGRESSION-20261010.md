# ReportInstanceService 语义回归（2026-10-10）

- 证据：`analytics-server/ai-decision/src/test/java/com/graduation/analytics/report/ReportInstanceServiceTest.java`。
- 新增覆盖：ORDER_ANALYSIS 仅使用已发布白名单指标并标记不可用指标；PRODUCT_ANOMALY 沿用同一快照/数据源且缺失转化率保持 null、不推断库存风险；USER_OPERATIONS 拒绝跨快照视图且不创建记录。
- 命令（在 `analytics-server`）：`mvn -o -pl platform-app -am '-Dtest=ReportInstanceServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。结果 `ReportInstanceServiceTest` 10/10 PASS，0 failures/errors/skipped，七模块 Maven Reactor BUILD SUCCESS。
- 另行打包：`mvn -o -pl platform-app -am -DskipTests package`，BUILD SUCCESS。
- 范围限制：这是离线 JUnit/Mockito 证据；不证明报告真实 HTTP/DB/权限隔离、artifact 持久化或浏览器流程。阶段14与总验收保持开放。
