# 批次结果 BATCH-G31-12 — 总控复核四项处置（G31-11 暂缓签收项修复）

- 执行日期：2026-09-26（当日收口）
- 计划：`docs/verification/batches/BATCH-G31-12-MASTER-REVIEW-FIXES-PLAN.md`
- 决策登记：**D-050**（`docs/decisions/DECISION_LOG.md`）；G31-11 状态按总控裁定改登记「主要功能已实现，复核发现问题待修，最终签收暂缓」
- 隔离栈证据根：`target/v25-it/g3112iso_20260926_192745/`（driver outcome **PASS**，platform LEFT RUNNING；发布型腿全程隔离栈，未上正式栈）
- 结论先行：**计划判据 1–8 全 PASS**。总控四项复核意见全部处置完毕，报总控请求**解除 G31-11 暂缓签收**。真实 LLM（G31-06 仍 BLOCKED）与远程集群按总控指令继续独立登记，本批不并入。

---

## §1 判据→证据映射（计划 §1 的 8 条）

| # | 判据（计划钉死） | 结果 | 关键证据 |
|---|---|---|---|
| 1 | V33 迁移可重复：夹具 IT 从 V32 数据态升级全绿（≥3 用例）＋ 静态门禁断言新写法 ＋ 权威 checksum 落档 ＋ 正式库重锚 | **PASS** | `V33__pipeline_batch_consumption.sql` 重写（GROUP BY 聚合 + INSERT IGNORE；原自指 ODKU 注释留痕永不执行）；`PipelineBatchConsumptionUpgradeMySqlIT` **3/3**；`PipelineBatchConsumptionMigrationScriptTest` **5/5**；权威 checksum **`535846146`**；正式库 flyway 33 行 success=1 @ `535846146`（credref 通道） |
| 2 | 重算负例（单测）：A 清单缺失、同源 B 待处理 → FAILED `RUN_RECALC_BATCH_UNAVAILABLE`、B 未消费、ACTIVE 不变、`input_batch_id` 保持 A；warehouse-pipeline 全绿 | **PASS** | `PipelineServiceTest` 新增负例；模块 **48/48** 绿 |
| 3 | 腿③ FIFO 两批同待处理：B1+B2 先摄取完（台账空/SNAPCNT 0）→ 连续两次流水线 A→B 零遗漏 | **PASS** | driver legs `leg3-pre`/`run1`/`run2`（§2 三） |
| 4 | 腿④ 正常修复流程：jar 移走 → run FAILED → 输入校验和前后全等 → jar 验真还原 → 原 run 重试 SUCCESS | **PASS** | driver legs `checksum-cp1/2/3`（三联全等）+ `run4`/`run4-fixed`（§2 四） |
| 5 | 腿⑤ 重算负例（真实链路）：目标批 1 manifest 移除 → recalculate → FAILED、`input_batch_id` 保持 1、批 4 未消费、ACTIVE 不变 | **PASS** | driver legs `manifest1-removed`/`run5-recalc-negative`（§2 五） |
| 6 | 基线：全 reactor `F=0 E=0`（S≤2 已知环境项），计数相对 1169 只增不减（本批 +1 → 1170） | **PASS** | **1170**（F=0 E=0 S=2；+1 = 重算旁路负例） |
| 7 | 措辞红线：限 WSL 单节点；不证远程/真实 LLM；发布型腿不上正式栈；腿④ 属环境层临时故障、不表述为「数据订正功能已建成」 | **PASS** | 全文遵守（见 §5） |
| 8 | 红线：3306 零接触；`V25_IT_*` 零落盘/零 argv/零 git；仅本地分组提交；3307 root 仅 credref；`*.bak-*` 永不入提交组 | **PASS** | 全程遵守（见 §5） |

---

## §2 总控四项逐条对答

### 一、复核第 1 项：V33 可重复升级（闭合「从已有 V32 数据正常升级」）

