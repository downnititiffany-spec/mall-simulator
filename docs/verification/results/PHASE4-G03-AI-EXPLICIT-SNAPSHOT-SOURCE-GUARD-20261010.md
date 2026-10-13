# 阶段4 G-03 AI 显式快照来源血缘 fail-closed（2026-10-10）

## 发现

批准的 D-059 要求显式历史快照读取以快照行固化的 `source_id` 为准，不得从当前 ACTIVE RuntimeProfile 推断历史来源。`AiScopeResolver` 已按精确 `snapshotId` 查询 ACTIVE/ARCHIVED 快照，但 `source_id` 为空时仍会继续构造问数作用域并执行。

## 修复

显式快照行缺少 `source_id` 时返回 `SNAPSHOT_NOT_AVAILABLE` 并 fail-closed。保留默认 ACTIVE 路径对历史兼容空 source_id 行的现有读取行为；不访问 meta 库补推来源，不更改历史行、迁移、快照范围或权限策略。

## 验证及边界

- `mvn -o -f analytics-server/pom.xml -pl ai-decision -am -DskipTests compile`：实际重编 ai-decision 41 个源码文件，platform-common、connection-ingestion、metric-analysis、ai-decision 均 `BUILD SUCCESS`。
- 未运行测试、真实 API/浏览器/数据库或跨 profile 历史读取；未连接 MySQL 3306。该改动仅为源码编译证据，不关闭 G-03、阶段4或总验收。
- 下一步需要对 `source_id IS NULL` 的显式快照进行定向回归，并验证真实授权用户读取既有历史快照及无权限拒绝。

依据：已批准决策 D-059（`docs/decisions/DECISION_LOG.md`）；冻结 V3.0 指导书和设计文档未修改。

## 复核更正（2026-10-10）

实现后对照 D-059 和 V12 迁移注释复核：历史 `source_id=NULL` 是既有合法未归属状态；D-059 禁止按当前 RuntimeProfile 推断来源，并未要求拒绝精确 snapshotId 读取。分析响应对 NULL 来源会保留空值并返回来源不可用警告。新增门禁因此属于不必要的范围收缩，已从 `AiScopeResolver` 撤回；本报告开头“修复”不再代表当前代码。AI 对历史快照来源为空时仍会保留 null，不做归属推断。此次复核没有新增代码行为，未运行测试/编译；此前编译结果只对应现已撤回的临时门禁，不能作为当前变更的验证证据。
