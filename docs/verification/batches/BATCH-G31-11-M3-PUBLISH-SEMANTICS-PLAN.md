# 批次计划 BATCH-G31-11 — M3 发布语义收紧（consumed-input 识别 + 四场景验证 + 受影响终验）

- 批次：G31-11
- 日期：2026-09-26
- 裁决依据：**D-048 §(5)（总控 M3 语义裁定，2026-09-25）**——no-new-input → 不发布/no-op/ACTIVE 不变；消费状态**独立记录**（既非 manifest READY 单独判定、亦非值相等去重）；多待处理批次逐批处理不遗漏；失败重试绑定原批；显式重算入口且必须带理由；落地与四场景验证（无新输入不发布 / 有新输入能发布 / 失败重试仍有效 / 连续两个待处理批次不遗漏）+ 一次受影响终验。**真实 AI 与远程集群不并入。**
- 更正登记（随 D-048 §(5) 已落 DECISION_LOG，本计划只重述）：`PipelineService.java:641` **已有** F-88 发布前质量门；G31-10 结果 §5-1「发布无条件（publish 不经门禁）」表述不准——实际缺口在 **consumed-input 识别**（consumed manifest 保持 READY 且选择器重扫最新 READY → no-new-input 场景仍再发布），非「无质量门」。
- RunId：行为腿 `g3111iso_<HHMMSS>`（隔离单栈）；终验腿复用常驻正式栈 `g3110_20260925_191753`（新 jar 重启由本批新脚本执行）。attempt 根：`target/v25-it/g3111iso_<HHMMSS>/`
- 数据库：行为腿 = 3307 隔离库对 `g3111iso_<ts>_analytics_meta` / `_analytics_metric`；终验腿 = 正式库对 `stage7q1_20260918_152245_analytics_meta` / `_analytics_metric`（root 口令经 credref 通道 `credref-mysql3307-root.properties`，零落文档/argv）。**3306 全程零接触。**

---

## §0 本批设计裁定（先决策后记录，执行按此落地；批次收口时以 D-049 登记 DECISION_LOG）

- **D-049a 消费状态独立记录**：新表 `pipeline_batch_consumption`（analytics_meta，Flyway V33），唯一键 `(source_id, batch_id)`。列：`status`（CONSUMED）、`consumed_by_run_id`、`first_consumed_by_run_id`、`target_snapshot_id`、`publish_count`、`recalc_count`、`last_recalc_reason`、`last_recalc_by`、`last_recalc_at`、`consumed_at`、`created_via`（PIPELINE / BACKFILL_V33）。**只在 PUBLISH_METRIC 成功（report.ok()）后写入/更新**；FAILED run 不写。它独立于 manifest READY（READY 是采集侧声明）与值相等去重（D-045 是快照内去重，不是批次消费判定）。
- **D-049b 选择语义 FIFO 化**：`LandingManifestSelector` 从「最新 READY 非空同源清单」改为「**最旧的未消费** READY 非空同源清单」（逐批处理不遗漏的语义基础）。重试/恢复的钉住路径不变且**钉住优先于 FIFO**；他源/不可归属 fail-closed 不变。空批次跳过不变。返回类型改为 `Selection`（manifest + `readyButConsumedCount`），使调用方能区分「无清单」（RUN_EMPTY_LANDING 维持）与「有清单但全部已消费」（no-op）。
- **D-049c no-new-input = no-op SUCCESS**：候选批已全部消费且本 run 非重算 → run 走完 WAIT_LANDING（证据 `{noNewInput:true, reason:"ALREADY_CONSUMED", batchId, consumedByRunId, readyButConsumedCount}`）后**直接 SUCCESS**，不执行 INIT_SCHEMA..PUBLISH_METRIC，不产快照，ACTIVE 不变；`input_batch_id` 留 NULL（如实）。**无任何 READY 清单仍维持 `RUN_EMPTY_LANDING` fail-closed**（配置/部署错误信号，语义拆分登记于结果文档）。no-op 不写消费行。
- **D-049d 失败重试绑定原批（已有行为，本批补验证 + 消费交互闭合）**：retry/resume 经 `manifestForRun` 钉住原批（现状保留）。新增边界：重试时钉住批**已被其他 run 消费** → no-op（不重复发布）。FAILED run 不写消费行 → 批次保持待处理，新 run 的 FIFO 也会再次选中最旧未消费批（= 同一批），两级路径一致收敛。
- **D-049e 显式重算入口（带理由）**：`POST /api/v1/admin/pipeline-runs/recalculate`，请求体 `{runtimeProfileId, batchId, operator, reason}`，`reason` 空白 → 400（与 resume/mark-failed 同审计口径）。实现：新 run 预置 `input_batch_id = batchId`（V33 给 `pipeline_run` 加 `recalc_reason VARCHAR(500) NULL`；非 NULL 即重算 run）→ `manifestForRun` 钉住源从「WAIT_LANDING 证据」扩展为「证据 ?? run.input_batch_id」→ 选择后**断言选中 batchId == 请求 batchId**（manifest 丢失等场景 → `RUN_RECALC_BATCH_UNAVAILABLE` fail-closed，绝不静默换批）；no-op 检查对重算 run 豁免（重算的目的就是再处理已消费批）；重算 run 的 WAIT_LANDING 证据带 `recalcReason`；消费行更新 `recalc_count/last_recalc_*`。
- **D-049f V33 含正式库回填**：`INSERT...SELECT` 从 `pipeline_run WHERE status='SUCCESS' AND input_batch_id IS NOT NULL AND source_id IS NOT NULL` 生成 `created_via='BACKFILL_V33'` 消费行（ON DUPLICATE KEY 幂等）。**否则新代码到达常驻正式栈后，历史已消费批（无消费行）会被当作未消费 → 再发布 → ACTIVE 漂移破坏锚点 S20260901_23**。执行前预检 `SUCCESS 且 (input_batch_id IS NULL OR source_id IS NULL)` 计数，预期 0，非 0 即登记偏差并停止回填。
- **D-049g 消费行写入点与崩溃窗口**：消费行写在 `metricPublisher.publish` 返回 ok 之后、阶段收尾之前；写入失败 → `RUN_CONSUMPTION_MARK_FAILED` → run FAILED（ACTIVE 已切换，如实登记）。已知崩溃窗口：发布成功与消费行落库之间进程死亡 → 批次仍记未消费 → 下轮重发同批（新快照同值，D-045 去重保证零重复计数，ACTIVE 前移一格）——登记为已知窗口而非缺陷。反向窗口（消费行已写、阶段收尾失败 → retry 命中 no-op）不产生重复发布。
- **D-049h 平台 UI 零改动**：裁决未要求 UI；消费状态可见性走 API/DB（运维查询面在结果文档给出 SQL）。