**改法**（`analytics-server/platform-app/src/main/resources/db/meta/V33__pipeline_batch_consumption.sql`）：

1. 回填语句重构为**按构造去重**：先对 `(source_id, input_batch_id)` GROUP BY 聚合（`COUNT(*)` = publish_count、`MIN/MAX(r.id)` 给出 first/last 消费 run），再 `INSERT IGNORE` 落行。语句结果集内不存在重复键 ⇒ 不会复现 MySQL 1567；重复执行或与既有行（含人工补偿行、PIPELINE 行）撞键 ⇒ IGNORE 跳过，**既有行绝不改写**。
2. 首版 `ON DUPLICATE KEY UPDATE id = pipeline_batch_consumption.id` 自指语句——1567 根因——**注释保留在文件内（「已触发 1567，仅留痕，不执行」）**，不删除、不改写。
3. 文件头**事故与重构记录**：登记 1567 事故成因（同批多次成功发布 ⇒ INSERT..SELECT 结果集内部自撞，ODKU 无法消解）、现场补偿原貌（「人工将 history 行置 success=1，并按既成事实逐行手工回填（sql-repair2-backfill.sql）；**不算『Flyway repair 已修复迁移』**」）、INSERT IGNORE 代价（数据类错误降级为告警——源/目标列均为受控字面量与直拷列，风险可忽略，故接受）。

**验证**：

- 夹具升级 IT `platform-app/src/test/java/com/graduation/analytics/migration/PipelineBatchConsumptionUpgradeMySqlIT.java`：从 **V32 历史数据态**起 Flyway 升级到 V33，**3/3 绿**：
  - `upgradeBackfillsLedgerWithSameBatchMultiPublishAggregated`——**同批多次成功发布**形态（镜像正式库批 24 被 run22/run23 先后发布）：聚合一行、publish_count=2、consumed_by=最新 run、first_consumed=最早 run、consumed_at=最早 finished_at，字段口径与人工补偿完全一致；FAILED / 无批次 run 不入账；
  - `backfillReexecutionIsIdempotent`——回填重复执行两次，行数与逐字段值不变；
  - `backfillDoesNotOverwritePreexistingRow`——预置「既有行」（publish_count=99 + 独有快照标记）后回填撞键跳过，既有行**逐字段保持**。
- 静态门禁 `PipelineBatchConsumptionMigrationScriptTest` **5/5**：新增断言回填为 `INSERT IGNORE` + GROUP BY、**禁自指 ODKU**；保留 V33 版本唯一、UK (source_id,batch_id)、recalc_reason 单列可空、语句数与 3306 冻结声明等既有断言。
- **权威 checksum：`535846146`**（原首版 `-75448211` 留痕于文件头与 D-049/D-050）。
- **正式库 live 重锚**（仅 3307 `analytics_meta`，credref 通道，3306 零接触）：`flyway_schema_history` version='33' 行 success=1、checksum 更新为 `535846146`；无数据行改动（回填事实以人工补偿脚本为准，未重放）。重锚后正式库与仓库 V33 源文件一致，「未来批正常升级」路径闭合。

**措辞更正（总控原话对答）**：报告不再出现「Flyway repair 已修复迁移」。事实是：`flyway repair` CLI **从未使用**；success=1 系**受控手工 SQL UPDATE 直接置位**（`UPDATE flyway_schema_history SET success=1 WHERE installed_rank=32 AND version='33' AND success=0`）+ 显式 VALUES 逐行回填（原件 `target/v25-it/g3110_20260925_191753/evidence-g3111/sql-repair2-backfill.sql`，已归档 v3-archive/g3111）。原 INSERT..SELECT 语句现场**从未成功执行**（其同语句 UK 冲突 + 自指更新正是 1567 根因）。

### 二、复核第 2 项：显式重算目标批次检查可旁路 → 修复 + 单测负例

**改法**（`analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/PipelineService.java`，G31-12 D-050② 标记）：

