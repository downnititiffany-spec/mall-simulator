# BATCH-G31-08 — 同日增量写入修复（F-G4-1/D-040，分区作用域 读-合并-去重-覆写）

- **批次**：G31-08（D-044 裁定的两短批次之**第一批**；非 V3.1 §7 原生批次序列成员）
- **日期**：2026-09-25（机器证据时钟）
- **状态**：执行中（按自主决策授权推进并逐条登记；**不 commit 不 push**）
- **被测对象**：工作树未提交改动上的 `spark-jobs`（`EventOdsLoadJob` + `OdsLoadSql`）

## 0. 计划依据与诚实登记（必须先读）

1. **D-044（总控裁定，2026-09-25 登记 DECISION_LOG）**：① F-G4-1 与 D-041 先修复并做小规模针对性回归，本版产品验收暂不签「限定通过」；④ 两个短批次顺序：**先修同日增量写入**（验证「原有数据保留＋新增数据计入＋重跑不重复」），再修 checkpoint 列宽与索引约束（V32）；**硬约束：仅开启动态分区覆盖不足以保证同一分区内旧记录不被覆盖，修复方案必须覆盖这一点**；⑤ 修完后**合并重跑**受影响链路 + G31-07 终验，不必每改一次重跑全部大规模测试。
2. **F-G4-1 / D-040（G31-04 发现并登记）**：`EventOdsLoadJob` 以 `OdsLoadSql` 四模板 `INSERT OVERWRITE TABLE … PARTITION (dt, hour)` 写 ODS，且从未设置 `spark.sql.sources.partitionOverwriteMode` ⇒ Spark 默认 **STATIC 模式 = 整表覆写**，分区谓词不裁剪覆写范围；同日第二批增量落地后历史被清空（G31-04 run2 实测：1 benign 行 ⇒ 3 张 ODS 表 parquet 文件数归零）。原 L121 注释「INSERT OVERWRITE 幂等：分区内重跑内容相同」对**跨批次**不成立，本批一并纠正（连同类文档第 5 条）。
3. **V3.0 §10.3 第 4 条（重跑幂等语义）**：被违反的原权威条款——修复后幂等语义必须在「分区内」与「跨批次」两个维度同时成立。
4. **V3.0 L212 + L243**：代码 Agent 不可自行宣布完整验收 ⇒ 本批证据 = 模块级回归 spec（测试域 in-memory catalog）；「在产 Hive metastore 上成立」的验证归 D-044 ⑤ 的合并重跑，不在本批宣称。

## 1. 修复设计（分区作用域 读-合并-去重-覆写）

对四张 ODS 表逐表执行（`OdsLoadSql` 拆出共享 SELECT、新增合并模板；`EventOdsLoadJob` 改写写出口）：

1. `newRows` = 现行 SELECT 模板输出（新函数 `selectFromLanding`；四条 `*FromLanding` 模板**字节不变**，仅供既有契约测试继续钉住）；
2. `hits` = `newRows` 的 distinct (dt, hour) —— 本批命中的分区；
3. `staleExisting` = 目标表 **left_semi join `hits` on (dt, hour)**（只取命中分区里的旧行）再 **left_anti join `newRows.event_id`**（同 event_id 旧副本让位新批 = new-wins）；
4. `merged` = `newRows.unionByName(staleExisting)`；
5. 写出 = `mergeInsertOverwrite`：`INSERT OVERWRITE TABLE … PARTITION (dt, hour) SELECT <OdsV2Columns.columnNames 显式列序> FROM merged`（列序由唯一所有者给出，按名投影）；**仅在写期间**设 `partitionOverwriteMode=dynamic`，try/finally 还原（防跨作业配置泄漏）；
6. `newRows` 为空 ⇒ 跳过写出（全拒绝/无本主题事件 = 零写入）；表不存在 ⇒ 让 Spark 报错响（schema 由 `LocalSchemaInitJob` 负责，不做静默兜底）。

性质（对应判据）：非命中分区物理不动；命中分区旧行保留（动态覆盖**＋合并**——直接回应 D-044 硬约束）；新增计入；同批重放 anti-join 幂等（批内不去重，重放稳定性由 anti-join 保证）；重投递 event_id 新值换旧值且 `ingest_batch_id` 取新批；下游零改动（`BehaviorDwdJob` 读全分区、`TradeDwdJob` 读全表后按 event_id 去重，ODS 层修复即全链正确）。

