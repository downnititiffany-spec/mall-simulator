# Java 应用模块当前工作树全量离线回归（2026-10-09）

## 执行范围与结果

- `mvn -o -f analytics-server/pom.xml test`：analytics-server 七模块 **1318 项，0 failure/error，2 skipped，BUILD SUCCESS**。
- `mvn -o -f mall-simulator/pom.xml test`：商城 **23 项，0 failure/error/skipped，BUILD SUCCESS**。
- `mvn -o -f synthetic-data-generator/pom.xml test`：生成器 **134 项，0 failure/error/skipped，BUILD SUCCESS**。
- 共覆盖 1475 项，全部已执行项目通过，另有 2 项后端跳过；各 reactor 均独立成功。

## 边界

均为当前 Windows 工作树离线 Maven 测试，包括 Mockito、应用上下文/MockMvc、本机 harness 与静态迁移守卫；不含真实 MySQL/3306、迁移应用/回读、真实商城 HTTP 写入与目标端核对、生成器-商城-采集-Spark 全链、真实服务认证或完整浏览器业务流。不能声称 exactly-once，也不关闭任何整体阶段或最终验收。