1. 进入选择前**冻结原请求批次**：`final Long requestedBatchId = run.getInputBatchId();`（run 行由此刻起不再被提前写值）。
2. **受控写回**：仅当选择器选中的批次与冻结值一致（或冻结值缺失的普通路径）才允许 `run.setInputBatchId(...)` + `updateById`；选中值与请求值不一致时**不写回**，run 行保持原请求批次。
3. WAIT_LANDING fail-closed 判定改用**冻结值**比较：`isRecalc && requested > 0 && selectedBatchId != requested` → 抛 `RUN_RECALC_BATCH_UNAVAILABLE`（「重算目标批次 X 不可选（实际选中 Y）」）。由此消除「先把选中批次写进 run.inputBatchId、再拿它与选中批次比较（B==B 恒真）」的旁路。

**单测负例**（`PipelineServiceTest`）：A 有历史 run、A manifest 缺失、同源 B 待处理 → recalculate{batchId=A} → run **FAILED `RUN_RECALC_BATCH_UNAVAILABLE`**、**B 未被消费**（台账不变）、**ACTIVE 不变**、run 行 **`input_batch_id` 保持 A**（未被改写为回落值 B）。warehouse-pipeline 模块 **48/48** 绿。

**真实链路负例**（腿⑤，见 §2 五）以同形态在隔离栈闭环。

### 三、复核第 3 项：FIFO 真实链路证据——「两批同时待处理」（腿③）

总控指出：原驱动是 B1 发布成功后才摄取 B2，旧「取最新」实现也能通过，不构成 FIFO 证明。本批补测序列（RunId `g3112iso_20260926_192745`）：

1. **先摄取完两批**：ingB1（batchId 1，`ing-20260926193219-c499e034`，50 行）、ingB2（batchId 2，`ing-20260926193219-04607d13`，50 行）连续完成，manifests 1/2 均 **READY**。
2. **执行前断言两批均未消费**（`leg3-pre`）：`manifestsReady:[1,2]`、`consumptionEmpty:true`、`activeSnapshots:0`、`SNAPCNT|0`——此时任何选择器都无从依据历史行为分流。
3. **连续执行两次流水线**：
   - run1（幂等键 `g3112iso-run1-193220`）：`RUN|1|SUCCESS|1|S20260901_1|-|1|-` + 台账恰一行 `CONS|1|1|1|S20260901_1|1|0|-|PIPELINE`——**取最旧批 1**，批 2 仍 READY 待处理（waitLanding evidence batchId=1）；
   - run2（幂等键 `g3112iso-run2-193557`）：`RUN|2|SUCCESS|2|S20260901_2|-|1|-` + 台账两行 `CONS|1|…`、`CONS|2|2|2|S20260901_2|1|0|-|PIPELINE`。
4. 消费顺序 **1→2 连续零跳批**；若实现为「取最新」，run1 必取批 2，该序列必败——判据成立。既有选择器单测按计划保留。

### 四、复核第 4 项：正常修复流程证据——环境临时故障解除后原 run 重试成功（腿④）

总控指出：g3111 驱动「修复后重试成功」使用了直接改写 accepted 文件的手段，只能证明「人工订正数据后计算可恢复」，不能作为正常数据修复流程的验收证据。本批补测（**输入文件与 manifest 全程零接触**）：

