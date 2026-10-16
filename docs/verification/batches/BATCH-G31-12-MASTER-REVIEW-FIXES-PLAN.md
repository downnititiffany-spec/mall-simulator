# 批次计划 BATCH-G31-12 — 总控复核四项处置（V33 可重复升级 + 重算目标校验旁路修复 + FIFO 两批同待处理证据 + 正常修复流程证据）

- 批次：G31-12
- 日期：2026-09-26
- 指令来源：**总控对 G31-11「判据 1–8 全 PASS」的复核裁定（2026-09-26）**——"主要功能已实现，复核发现问题待修，最终签收暂缓"；已完成能力与归档（333/333 SHA256、git bundle、源码快照）接收；G31-10 已解除的两项拦截保持解除。**下一批仅覆盖：迁移方案修正与升级验证、重算目标校验、两项小样本补验；真实 LLM、远程集群继续独立登记。**
- G31-11 状态改登记：**"主要功能已实现，复核发现问题待修，最终签收暂缓"**（CURRENT_BATCH / PROJECT_STATUS 同步改口；G31-11 结果文档以 §9 补记更正两处不准确表述、正文原貌保留）。
- RunId：`g3112iso_20260926_192745`（隔离单栈，四金样 50 行批 B1–B4；attempt 根 `target/v25-it/g3112iso_20260926_192745/`）。
- 数据库：3307 隔离库对 `g3112iso_20260926_192745_%`（prep 重置）；3307 root 仅经 credref 通道；**3306 全程零接触**。
- jar 锚点：platform `ae909a24…`、spark-jobs `71c0fc88…`（与本批构建台账全等，legs 不重建 jar——修复面全部可在现有二进制语义下验证，除 V33 SQL 与 PipelineService，见 §2 隔离说明）。

---

## §0 本批设计裁定（先决策后记录；收口时以 D-050 登记 DECISION_LOG）

- **D-050① V33 可重复升级**：重写 `V33__pipeline_batch_consumption.sql` 回填语句为**去重构造**（`INSERT IGNORE` + `GROUP BY (source_id,batch_id)` 聚合：`consumed_by_run_id=MAX(run_id)`、`first_consumed_by_run_id=MIN(run_id)`、`target_snapshot_id` 取最大 run 对应快照、`publish_count=COUNT(*)`），消除 1567 根因（`ON DUPLICATE KEY UPDATE id=…` 非法自指）；原 ODKU 语句保留为注释。文件头记录**原 checksum `-75448211`**（现场补偿态）与权威新 checksum；**不擅改历史提交**（D-048），修复以工作树新提交落账。**升级验证 = 小型历史夹具 IT**：V32 时代库（run 表含 SUCCESS + 双非空 run，**含同批两次成功发布的批 24 形态**）→ 起 flyway 到 V33 → 断言台账行数/聚合列/EXPECTBF 语义。正式库 live 重锚（旧值 `-75448211` → 新值，success=1 保持）经 credref 通道执行并留 SQL 证据。
- **D-050② 重算目标批次校验旁路修复**：`PipelineService` 执行路径**先冻结本 run 原请求批次**（`recalculate()` 预置值 / 重试首跑写入值），选择输入后**仅当选中批次 == 冻结值才写回 `run.input_batch_id`**；不等（钉住清单缺失、选择器 FIFO 回落他批）时**保持原值不写**，交 WAIT_LANDING 重算断言按冻结值比对 fail-closed（`RUN_RECALC_BATCH_UNAVAILABLE`，消息含"重算不得静默改换输入批次"）。修复"先写选中值再比较 B==B"的旁路。单测负例 + 真实链路腿⑤。
- **D-050③ FIFO 证据形态**：真实链路证据必须先让 **A、B 两批同时待处理**（两批摄取完成后断言台账为空、两批均 READY 未消费、零快照），再连续执行两次流水线 → run1 必取 A（最旧）、run2 取 B。旧"取最新"实现在该序列下必然失败，方可构成 FIFO 证明；既有选择器单测保留。
- **D-050④ 正常修复流程证据**：腿④以**环境临时故障**（job jar 移走 → RUN_JOB_FAILED/RUN_INTERNAL，不触碰数据）验证：故障前后**输入文件与 manifest 校验和逐一不变**（`Get-InputChecksums` 前后两次 SHA256 清单+总签名）、恢复 jar（SHA 验真）后**原 run 原地 retry-from-stage 成功**、消费台账与 ACTIVE 语义不变。g3111 直接改写 accepted 文件的实验按原貌保留、结论限定（G31-11 结果 §9②）。

## §1 判据（钉死；任何一条不满足即停止并记录现场）

