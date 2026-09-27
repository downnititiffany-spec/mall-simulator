# 批次 G31-08 结果 — F-G4-1（D-040）同日增量写入修复 + 针对性回归

- **批次**：G31-08（D-044④ 两个短批次之第一批）
- **日期**：2026-09-25
- **对应计划**：`docs/verification/batches/BATCH-G31-08-FG41-SAMEDAY-INCREMENTAL-PLAN.md`
- **状态**：✅ 完成（代码修复 + 窄回归 7/7 + 全 spark 套件 329/329、41 套件、JDK8=True）
- **提交纪律**：不 commit、不 push（等用户安排）；本批触碰文件见 §5 归属清单

## 1. 缺陷与修复

**缺陷（F-G4-1 / D-040）**：`EventOdsLoadJob` 的四条 ODS 写出模板是裸 `INSERT OVERWRITE TABLE <ods> PARTITION (dt, hour)`，未设置 `spark.sql.sources.partitionOverwriteMode` 时默认 STATIC = **整表覆写**——同业务日第二次增量批次把历史清空（G31-04 run2 实测：3 张 ODS 表 parquet 归零）。且 D-044④ 硬约束指出：**仅开动态分区覆盖也不够**——同一分区内的旧记录仍会被新批覆写掉。

**修复**（两个生产文件，读路径/校验/拒绝计数/JobResult 输出面零变化）：

1. `spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala` — 写出改为分区作用域「读-合并-去重-覆写」，每表：
   - `newRows` = 现行 SELECT 模板输出（`OdsLoadSql.selectFromLanding`）；
   - `hits` = `newRows` 的 distinct `(dt, hour)`（本批命中分区）；
   - `staleExisting` = 目标表 semi-join `hits`（只取命中分区旧行）anti-join `newRows.event_id`（同 event_id 旧副本让位新批 = **new-wins**）；
   - `merged` = `newRows ∪ staleExisting`，整体 `INSERT OVERWRITE … PARTITION (dt, hour)` 写回命中分区（写期间 `partitionOverwriteMode=dynamic`，`try/finally` 还原，防跨作业泄漏）；
   - 性质：**非命中分区物理不动；命中分区旧行保留；同批重放 anti-join 幂等；空批零写入**（旧实现的空 STATIC INSERT 会清表）。
2. `spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala` — 新增 `ColDt/ColHour/ColEventId`、`mergeView(table)`、`mergeInsertOverwrite(ns, table, mergedView)`（SELECT 列序 = `OdsV2Columns.columnNames(table)`，与 A12d 表 schema 契约同源）；四条 `*FromLanding` INSERT 文本**字节不变**（`insert()` 委托 `selectFromLanding()`，`topicWhere` 返回原 where 串），A9/A9d 与 SqlTemplateSpec 契约不动。

**语义决策（new-wins）**：同 event_id 重投递时旧副本被新批替换（`ingest_batch_id` 取新批）。依据：at-least-once 投递下重投递是同一事件的再确认，取新批即「重跑不重复」；D-044④ 判据「原有数据保留＋新增数据计入＋重跑不重复」在该语义下三方同时成立（G31-08 回归批次序列 A→B→B-replay→C→D→E 全部覆盖）。

## 2. 测试证据

### 2.1 新增针对性回归 `OdsMergeIncrementalSpec`（7/7 全绿）

夹具：golden 克隆派生 5 个批次 A→B→B-replay→C→D→E（`D:/Develop/tmp/g31-08-landing/<runId>/`，只新建不删除）；自带 SparkSession（**不预置** `partitionOverwriteMode`——P2TestSupport 预置 dynamic 会掩盖「作业自设自还」）；own runId（`-Dg31_08.test.runId` 可复现）。

| 判据 | 断言 | 结果 |
|---|---|---|
| G1 原有数据保留 | B 后各表 id 集 == A ∪ B | ✅ |
| G2 新增计入 | 各表分区行数 == A ⊕ B 贡献（分区级全可加） | ✅ |
| G4 **同分区旧记录并存（硬约束）** | 克隆行保持 event_time ⇒ 与 A 行同 (dt,hour)；B 后该分区行数 = A + B（covered=behavior/trade，共享分区非空守卫） | ✅ |
| G3 重跑不重复 | B-replay 各表快照（id 集+分区图+总数）逐位 == B | ✅ |
| new-wins | C 后重投递行 `ingest_batch_id` == 103；`GROUP BY event_id HAVING COUNT(*)>1` == 0 | ✅ |
| G5a 单主题不清他表 | D（仅 user_registered）后其余三表快照逐位不变 | ✅ |
| G5b 零写入守卫 | E（全拒绝）后四表快照逐位不变 | ✅ |
| G6 conf 还原 | 跑完 6 批次后 `partitionOverwriteMode` == 进入前出厂值 STATIC | ✅ |