1. **B3 摄取**：batchId 3（`ing-20260926193941-95bf2165`，50 行，READY）。
2. **校验和清单①**（`Get-InputChecksums`，2 个文件：`accepted/3` + `manifests/3.json`）：签名 `c27df0d7090c75854d97096f160cbbc33a976f7652490554b2926ef6fa4b87a0`（存档 `evidence/input-checksums-b3-cp1-before-fault.txt`）。
3. **注入环境层临时故障**：spark-jobs jar 改名 `.fault-g3112`（`RENAME_SPARK_JAR`；平台输入零接触）。
4. **执行流水线**：`RUN|3|FAILED|3|S20260901_3|RUN_JOB_FAILED|1|-`，failedStage=**INIT_SCHEMA**；`input_batch_id=3` 正确钉批；**台账仍 {1,2}**、**ACTIVE 仍 S20260901_2**（失败 run 零发布零台账）。
5. **校验和清单②**：签名 == 清单①（`c27df0d7…`，`evidence/input-checksums-b3-cp2-after-failed-run.txt`）——**故障期间输入未变**。
6. **解除故障**：jar 还原，`restoredSha256 = 71c0fc88b1c093df3e2e828d0c00b827a5bd51bca1d0ca0ab4a378e37bc00e57`（与 G31-08/G31-10 以来钉档的 spark-jobs jar SHA 一致，验真非重造）。
7. **原 run 原地重试**（retry-from-stage，attempt 2，理由 `G31-12-env-fault-cleared-retry-same-run`）：`RUN|3|SUCCESS|3|S20260901_3|-|2|G31-12-env-fault-cleared-retry-same-run` + `CONS|3|3|3|S20260901_3|1|1|G31-12-env-fault-cleared-retry-same-run|PIPELINE` + **ACTIVE → S20260901_3**。
8. **校验和清单③**：签名 == 清单① == 清单②（`evidence/input-checksums-b3-cp3-after-success.txt`）；driver 断言 `checksumsInvariant: true`。三份清单文件 SHA256 实测全等。

**结论限定**：腿④ 证明的是「**环境层临时故障**解除后，原 run 依原输入校验和不变地重试成功」；jar 故障不是数据故障，本腿**不表述为「数据订正功能已建成」**。g3111 直接改文件实验按原貌保留（G31-11 结果 §9②：人工订正数据后计算可恢复，不作为正常修复流程验收证据）。

### 五、腿⑤：重算目标批次校验真实链路负例（与 §2 二同形态）

1. **B4 摄取**：batchId 4（`ing-20260926194328-2423c2eb`，50 行，READY 待处理）；此时台账 {1,2,3}。
2. **移走目标批 1 的 manifest**（`landing/manifests/1.json` → `evidence/leg5-manifest1-moved.json` 原件保全，SHA256 `fbdb8c4ae1bee19645fc3c4ac5b9764aa5d05ab3eb8483ce5a86393ec62e52ec`）；批 4 READY 待处理——构成总控设定的旁路温床（旧实现会先写 input_batch_id=4 再「4==4」放行）。
3. **POST recalculate{batchId=1, reason=…}**：run（DB id 4）`RUN|4|FAILED|1|S20260901_4|RUN_RECALC_BATCH_UNAVAILABLE|1|G31-12 负例：目标批 1 清单缺失，必须拒绝而非改换输入`。
4. 判据逐项：**`input_batch_id` 保持 1**（未被改写为回落值 4）；**批 4 未被消费**（台账仍 {1,2,3}，`CONS` 三行与腿④ 终态全等）；`batch4ManifestStillReady=true`（批 4 manifest 未被消费动作污染）；**ACTIVE 不变 S20260901_3**。fail-closed 成立。

> 注：FAILED run 行携带的 `target_snapshot_id`（S20260901_3/S20260901_4）是 run 创建时预分配的目标槽位，非已发布快照（SNAPCNT/ACTIVE 均未移动），与既有行为一致，非缺陷。

---

## §3 测试基线

| 时点 | 计数 | 说明 |
|---|---|---|
| G31-11 终态 | 1169 | F=0 E=0 S=2 |
| 本批 +1 | **1170** | PipelineServiceTest 重算旁路负例 |
| 本批终态 | **1170** | 全 reactor `F=0 E=0 S=2`（S=2 为既有已知环境项，口径不变） |

V33 相关验证均在 platform-app/warehouse-pipeline 模块内（夹具 IT 起独立 3307 per-run 库，不污染基线计数口径）。

---

## §4 偏差登记

本批**无计划外偏差**。三点如实说明：