1. **V33 迁移可重复**：夹具 IT 从"V32 数据态"升级到 V33 全绿（≥3 用例：单发布批 / 同批多次成功发布批 / 幂等重复执行不炸不重）；静态门禁测试断言新写法（禁自指 ODKU）；权威 checksum 落 DECISION_LOG D-050；正式库 flyway 33 行 success=1 @ 新 checksum（credref 通道复核 SQL 留证）。
2. **重算负例（单测）**：同源 B 待处理、目标 A 清单缺失 → 重算 run FAILED `RUN_RECALC_BATCH_UNAVAILABLE`、B 未消费、ACTIVE 不变、run 行 `input_batch_id` 保持 A。warehouse-pipeline 模块全绿。
3. **腿③ FIFO 两批同待处理**：B1+B2 摄取完 → `CONS` 台账空 + `SNAPCNT|0` → run1 SUCCESS `input_batch_id=1`、消费 {1}、批 2 仍 READY 待处理 → run2 SUCCESS `input_batch_id=2`、消费 {1,2}。顺序 A→B 零遗漏。
4. **腿④ 正常修复流程**：B3 摄取 → 校验和清单1 → 移走 spark-jobs jar → run FAILED（`RUN_JOB_FAILED`/`RUN_INTERNAL` 之一，failedStage 非空）→ 校验和清单2 **与清单1 全等** + 台账 {1,2} + ACTIVE 不变 → 还原 jar（SHA 验真 `71c0fc88…`）→ 原 run 原地 retry-from-stage → SUCCESS `input_batch_id=3`、快照 S20260901_run4、台账 {1,2,3}（publish=1、recalc_count≥1 允许 flake 重试）→ 校验和清单3 仍全等。
5. **腿⑤ 重算负例（真实链路）**：B4 摄取（台账 {1,2,3}）→ 记录 manifests/1.json SHA → **移走**批 1 清单至 evidence（原件保全）→ POST recalculate{batchId=1} → run FAILED `RUN_RECALC_BATCH_UNAVAILABLE` → run 行 `RUN|…|FAILED|1|`（**input_batch_id 保持 1，未被改写为回落值 4**）→ 台账仍 {1,2,3}（B4 未被消费）→ ACTIVE 不变 → manifests/4.json 仍 READY。
6. **测试基线**：改动后 analytics-server 全 reactor `F=0 E=0`（S≤2 已知环境项按既有口径登记），计数相对 G31-11 终态 1169 只增不减（本批 +1 重算负例 → 1170）。
7. **措辞红线**：全部结论限当前 WSL 单节点环境；不证明远程集群/真实 LLM（G31-06 仍 BLOCKED）；发布型腿不在正式栈重跑；腿④的 jar 故障属**环境层临时故障**，不表述为"数据订正功能已建成"。
8. **红线**：3306 永久零接触；V25_IT_* 口令零落盘/零 argv/零 git；push 授权已用尽 → 仅本地提交（分组提交、绝不 bulk-tree）；3307 root 只走 credref 通道；`*.bak-*` 永不入提交组。

## §2 改动面

| 对象 | 预期 |
|---|---|
| `analytics-server/platform-app/src/main/resources/db/meta/V33__pipeline_batch_consumption.sql` | 回填语句去重构造重写（INSERT IGNORE + GROUP BY），原 ODKU 注释保留，头部记录新旧 checksum |
| `warehouse-pipeline/.../PipelineService.java` | 冻结原请求批次 + 校验相等才写回 + WAIT_LANDING 按冻结值比对（D-050②，G31-12 标记注释） |
| `warehouse-pipeline/.../PipelineServiceTest.java` | +1 重算旁路负例（清单缺失回落他批 → FAILED、input_batch_id 保持原值、他批不消费） |
| `platform-app/src/test/.../migration/PipelineBatchConsumptionUpgradeMySqlIT.java`（新增） | V32 历史夹具 → V33 升级 IT（含同批多次成功发布形态） |
| `platform-app/src/test/.../migration/PipelineBatchConsumptionMigrationScriptTest.java` | 静态门禁：禁自指 ODKU、断言 INSERT IGNORE + GROUP BY、checksum 记录行存在 |
| `analytics-server/db/meta/V33` 夹具/资源 | fixture JSONL/SQL（V32 态最小历史） |
| `g3112-driver.ps1`（新增，不入产品提交组） | 腿③④⑤ 证据驱动（clone g3111 骨架 + Get-InputChecksums / Assert-ManifestReady / fault 移 jar / recalc 负例） |

## §3 执行序

1. 修② PipelineService + 单测（模块级绿）→ 2. 修① V33 重写 + 门禁 + 夹具 IT（IT 绿、权威 checksum 定版）→ 3. 3307 正式库 live 重锚（credref）→ 4. 全 reactor 基线 → 5. g3112 驱动 `-Confirm -Fresh`（腿③→④→⑤）→ 6. 收口：D-050 登记、G31-11 §9 补记、CURRENT_BATCH/PROJECT_STATUS 改口、分组本地提交、`v3-archive/g3112` 归档 → 7. 报总控解除暂缓签收。
