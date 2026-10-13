# 阶段 4 限流键容量保护行为回归

日期：2026-10-07  
范围：`InMemoryRequestRateLimiter` 的单进程固定窗口与 10,000 键全局容量行为。

## 验证命令

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=InMemoryRequestRateLimiterTest,RateLimitInterceptorHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：七模块 reactor `BUILD SUCCESS`；`InMemoryRequestRateLimiterTest` 5/5、`RateLimitInterceptorHttpTest` 2/2，合计 7/7 PASS，0 failure、0 error、0 skipped。

## 覆盖范围

- 同一 key 的固定窗口预算、到期重置和 `retryAfterSeconds`。
- 全局键数达到 10,000 时新 key fail-closed，并报告最早到期窗口对应的重试秒数。
- 容量清理仅回收各自窗口已过期的 key；未过期 key 继续受限流预算保护。
- 并发同 key 请求不超过预算；并发新 key 不超过全局容量。
- MockMvc 验证认证先于限流；达到窗口预算后返回 HTTP 429、`Retry-After` 头及稳定错误码。
- MockMvc 验证同一用户跨相同 scope 的不同路由共享预算；不同用户及不同 scope 的预算相互隔离。

## 证据边界

行为和 HTTP 契约由可控时钟单测及 MockMvc 验证，证明当前单进程实现与受测 Spring MVC 链路的行为。未验证真实部署负载或跨进程/多节点共享限流；不扩大为分布式限流能力证据。阶段 4 与最终验收保持未关闭。
