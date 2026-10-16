# 阶段 4 MetricStore 快照分页契约收敛（2026-10-07）

## 实现

- 将快照分页返回记录 `SnapshotPage` 与 `listSnapshotsPage(page,size,sort)` 下沉至 `MetricStore`，并把既有兼容数组 `listSnapshots(limit)` 一并声明在接口。
- `MySqlMetricStore` 继续负责只读数据库查询、排序白名单和一致读事务；`MetricController` 仅依赖 `MetricStore`，不再以 MySQL 实现类作为注入类型或 API 返回类型。
- HTTP 字段、路由、排序行为、权限及旧数组契约不变。

## 验证

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=UserAdminPaginationHttpTest,AiControllerAuditPaginationTest,MetricControllerPaginationHttpTest,MySqlMetricStorePaginationTest,AuthServiceUserPaginationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

JDK 17 七模块 reactor BUILD SUCCESS。五个测试类共 **11/11 PASS**，0 failure/error/skipped；同时重新编译了 `platform-common`、`metric-analysis` 与 `platform-app` 的生产/测试代码。

## 边界

只证明当前 MySQL 实现和 MockMvc/替身下的接口契约兼容；没有第二种 Store 实现、真实数据库或认证浏览器验收。本修复不关闭阶段 4，也不构成产品验收。
