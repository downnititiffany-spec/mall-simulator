# 阶段 4 决策分页排序与页内快照一致性复核

日期：2026-10-09  
范围：复核当前工作树 `DecisionService.listPage` 的排序方向、count/page 一致性声明及分页边界。

## 当前实现

- 分页读取标记为只读 `REPEATABLE_READ` 事务，使总数与当前页查询在同一事务上下文执行。
- 显式 `id,asc` 排序不再附加冲突的 `id DESC`；非 ID 主排序仍追加 ID 降序作为稳定并列次序。
- 总页数使用 `PaginationMath.totalPages`，避免大计数加法溢出；请求页超范围时夹到最后一页。

## 验证

Windows 11、JDK 17.0.12、Maven 3.9.14，离线定向命令：

```powershell
mvn -o -f analytics-server/pom.xml -pl ai-decision -am `
  '-Dtest=DecisionServicePaginationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`DecisionServicePaginationTest` 5/5 PASS，0 failure、0 error、0 skipped；platform-common、connection-ingestion、metric-analysis、ai-decision reactor `BUILD SUCCESS`。覆盖分页元数据/越界夹取、空结果和非法参数、非 ID 排序的 ID 稳定次序与偏移、ID 升序不被反向覆盖，以及 `Long.MAX_VALUE` 计数的页数边界。

## 证据边界

测试使用 Mockito Mapper，不连接 MySQL，也未通过 Spring 代理实测事务隔离；不能证明真实隔离栈的并发写入下 count/page 行为、受认证 HTTP 非空分页/排序或浏览器呈现。管理员凭据未进入本任务环境；阶段 4 和总体验收保持开放。未访问 3306，未修改数据库或服务。