## §1 判据（四场景 + 终验，钉死；任何一条不满足即停止验收并记录原始现场）

1. **场景1 无新输入不发布**：候选 READY 批全部已消费后触发 run → run SUCCESS（no-op）、阶段证据 `noNewInput=true`、**零新快照行**、ACTIVE 不变、消费行零新增。隔离栈与正式栈各执行一次（正式栈一次兼作终验腿）。
2. **场景2 有新输入能发布**：未消费新批存在 → run 发布成功、ACTIVE 切换到新快照、消费行生成（`consumed_by_run_id`/`target_snapshot_id` 与 run/快照一致）。
3. **场景3 失败重试仍有效**：构造质量门必败批 → run FAILED（`PIPELINE_QUALITY_FAILED`）、**无消费行**、ACTIVE 不变；在存在更新未消费批 B3 的条件下 retry → **仍用原批**（钉住胜过 FIFO）、重跑后 SUCCESS、消费行落到原批。若 retry 换批 → 停止验收。
4. **场景4 连续两个待处理批次不遗漏**：B1<B2 两个未消费批连续入账 → run1 消费 **B1（最旧）**、run2 消费 B2，两行消费记录齐、两快照先后 ACTIVE、**无批次被跳过**。若 run1 选了 B2 → 停止验收（FIFO 判据直接证伪）。
5. **受影响终验**（正式栈，新 jar + V33 回填后）：触发一次 run → no-op、ACTIVE 仍唯一 `S20260901_23`；随后重跑 G31-07 终验中**不受发布影响**的腿：ACTIVE 唯一性、overview 指纹 == P6 oracle 钉档（14/14，容差 0.0005）、F2b 日志扫描（零 `:3306`、双 3307 URL + landing 有据）、stub LLM 探针、进程身份链。**发布型腿不在正式栈重跑**（会移动锚点）——其覆盖由隔离栈场景 2/3/4 承担，映射表落结果文档。
6. **措辞红线**：全部结论限定当前 WSL 单节点环境（本机 3307 + Spark local + 本地 landing），不证明远程集群/共享 Metastore；真实 AI 不并入（stub LLM 仅作栈健康探针）。终验登录与 AI 探针按设计产生审计/查询记录，不称「完全只读」。
7. **测试基线**：改动前先跑 analytics-server 全 reactor 记录基线计数；改动后全 reactor `F=0 E=0`（S≤2 已知环境项按 G31-09 口径登记）。单测新增/改动见 §2。
8. **红线**：3306 永久零接触；V25_IT_* 口令零落盘/零 argv/零 git；push 授权已用尽 → 仅本地提交（按批次归因清单分组，绝不 bulk-tree）；3307 root 口令只走 credref 通道。