1. **V33 重锚范围**：正式库重锚仅 UPDATE `flyway_schema_history` version='33' 单行（success/checksum 两字段），**未重放回填语句**——回填事实以人工补偿脚本既成事实为准（批 22 publish_count=1、批 24 publish_count=2 快照 S20260901_23），重放无意义且违反「不擅改数据」纪律。
2. **driver 腿名 vs DB run id**：腿名 run1/run2/run4/run5 对应 DB run id 1/2/3/4（run3 槽位被 g3111 时代命名占用是驱动内部命名习惯，DB 记录连续无空洞）。
3. **FAILED run 预分配 target_snapshot_id**：见 §2 五注，既有行为，非缺陷，留痕防复核误读。

---

## §5 边界与红线（判据 7/8 落实）

- **环境限定**：全部结论限**当前 WSL 单节点环境**；不证明远程集群/共享 HMS/YARN；真实 LLM 继续独立登记（G31-06 仍 BLOCKED，stub LLM 链路不冒充）。
- **发布型腿不上正式栈**：腿③④⑤全部发布动作在隔离栈 `g3112iso_20260926_192745`（per-run 3307 隔离库对）；正式栈本批仅 flyway 单行重锚，无 run、无发布、无快照变动，**ACTIVE 唯一锚 S20260901_23 不漂**。
- **3306 永久冻结零接触**：本批零连接；V33 文件内 3306 冻结声明由静态门禁钉住。
- **3307 root 仅 credref 通道**（`credref-mysql3307-root.properties`，零落盘/零 argv/零日志/零 git）；`V25_IT_*` 口令同纪律。
- **仅本地分组提交**（push 授权已用尽）：按 §6 归属分组，显式路径，绝不 `-A`/bulk-tree；`*.bak-20260926-g3112` 四件备份（含编辑前原文）**永不提交**；G31-10/D-048 等在途无关改动文件不触碰。
- **不擅改历史**：G31-11 结果文档按「正文原貌保留 + §9 补记为准」处理，仅两处**行内更正标记**（G31-12 更正括注）就地追加，不删改原句；历史提交零改写（D-048 延续）。

---

## §6 提交归属（仅本地提交，分组）

**组① 代码（G31-12 四文件改 + 二新增）**：
- `analytics-server/platform-app/src/main/resources/db/meta/V33__pipeline_batch_consumption.sql`（改：去重构造重写 + 事故记录）
- `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/PipelineService.java`（改：冻结-比较-受控写回）
- `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java`（改：+重算旁路负例）
- `analytics-server/platform-app/src/test/java/com/graduation/analytics/migration/PipelineBatchConsumptionMigrationScriptTest.java`（改：门禁收紧）
- `analytics-server/platform-app/src/test/java/com/graduation/analytics/migration/PipelineBatchConsumptionUpgradeMySqlIT.java`（**新增**：V32→V33 夹具升级 IT）

**组② 文档**：
- `docs/verification/batches/BATCH-G31-12-MASTER-REVIEW-FIXES-PLAN.md`（新增）
- 本结果文档（新增）
- `docs/verification/results/BATCH-G31-11-M3-PUBLISH-SEMANTICS-RESULT.md`（§9 补记 + 两处行内更正标记）
- `docs/verification/CURRENT_BATCH.md`（G31-12 顶块 + G31-11 降级改登记）
- `docs/PROJECT_STATUS.md`（G31-12 顶节 + 偏差③更正）
- `docs/decisions/DECISION_LOG.md`（D-049 子项更正 + D-050）

**排除**：`run-tests.ps1`（MIXED，G31-11 已登记不入组）、`*.bak-*`、`target/`、`.zcode/`、G31-10/D-048 在途文件。

---

## §7 下一步

**报总控：请求解除 G31-11 暂缓签收**（四项复核意见处置完毕，判据 1–8 全 PASS）；`v3-archive/g3112` 归档义务（D-048 口径：源码快照 + 脱敏证据 + git bundle + SHA256 清单；DB 备份受控不上传）。签收权归总控。