正对照：基线 A 非空断言（`snapsA` 总和 > 0）防假绿；夹具健全性断言（4/2/2/2）防夹具退化。

### 2.2 全 spark 套件回归

`pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark`（JDK8）：**329/329 全绿、41 套件、failed=0 aborted=0、`All tests passed`、BUILD SUCCESS**（2026-09-25 16:18）。既有套件零回归——四条 `*FromLanding` 模板字节稳定的契约（A9/A9d、SqlTemplateSpec）与 ODS v2 保真契约（A12d/OdsV2ByteFidelitySpec）全部保持绿。

基线漂移处理：首跑 329 对照旧基线 322 报 `DRIFT ⇒ FAIL exit=7`（唯一失败项＝基线比对，非测试失败），随后 `$BaselineSpark` 322→329 并按惯例补记注释（`scripts/run-tests.ps1` §2026-09-25 条目）；同步后 **MATCH 复跑 PASS exit=0**（329/329、41 套件、JDK8=True，日志 `v25tests-dev003c_20260925_162007_afe66c`）。

## 3. 边界与诚实登记（必须随批报告）

1. **测试域 ≠ 在产 metastore**：spark-hive 为 provided，回归套件用 in-memory catalog + `USING parquet`（`LocalSchemaInitJob.statements`）。合并语义在**真 Hive metastore / 真 parquet ODS 表**上的行为归 **D-044⑤ 合并重跑**（重跑受影响链路 + G31-07 终验）验证，本套件不宣称「在产成立」。
2. **平台链路未重跑**：本批只动 spark-jobs 两个文件，LOCAL/HDFS 平台摄取与 LOAD_ODS 平台编排未重跑；D-044⑤ 统一合并重跑时以本修复入链。
3. **批内不去重保持现状**：同批次内重复 event_id 不合并（与 v1 行为一致）；重放稳定性由 anti-join 保证。若未来引入批内重复，属独立语义决策，不在本批范围。
4. **new-wins 语义变更登记**：见 §1 末段——这是对「重投递」的显式语义选择，非静默行为。
5. **G31-09（D-041 checkpoint 列宽）未动**：`file_identity` VARCHAR(64) 缺陷仍在，HDFS 档同文件重扫幂等继续**不得**作为验收口径（D-044④ 边界不变）。
6. 3306 零接触；不改已发布迁移；冻结 V3.0 文档零触碰。

## 4. D-044④ 判据对账

| 判据 | 证据 |
|---|---|
| 原有数据保留 | G1（id 集 ⊇）+ G4（同分区行数可加）+ G5a/G5b（零误清） |
| 新增数据计入 | G2（分区级可加）+ G1（id 集 ∪） |
| 重跑不重复 | G3（同批重放逐位不变）+ new-wins（零 event_id 重复） |
| 硬约束：同分区内旧记录不被覆盖 | G4（克隆行同 (dt,hour) 且行数 A+B） |

## 5. 归属清单（本批触碰文件，供提交分组）

| 文件 | 变更 |
|---|---|
| `spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala` | 修改：类文档第 5 条 + 合并写出口 |
| `spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala` | 修改：头注释 + topicWhere/selectFromLanding 重构 + merge 段 |
| `spark-jobs/src/test/scala/com/graduation/analytics/OdsMergeIncrementalSpec.scala` | **新增**：7 条判据回归 |
| `scripts/run-tests.ps1` | 修改：`$BaselineSpark` 322 → 329（按惯例注释） |
| `docs/verification/batches/BATCH-G31-08-FG41-SAMEDAY-INCREMENTAL-PLAN.md` | 新增：批次计划 |
| `docs/verification/results/BATCH-G31-08-FG41-SAMEDAY-INCREMENTAL-RESULT.md` | 新增：本文件 |
| `docs/decisions/DECISION_LOG.md` | 追加：D-045 |

（其他工作树在途改动属先前批次，与 G31-08 无关，提交时不得混入——D-044⑤。）

## 6. 下一步

- **G31-09（第二批）**：D-041 checkpoint 列宽修复——追加式迁移 **V32**（V30/V31 已占用）加宽 `file_checkpoint.file_identity` 至 VARCHAR(255)；验证 = 同一 HDFS 文件重试不重读（3307 IT + 迁移脚本测试）。
- 两批闭环后：**D-044⑤ 合并重跑**受影响链路 + G31-07 终验重跑，替换限定通过候选中的两个拦截项，再报总控。
