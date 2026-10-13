# 阶段 4 采集批次分页查询服务分层与离线回归（2026-10-09）

## 实现

- 将 `GET /api/v1/ingestion/batches/page` 的参数校验、排序白名单、稳定次排序、计数/页查询、超范围页码收敛及只读 `REPEATABLE_READ` 事务移入 `IngestionBatchQueryService`；控制器保留权限路由并委托查询服务。
- 查询服务单测验证 `status ASC,id DESC LIMIT 10 OFFSET 10`、`start_time DESC,id DESC`、ID 降序空结果边界及非法参数在访问 Mapper 前拒绝；控制器单测验证分页契约委托。
- MockMvc HTTP 契约验证受认证的新分页路由 JSON 元数据、匿名访问 401、业务参数错误 400，以及旧 `/batches?limit=` 仍返回数组。此 HTTP 测试使用 mock service，不访问数据库。

## 验证

- 定向命令：`mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=IngestionBatchQueryServiceTest,IngestionControllerPaginationTest,IngestionControllerPaginationHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；查询服务 4/4、控制器方法 1/1、MockMvc 2/2 PASS，合计 7/7，0 failure/error/skipped，七模块 reactor `BUILD SUCCESS`。
- 全回归命令：`mvn -o -f analytics-server/pom.xml -pl platform-app -am test`；Surefire 合计 1325 项，0 failure、0 error、2 skipped，七模块 reactor `BUILD SUCCESS`。
- 目标 Java 源码/测试 `git diff --check` 通过。

## 边界

本证据是 Windows JDK 17 离线后端测试；MockMvc 的模拟登录与 mock service 不证明真实管理员 HTTP、Spring 事务代理真实生效、MySQL 隔离/非空数据、浏览器展示或端到端流程。当前管理员凭据变量不在任务环境中；本轮未连接数据库、未访问 3306、未启动/停止服务。阶段 4 和总体验收保持开放。动态状态文档本轮编辑前备份后缀：`bak-20261009-ingestion-pagination-http-contract`。
