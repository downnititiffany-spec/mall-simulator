# 阶段 4 采集批次分页 SQL 排序与偏移契约（2026-10-09）

## 范围

补强 `IngestionControllerPaginationTest`，除验证响应分页元数据外，直接捕获 Mapper 收到的 MyBatis Lambda 查询片段，固定状态升序分页的实际排序及页偏移：

```text
ORDER BY status ASC,id DESC LIMIT 10 OFFSET 10
```

该断言覆盖非唯一排序字段以 `id DESC` 稳定次排序，以及第 2 页（size=10）的 OFFSET=10。测试初始化 MyBatis 表字段缓存只为将 Lambda 查询渲染为 SQL 片段，不启动 Spring 应用。

## 验证

命令：

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=IngestionControllerPaginationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`IngestionControllerPaginationTest` 3/3 PASS，0 failure、0 error、0 skipped；platform-common 至 platform-app Maven reactor `BUILD SUCCESS`。

## 边界

本证据只证明离线控制器与 MyBatis Wrapper SQL 组装契约，不证明数据库真实执行、事务隔离、认证 HTTP、实服非空分页或浏览器展示。仍需使用受控管理员会话验证真实 API/数据库和页面；阶段 4 与项目总体验收保持开放。没有连接数据库、启动/停止服务或访问 3306。
