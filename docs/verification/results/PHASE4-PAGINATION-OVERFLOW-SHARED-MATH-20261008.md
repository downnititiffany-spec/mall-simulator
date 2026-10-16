# 阶段 4 全分页总页数溢出修复

日期：2026-10-08  
范围：统一修复 offset 分页总页数向上取整的整数溢出风险。

## 修复

多个分页实现使用 `(total + size - 1) / size`。当 `COUNT(*)` 接近 `Long.MAX_VALUE` 时，中间加法溢出，错误的总页数会使请求页码被夹到第一页。

新增 `platform-common` 的 `PaginationMath.totalPages(total, size)`：空或非正总数保留现有 1 页语义，正数使用 `1 + (total - 1) / size`，并拒绝非正 page size。决策、快照、质量结果、流水线、AI 问数审计、AI 调用审计和管理员用户分页均改用这一共同实现；排序、页大小限制、响应字段及 offset 计算未改变。全仓扫描确认 `analytics-server` 不再残留旧向上取整表达式。

## 验证

定向离线 reactor：

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am `
  '-Dtest=PaginationMathTest,DecisionServicePaginationTest,MySqlMetricStorePaginationTest,MetricControllerPaginationHttpTest,PipelineControllerPaginationTest,AiControllerAuditPaginationTest,AuthServiceUserPaginationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

选定的分页/公共算术测试 **22/22 PASS**，0 failure、0 error、0 skipped。

七模块完整离线回归：

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am test
```

Surefire XML 按本轮生成时间汇总为 **170 suites / 1,313 tests / 0 failures / 0 errors / 2 skipped**，Maven reactor `BUILD SUCCESS`。模块计数：platform-common 121、connection-ingestion 404（2 skipped）、warehouse-pipeline 209、metric-analysis 152、ai-decision 209、platform-app 218。

## 证据边界

本报告证明当前工作树的 Java 离线测试和共享算术实现，不证明真实数据库 `COUNT(*)` 驱动行为、认证 HTTP/浏览器真实分页与排序，也不覆盖商城、生成器、Spark Scala、Web、真实 HiveServer2、LLM 或完整业务链。阶段 4 和项目总体验收保持未关闭。
