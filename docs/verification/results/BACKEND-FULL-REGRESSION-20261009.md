# analytics-server 当前工作树完整离线回归（2026-10-09）

- 命令：`mvn -o -f analytics-server/pom.xml test`
- Windows JDK 17.0.12 / Maven 3.9.14；七模块 reactor：platform-common、connection-ingestion、warehouse-pipeline、metric-analysis、ai-decision、platform-app。
- Surefire 汇总：**1318 tests，0 failures，0 errors，2 skipped**；七模块均 SUCCESS，`BUILD SUCCESS`，总耗时约 58 秒。
- 含本轮 IngestionController 服务端分页及权限覆盖测试。统计来自本轮 172 个 Surefire XML 报告。
- 属本机离线测试证据；不含商城/生成器/Spark/Web、真实服务、真实 MySQL/3306、管理员认证、浏览器或完整业务链。阶段4和总体验收不据此关闭。
