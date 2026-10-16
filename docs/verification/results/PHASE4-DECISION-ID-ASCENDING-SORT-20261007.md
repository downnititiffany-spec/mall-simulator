# 阶段 4 决策列表 ID 升序排序修正

日期：2026-10-07（Asia/Shanghai）

## 发现与修正

`DecisionService.listPage` 对任意排序字段都追加 `id DESC` 稳定排序键。当主排序字段为唯一主键 `id` 且调用 `sort=id,asc` 时，实际 SQL 片段会包含 `ORDER BY id ASC, id DESC`，与请求方向冲突。现仅在排序字段不是 `id` 时追加 `id DESC`；非唯一排序字段仍保留稳定分页次序。

## 验证

JDK 17 离线 Maven reactor（`platform-common`、`connection-ingestion`、`metric-analysis`、`ai-decision`）：

```powershell
mvn -o -f analytics-server/pom.xml -pl ai-decision -am '-Dtest=DecisionServicePaginationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`DecisionServicePaginationTest` 4/4 PASS，0 failures/errors/skips，`BUILD SUCCESS`。新增测试捕获 MyBatis LambdaQueryWrapper SQL，确认 `id,asc` 只保留升序而不追加下降序；原非唯一字段稳定 tie-break 与 LIMIT/OFFSET 测试继续通过。

## 限制

只验证查询构造和分页服务单测；未部署本轮代码或连接数据库，不代表真实服务的新排序行为已重新验收。此前决策列表 11 条非空浏览器分页证据属于另一个既有构建/RunId，不扩展到本修改。阶段 4 保持部分完成。

动态文档编辑前备份后缀：`bak-20261007-decision-id-ascending-sort`。
