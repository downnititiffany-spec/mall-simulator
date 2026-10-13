# 阶段 4 决策分页总页数溢出修复

日期：2026-10-08  
范围：决策记录分页 `DecisionService.listPage` 的页数计算和离线定向回归。

## 修复

原实现用 `(total + size - 1) / size` 向上取整。当数据库返回接近 `Long.MAX_VALUE` 的总记录数时，加法会溢出为负数，随后 `Math.max(1, ...)` 会把总页数错误地变成 1，服务再将请求页码夹到第一页。

现在 `total == 0` 时仍返回 1 个空页；正数总数使用 `1 + (total - 1) / size`，避免中间加法溢出。分页上限、排序、稳定 ID 次序和响应字段均未改变。

## 验证

Windows JDK 17.0.12 / Maven 3.9.14，离线运行：

```powershell
mvn -o -f analytics-server/pom.xml -pl ai-decision -am `
  '-Dtest=DecisionServicePaginationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`DecisionServicePaginationTest` **5/5 PASS**，0 failure、0 error、0 skipped；platform-common、connection-ingestion、metric-analysis、ai-decision reactor `BUILD SUCCESS`。

新增边界用例令 mapper 返回 `Long.MAX_VALUE`，以 `size=100` 请求 `Long.MAX_VALUE` 页，断言总页数与最终夹取页码等于无溢出计算结果。此前排序、分页偏移、参数拒绝和空列表用例一并通过。

## 证据边界

本结果证明当前源码在 Mockito mapper 替身下的离线服务行为，不证明真实 MySQL `COUNT(*)` 在目标数据规模下的驱动转换、认证 HTTP 分页、浏览器显示或真实非空排序。阶段 4 和项目总体验收保持未关闭。
