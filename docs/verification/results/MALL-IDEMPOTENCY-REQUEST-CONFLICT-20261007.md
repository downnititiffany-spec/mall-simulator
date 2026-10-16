# 商城幂等账本同操作不同请求冲突回归

日期：2026-10-07

## 范围

`MallBusinessService.executeIdempotently` 的键账本必须同时绑定操作名和规范化请求摘要。此前单测覆盖同键同请求回放及跨操作冲突，但未单独覆盖“操作名相同、请求内容不同”的冲突分支。本次补充该负例，并验证首次成功注册会把操作名、请求摘要及原始用户 ID 写入幂等账本，确保重放可以返回原结果。

## 验证

- 定向：`mvn -o "-Dtest=MallBusinessServiceIdempotencyTest" test`，6/6 PASS，0 failure/error/skipped。
- 模块默认回归：`mvn -o test`，20/20 PASS，0 failure/error/skipped，BUILD SUCCESS。
- JDK 17，离线 Mockito/本地单测。

## 限制

- 未连接商城数据库、未应用 Flyway V8/V9、未验证唯一键并发竞争或真实事务回放。
- 不证明当前部署商城已启用账本；不声称端到端 exactly-once。阶段 2/7 仍未关闭。
- 未访问 MySQL 3306。