## §2 改动面

| 对象 | 预期 |
|---|---|
| `db/meta/V33__pipeline_batch_consumption.sql`（新增） | ① `CREATE TABLE pipeline_batch_consumption`（D-049a）；② `ALTER TABLE pipeline_run ADD COLUMN recalc_reason VARCHAR(500) NULL`（D-049e）；③ SUCCESS run 回填 INSERT...SELECT（D-049f） |
| `warehouse-pipeline/entity/PipelineBatchConsumption.java` + `mapper/PipelineBatchConsumptionMapper.java`（新增） | MyBatis-Plus 实体 + mapper（照 IngestionBatch/FileCheckpoint 同款） |
| `LandingManifestSelector.java` | FIFO 化（D-049b）：注入消费 mapper，扫 READY 非空同源清单时跳过已消费批，取**最旧**未消费；返回 `Selection(manifest, readyButConsumedCount)`；钉住路径不变 |
| `PipelineService.java` | ① `manifestForRun` 钉住源扩展（证据 ?? input_batch_id，D-049e）；② no-op 分支（D-049c）——WAIT_LANDING 证据化后直接 SUCCESS；③ 重算断言选中批（`RUN_RECALC_BATCH_UNAVAILABLE`）；④ PUBLISH_METRIC 成功后写/更新消费行（D-049a/e/g），失败 → `RUN_CONSUMPTION_MARK_FAILED`；⑤ `recalculate(runtimeProfileId, batchId, operator, reason, traceId)` 入口（预置 input_batch_id + recalc_reason，审计照 resume 口径） |
| `entity/PipelineRun.java` | 加 `recalcReason` 字段（列由 V33 建） |
| `platform-app/controller/PipelineAdminController.java` | `POST /api/v1/admin/pipeline-runs/recalculate`（D-049e；reason 空白 400） |
| 测试 | `LandingManifestSelectorTest`（FIFO + 消费感知断言改写/新增）；`PipelineServiceTest`（no-op / 重算豁免 / 消费行写入 / 消费标记失败 / 重试命中已消费批 → no-op / 重算批不可用 fail-closed）；`platform-app` 新增 `PipelineBatchConsumptionMigrationScriptTest`（静态门禁，照 FileCheckpointIdentityWidthMigrationScriptTest 同款） |
| 平台 UI（platform-ui） | **零改动**（D-049h） |
| MetricPublisher / MetricPublishValidator | **零改动**（缺口在 pipeline 侧输入识别，不在发布器） |
| 新增脚本 | `target/v25-it/g3111iso_*/scripts/{g3111-driver.ps1, g3111-formal-restart.ps1}`（后者照 g3110-restart.ps1 模板换 attempt 路径与新 jar SHA；mini-gate 锚点 S20260901_23 随迁） |
| 文档 | 本计划、结果文档 `BATCH-G31-11-M3-PUBLISH-SEMANTICS-RESULT.md`、`DECISION_LOG.md`（D-049）、`CURRENT_BATCH.md`、`PROJECT_STATUS.md`（后四者编辑前备份 `*.bak-20260926-g3111`） |
| 3306 | **零接触**（永久红线） |

## §3 依据

- D-048（DECISION_LOG，2026-09-25）：M3 语义裁定原文 + F-88 更正登记（§(5)）。
- D-047 / G31-10 结果 §5：M3 偏差暴露现场（run3 以重放批再发布产出同值新快照 S_C；D-045 去重保证零重复计数）。
- 现状代码：`PipelineService.java`（`manifestForRun` :1041 钉住、WAIT_LANDING :466 RUN_EMPTY_LANDING、F-88 门 :641、PUBLISH :615-677）；`LandingManifestSelector.java`（`newestReadyOfSource` :77 最新 READY 语义）；`MetricPublisher.java`（ok 报告 :228）；`PipelineAdminController`（resume/mark-failed/retry-from-stage 审计口径）。
- 终验基座：G31-10 P7 终验 9 腿与 P6 oracle 钉档（attempt `target/v25-it/g3110_20260925_191753/oracle/`）；常驻栈重启模板 `scripts/g3110-restart.ps1`。
- 隔离栈模式：G31-09 iso run（`it-prepare-isolation.ps1 -RunId <id>`，单进程 V25_IT_* 通道）+ v26-f88 隔离链。

## §4 执行阶段（一次顺序执行；每阶段产物落 attempt 根）

### P0 基线与重建
1. `date`/`git status --porcelain` 留档（G31-11 触碰文件归属清单以此为底册）。
2. analytics-server 全 reactor 测试（当前工作树）→ 基线计数留档。
3. 实现 §2 代码改动 → 全 reactor 复跑 → `F=0 E=0`（判据7）。
4. 重建两份 jar（spark-jobs / platform-app），SHA256 留档 `jars/SHA256S.txt`。

