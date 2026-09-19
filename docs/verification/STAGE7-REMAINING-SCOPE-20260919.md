# Stage 7 剩余范围清单（2026-09-19，代码 Agent 研究登记）

> 性质：工作清单，随批次推进滚动更新；不改变目标/范围/架构（指导书 §9）。
> 权威出处：指导书 §7「### 阶段 7：业务联调」（L177–L183）与 §8 Stage 7 完成标准（L203）；设计 V3 §8（Flume/HDFS）、§5.1（环境矩阵）、§18.3。
> 当前门：`BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS`（T-R1 未关闭前，下表任何一项都不得置 READY/执行）。

## 总锚点

指导书 Stage 7 五项任务（L179–L183）：

1. 独立生成器调用商城 HTTP，下单/支付/退款形成 Outbox 和日志。
2. 小规模串通采集→数仓→Spark→发布→页面→AI/决策；对账交易、事件、接收/隔离和指标不同量纲。
3. 第二来源异构夹具复用同一平台，证明字段适配及同 ID 不串源；真实外部商城按授权样例另验。
4. 检验源停机、重放、阶段失败、发布中断后旧快照仍读得到。
5. HDFS/Hive/集群验证按环境目标单独登记，不用 local[1] 测试替代。

完成标准（L203）：「一个业务来源完整链及第二异构源链；日志/层表/指标/页面同源一致，故障样本通过」。

Batch T 计划 §8「PASS boundary」明确本链仍不证明：Flume Spooldir→File Channel→HDFS Sink；REMOTE_CLUSTER；浏览器 E2E；真实 LLM provider。这四项 + 第二来源 + 故障样本 = Stage 7 剩余面。

## 逐项清单

| # | 内容 | 指导书/设计出处 | 当前状态（证据行号见 PROJECT_STATUS） | 环境前提 | 建议批次形状 |
|---|---|---|---|---|---|
| 0 | （当前门）T-R1 platform-exit 诊断复跑 | PROJECT_STATUS L4–L6；CURRENT_BATCH | READY，唯一激活门 | 与 Batch T 相同：3307、Q-R1 run-scoped 双库、8091 空闲、口令只走 Process env | 见 T-R1 计划 §5/§6 |
| 1 | Flume→HDFS 独立批次：Spooling Directory Source + File Channel + HDFS Sink 首版链路实测 | 指导书 L92、L183；设计 §8.1–§8.4 | Flume 从未运行（L482）；`flume-spooldir.conf` 已有静态门禁证据，`landing_layout=FLUME_RAW` 读侧（V22 列）已备 | WSL2 单节点 Hadoop（1NN/1DN，replication=1，不要求 YARN/SSH，设计 §5.1）；Flume 二进制；HDFS RPC 端口按 core-site 实读不得猜测（设计 L136）；Windows→WSL 文件交接显式记录（§8.2 规则 6） | 最可能下一批次：`BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN`（DRAFT 骨架见 batches/） |
| 2 | HDFS/Hive/集群验证按环境目标单独登记（WSL 单节点档 / REMOTE_CLUSTER） | 指导书 L183、L233；设计 §5.1 L110–L115、§5.4 L146 | 未验；G-01「完整 WSL 单节点链是否必交」待总控裁决 | REMOTE_CLUSTER 需 VMware/云主机、SSH、HMS/HiveServer2 端口实锁（设计 L134–L135）；G-01/G-06 需总控先行 | 与 Flume 批解耦：Flume 批可落在 WSL 单节点档并单独登记环境边界；REMOTE_CLUSTER 另立批次 |
| 3 | 页面→AI/决策小规模串通（浏览器 E2E） | 指导书 L180、L125；设计 §18.3 L816 | 真实浏览器＋真实 HTTP 数据渲染仍未验（L552）；DEFERRED_TEST_PLAN V-003～V-027 均标「无真实浏览器/HTTP E2E」 | 前端 5173/5174 或构建后静态页挂 8091（设计 L127–L131）；R4 已证 8091 平台全 8 阶段链可跑通 | R4 式真实 attempt + 真实浏览器走查经营概览/销售/行为/RFM/AI/决策页；PASS=页面数字与 metric_value/ADS 导出同源一致（L203） |
| 4 | 真实 LLM provider 实证 | 指导书 L172、L125；设计 §13.4 L577–L581 | 未验：DEFERRED_TEST_PLAN L32/L35「无真实 Provider」 | G-07「模型预算/外部商城授权」待总控（L264）；凭据 credentialRef/受保护 env（设计 L755） | 小预算真实调用批次：正样本+拒绝样本+超时+非法 JSON，记录 provider/model/promptVersion/requestFingerprint |
| 5 | 第二来源异构夹具 | 指导书 L181、L203、§4.2 L66；设计 §2.1 L33、§7 L206–L247 | 未开始（状态文件无第二来源工作项登记） | 来源登记/画像/映射 v2 激活生命周期代码已在（L168）；需设计异构小夹具与第二 sourceCode | 独立批次：夹具导入（标明 fixture 来源）→ 映射 dry-run → activate → 采集 → 与 mock-mall 并存验证同 ID 不串源 |
| 6 | 故障样本：源停机、重放、阶段失败、发布中断后旧快照仍读得到 | 指导书 L182、L203；设计 §17.4 L761–L772、§10.4 | 仅 Fake-executor L1 证据（7 阶段 fail-fast 矩阵 `384dedf`，不证明真实 spark-submit/子进程失败形态，L574） | 依赖真实链可复跑（T-R1 通过后）；样本需可重放输入（指导书 §4.2 L68） | 每类故障一小节：停 producer、重投放同文件、kill Spark 子进程、发布中断后读旧 ACTIVE 快照 |

相邻 backlog（非 Stage 7 门禁，不与本表混写）：真实 spark-submit 专项 `SparkStageExecutorSmokeIT`（L490，本机缺 `HADOOP_HOME/winutils.exe`）；Flume 清单契约开放问题 `files[].file` 为文件名（L483，需总控裁定，不得擅改契约）；遗留 `flume-taildir.conf` 处置（L484）。

## T-R1 未关闭前不得启动的事项

1. 任何新批次置 READY/执行（CURRENT_BATCH：当前唯一可执行批次是 T-R1；TEST_EXECUTION_PROTOCOL §2 一次一门）。
2. Flume/HDFS 链路执行（PROJECT_STATUS L26：Flume/HDFS 单独后置，不与 LocalFile 三程序链混跑；Batch T PLAN 同款）。
3. 若 8091 再次消失且未按新 evidence 分类——不得启动依赖 8091 长存活的批次（CURRENT_BATCH：不再猜测）。
4. 复用被禁运行物：pipeline runId 5、mutable producer latest pointer、旧 target JAR、Q-R1 之外的库。
5. 改写 Batch T 结论（BLOCKED_PLATFORM_EXIT_UNDIAGNOSED 不得升格为 BUILD_DWS 业务 FAIL 或降格为 PASS）。
6. 放宽安全边界：3306 写入、口令进命令行/文档、改质量阈值、Fake executor、`-AllowCountDrift` 当绿灯。
7. 宣称集群/实链验收（spark local[1]+in-memory ≠ Hive/集群/Flume/HDFS 验收；Flume「配置文本正确」≠「采集可用」）。
8. 未经总控的契约/范围变更（`files[].file` 契约、taildir 处置等只登记不擅改）。