## 2. 回归 spec 判据（新增 `OdsMergeIncrementalSpec`）

测试域：**独立 SparkSession，不预置 `partitionOverwriteMode`**（与 `P2TestSupport.spark` 不同——它预置了 dynamic，会掩盖「作业自己设/还原」这一事实；beforeAll 断言该 conf 为 None）；warehouse `D:/Develop/tmp/g31-08-warehouse/<runId>`、landing `D:/Develop/tmp/g31-08-landing/<runId>`（只新建不删除）；in-memory catalog + `USING parquet`（`spark-hive` 为 provided，**测试域 ≠ 在产 Hive metastore**，报告登记）。夹具从 golden 克隆（只读不改）：改 `event_id` 克隆行保持原 `event_time` ⇒ 与原行**同 (dt,hour) 分区**（G4 判别力的来源）；另造一条 hour=23 克隆验证非命中分区。

批次序：A 基线（4 behavior + 2 order_created 原始 golden 行）→ B 增量（A 的克隆换新 id 同分区 + 1 条 hour=23 克隆）→ **B 同参重放** → C 重投递（B 全部行重投 + 2 条新 behavior 克隆）→ D 单主题（1 条 user）→ E 全拒绝（2 条 schema_version='2.0'）。

- **G1 原有数据保留**：B 后各表 event_id 集 ⊇ A 的集、行数不回退；
- **G2 新增计入**：B 后各表 event_id 集 = A ∪ B、分区行数 = A 图 ＋ B 贡献图（分区间全可加）；
- **G3 重跑不重复**：B 同参重放后，event_id 集与分区行数图**逐位相等**；
- **G4 同分区旧记录并存（硬约束判别测试）**：B 克隆行与 A 行同 (dt,hour)，B 后共享分区行数 = A 行数 ＋ B 行数；断言共享键集**非空**防夹具退化成纯跨分区假绿——本条在修复前（STATIC 整表覆写）必红；
- **G5a 单主题批次不清他表**：D 后其余三表快照逐位不变（旧实现此处为空 SELECT 静态覆写 = 清表，必红）；**G5b 全拒绝批次零写入**：E 后四表快照逐位不变；
- **new-wins 钉死**：C 重投递后同 id 行 `ingest_batch_id` = C 批号（103），且四表 event_id 零重复（anti-join 去重证据）；
- **G6 conf 还原**：全批结束后 `spark.sql.sources.partitionOverwriteMode` 回到未设置（None）。

## 3. 执行顺序（小样本窄测试先行）

1. 本计划落盘；2. `OdsLoadSql` 重构（字节保持：抽 `topicWhere`/`selectFromLanding`，新增 `ColDt`/`ColHour`/`ColEventId`/`mergeView`/`mergeInsertOverwrite`）＋ `EventOdsLoadJob` 合并写出口（含两处过时注释纠正）；3. 新 spec；4. **先单跑新 spec**（`mvn -Dtest=OdsMergeIncrementalSpec`，JDK8）迭代到绿；5. 全 spark 套件（`scripts/run-tests.ps1 -Suite spark`，JDK8；基线 312 → 实际通过数并按惯例加注释）；6. 批次结果文档 ＋ `DECISION_LOG` D-045（修复语义＋证据边界）＋ `CURRENT_BATCH`/`PROJECT_STATUS`（每次改前备份原件）。

## 4. PASS 判据与边界

- **PASS**：新 spec G1–G6＋new-wins 全绿；既有 spark 套件全绿（模板契约 A9/A9d、`SqlTemplateSpec` 字节稳定性不破 = 四模板文本逐字节未动）；基线同步登记。
- **边界**：测试域 in-memory catalog ≠ 在产 Hive metastore（报告登记，归 D-044 ⑤ 合并重跑）；真库 3306 冻结零接触；本批不重跑全链路大规模测试；不 commit 不 push；不触碰 `D:\Develop_code\GraduationProject`（另一份项目目录）。
