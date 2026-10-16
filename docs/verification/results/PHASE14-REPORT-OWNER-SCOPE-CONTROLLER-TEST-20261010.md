# 阶段14 报告 API owner 范围控制器测试（2026-10-10）

- 新增 `analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/ReportInstanceControllerTest.java`，覆盖创建者从 `CurrentUserHolder` 取值、分页按当前会话用户名调用 owner 分页、详情/制品仅通过 `findOwn(id, currentUser)` 读取、未归属报告映射为不存在，以及公开摘要 record 不含内部 `artifactUri`。
- 与 `ReportInstanceServiceTest` 一起运行：`mvn -o -pl platform-app -am '-Dtest=ReportInstanceServiceTest,ReportInstanceControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。两个测试类分别 7/7 和 3/3，合计 10/10 PASS，0 failure/error/skipped；analytics-server 七模块 reactor BUILD SUCCESS。
- 证据范围是 Mockito/JUnit 控制器和服务离线测试，未启动应用、未验证认证拦截器真实 HTTP、数据库隔离、迁移或浏览器会话；真实 task-owned RunId 8091 仍关闭，DB凭据未在任务/WSL环境可见，3306未连接。
- 阶段14和项目总验收保持开放。