### P1 隔离行为栈（RunId `g3111iso_<ts>`）
1. `it-prepare-isolation.ps1 -RunId g3111iso_<ts> -Confirm -AllowRootOnIsolated -IncludeAnalytics`（3307 隔离库对 + 账号 + landing；单进程 V25_IT_* 通道，零落盘）。
2. 平台以隔离库启动（F2a 门禁 + F2b 日志扫描照抄 G31-10 口径；stub LLM ensure）。
3. 入账 B1、B2（合法数据，两次 ingest，各成批）、B3（合法，供场景3钉住证明）、BF（质量门必败数据，照 G31-10 F1 夹具模式）。

### P2 四场景行为腿（判据 1-4 的隔离栈部分；顺序即依赖）
1. **场景4 前半 + 场景2**：run1（独立 Idempotency-Key，businessTime=2026-09-01）→ 断言选 **B1**、发布成功、ACTIVE=新快照 S1、消费行(B1,run1,S1)。
2. **场景4 后半**：run2 → 断言选 **B2**（不是 B3/最新）、发布、消费行(B2,run2,S2)、ACTIVE=S2。**两批均未被遗漏**（判据4）。
3. **场景1（隔离栈）**：run3（此时 READY 未消费批 = B3、BF）→ 先验证「仍有输入会发布」由场景3承担；no-op 腿改为在 B3/BF 也消费后以 run5 执行（见第 5 步）。本步改为：**重算腿（场景3 依赖件）**——对已消费 B1 执行 recalculate(operator/reason) → 断言成功再发布 B1（重算豁免生效）、消费行 recalc_count+1、last_recalc_* 落值。
4. **场景3**：run4 = 消费 BF（FIFO 选中最旧未消费 = B3？——**注意**：BF 必败批必须排在 B3 之前入账或以 recalc 入口触发，保证 run4 钉住 BF）；预期 FAILED(`PIPELINE_QUALITY_FAILED`)、无消费行、ACTIVE 不变；此时入账更新批（或用既有 B3）后 **retry run4** → 断言仍用 **BF**、仍 FAILED（钉住证明前半）；再用合法重试路径（将 BF 数据修复为合法批内容后同 run retry）→ SUCCESS、消费行落 BF。**若 retry 选了 B3 → 判据3 停止**。
5. **场景1（隔离栈）**：run5（全部批已消费）→ SUCCESS no-op、`noNewInput=true`、零新快照、ACTIVE 不变、消费行零新增。

### P3 正式栈受影响终验（判据5）
1. 预检：credref 通道连通、正式库对、flyway 31 行（V32 顶）、`SUCCESS 且 (input_batch_id IS NULL OR source_id IS NULL)` 计数（预期 0，非 0 停止回填并登记）。
2. 停常驻栈 → 以新 platform-app jar 重启（`g3111-formal-restart.ps1`：三迁移随迁锚点 S20260901_23/credref/DEC 弹性下限 + mini-gate）→ Flyway V33 落地（`FLY|33|...|success=1`），回填行数 = SUCCESS run 数（`created_via='BACKFILL_V33'`）。
3. **终验 no-op 腿**：正式栈触发 run → SUCCESS no-op、ACTIVE 仍唯一 `S20260901_23`、零新快照。
4. **G31-07 腿重跑**（非发布型）：ACTIVE 唯一、overview 指纹 == P6 oracle 钉档 14/14（容差 0.0005）、F2b 扫描、stub LLM 探针、身份链。腿→判据映射表落结果文档（发布型腿由 P2 承担的映射一并登记）。

### P4 收口
1. 结果文档（§验收→证据映射、四场景逐条对答、偏差登记、运维查询 SQL、触碰文件归属清单）。
2. DECISION_LOG D-049、CURRENT_BATCH、PROJECT_STATUS（先备份）。
3. 本地提交（push 授权用尽）：按归因清单分组——① G31-11 代码+测试+迁移；② G31-11 文档；**绝不与 D-048/G31-10 在途未提交文件混组**。

## §5 边界

- 真实 AI、远程集群、远程 Hive Metastore **不并入**（裁决原文）；本批不证明它们。
- 崩溃窗口语义（D-049g）如实登记，不当缺陷修，不做分布式事务。
- 历史 `RUN_EMPTY_LANDING` 语义保持（无清单 ≠ 全部已消费），拆分理由落结果文档。
- 若执行中发现本计划未覆盖的语义分叉（如老清单无 sourceId 的消费归属）→ 停止该腿、记录现场、按「先决策后记录」补登记后再继续。
