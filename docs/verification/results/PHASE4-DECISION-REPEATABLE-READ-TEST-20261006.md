# 阶段 4 决策列表分页一致性定向测试

## 结果

`DecisionService.listPage` 使用只读 `REPEATABLE_READ` 事务读取总数和当前页。针对该服务的既有定向单测通过：

- 命令：`mvn -o -pl ai-decision -am '-Dtest=DecisionServicePaginationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：`DecisionServicePaginationTest` **3/3 PASS**；0 failure、0 error、0 skipped；五模块 Maven reactor `BUILD SUCCESS`。
- 既有 Chromium/隔离服务证据另见 `PHASE4-DECISION-PAGINATION-BROWSER-20261006.md`，该浏览器切片实测 11 条合成 DRAFT 的 10+1 分页与 `createdAt,desc`。

## 证据边界

本次 Maven 测试是服务单测，不连接数据库，也不证明真实 MySQL 并发下隔离行为。浏览器证据证明非空数据分页和排序读链，不是并发一致性压力测试。阶段 4 其它门槛仍按完成清单逐项核验。