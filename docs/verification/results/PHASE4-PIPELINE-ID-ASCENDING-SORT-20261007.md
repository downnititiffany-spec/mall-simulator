# 阶段 4 流水线分页 ID 升序排序修正

日期：2026-10-07（Asia/Shanghai）

## 发现与修正

`PipelineController.page` 对所有排序字段都追加 `id DESC` 稳定键。`sort=id,asc` 因此生成 `ORDER BY id ASC, id DESC`，与请求方向冲突。现只在非 ID 字段排序时追加降序 ID 并列键。

## 验证

JDK 17 离线七模块 Maven reactor：

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=PipelineControllerPaginationTest,DecisionServicePaginationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`PipelineControllerPaginationTest` 5/5、`DecisionServicePaginationTest` 4/4 PASS，共 9/9，0 failures/errors/skips，`BUILD SUCCESS`。测试捕获 MyBatis LambdaQueryWrapper，断言 ID 升序只有 `id ASC`，且既有非唯一排序稳定键、分页元数据、边界及事务属性测试通过。

## 限制

验证范围为本轮源码及离线测试，未部署新 JAR 或连接数据库；不证明当前运行栈使用本次修正。决策排序对应独立修正与证据 `PHASE4-DECISION-ID-ASCENDING-SORT-20261007.md`。阶段 4 保持部分完成。

动态文档编辑前备份后缀：`bak-20261007-pipeline-id-ascending-sort`。
