# PHASE4-RATELIMIT-KEY-CAP-BEHAVIOR-20261007

## 范围

验证阶段 4 单节点进程内固定窗口限流器的全局键容量上限行为。没有启动/停止服务、连接数据库或访问 3306。

## 修改

在 `InMemoryRequestRateLimiterTest` 增加三项行为测试：

- 填满 10,000 个未过期键后，新键被拒绝，`retryAfterSeconds` 按最早到期窗口向上取整；窗口全部过期后新键可进入。
- 不同窗口长度的记录按各自期限回收；短窗口过期可释放一个容量，未到期长窗口仍继续执行其原限流预算。
- 预填 9,990 个键后并发创建 32 个不同键，恰好 10 个获准，第 11 个容量请求被拒绝。

## 验证

命令：

`mvn -o -f analytics-server/pom.xml -pl platform-app -am "-Dtest=InMemoryRequestRateLimiterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

JDK 17 七模块 Maven reactor `BUILD SUCCESS`；`InMemoryRequestRateLimiterTest` **5/5 PASS**，0 failure、0 error、0 skipped。既有每键预算/窗口重置和并发计数测试也在本次目标类运行中通过。

## 边界

这是进程内 Java 单测证据，不验证服务部署、多 JVM 状态协调或多节点限流。阶段 4 与产品验收保持未关闭。修改动态文档前备份：

- `docs/PROJECT_STATUS.md.bak-20261007-ratelimit-cap-behavior`
- `docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-ratelimit-cap-behavior`
- `docs/verification/CURRENT_BATCH.md.bak-20261007-ratelimit-cap-behavior`
