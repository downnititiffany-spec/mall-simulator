# 批次计划 BATCH-G31-10 — D-044⑤ 合并重跑（正式 3307 V32 落地 + F-G4-1 / D-041 行为级实测 + G31-07 终验重跑）

- 批次：G31-10
- 日期：2026-09-25
- 裁决依据：D-044⑤（合并验收批：不逐项重复跑全量链，一次合并重跑覆盖 G31-08/G31-09 两修复的行为级取证 + G31-07 终验重跑）
- 缺陷登记：F-G4-1（G31-04 登记，G31-08/D-045 修复，**行为级证据待本批**）、D-041（HDFS 同文件重试重读嫌疑，G31-09/D-046 仅完成 schema 前提，**行为级证据待本批**）
- RunId：`g3110_20260925_HHMMSS`（执行时落实际时间戳），attempt 根：`target/v25-it/g3110_20260925_HHMMSS/`
- 正式库对：`stage7q1_20260918_152245_analytics_meta` / `_analytics_metric`（3307；root 口令**不含于文档**——执行时经 WSL `MYSQL_PWD` 环境变量传入。2026-09-25 D-048：原 W03 记录值已轮换作废，现行值仅存仓库根 `credref-mysql3307-root.properties`（gitignored））

---

## §1 判据与证据边界（四条停机判据，钉死，执行中任何一条不满足即停止验收并记录原始现场）

1. 正式库迁移前，核实连接确为 **3307 的 `analytics_meta`**、Flyway 当前版本及 V32 未应用；保留可恢复备份。目标不符立即停止，绝不触碰 3306。
2. 用当前工作树重建并记录两份 jar 的 SHA；若 G31-08 的"读旧分区再覆写"在实际 Spark 表上失败，停止验收、记录原始错误，不把单元测试通过当成链路通过。
3. 新快照的指标 oracle 从本次数据和库中独立取证，不能沿用旧快照，也不能只拿 API 与自身对比；同时验证失败时旧 ACTIVE 仍可读。
4. 验收措辞限定为**当前 WSL 单节点环境**；它不证明远程集群或共享 Hive Metastore。G31-07 的登录与 AI 探针可能产生审计记录，终验也不要笼统称为"完全只读"。

补充判据：

- **判据2 的具体化**：M2（同日增量）必须给出「行为/交易/产品三类指标**不被清零**、新增事件**被计入**、重放**不重复计数**的三重行为证据；任何一类出现 G31-04 式清零（S2+ 行为零化形态）→ 停止验收，留存 SJR/原始 Spark 日志与错误原文。
- **D-041 的解除标准**：必须看到**同一 HDFS 文件重试**产生 `noNewData=true && recordCount=0` 的行为证据；仅 schema 变宽（D-046）不构成解除。
- **旧 ACTIVE 可读**（判据3 后半）：F1 质量门阻断腿中，run FAILED（`PIPELINE_QUALITY_FAILED`）后 overview 必须仍完整返回阻断前 ACTIVE（S_C）的 14 指标指纹，逐项相等（容差 0.0005）。
- **证据边界（措辞红线）**：本批全部结论限定 WSL 单节点（本机 3307 + 本机 HDFS 19000 + 本机 Spark local）；ODS 落在 attempt 本地 spark-warehouse（Derby per-attempt metastore），**不涉及**正式 Hive Metastore / 远程集群。终验 9 腿为对常驻栈的核验，其中登录与 AI 探针**按设计产生审计/查询记录**，不称"完全只读"。
- 测试基线（历史参考，本批不新增测试运行）：spark 329 / isolated 62 / default 1275（G31-09 收口值）。本批为链路行为取证批，**预期零源代码改动**；若执行中需要改代码修复 → 本批立即停止，另开批次。

## §2 改动面

| 对象 | 预期 |
|---|---|
| 源代码（analytics-server/*, platform-ui/*） | **零改动**（G31-08/D-045、G31-09/D-046 修复已落工作树，本批用当前工作树重建 jar 验证） |
| 3307 正式库 schema | `file_checkpoint.file_identity` varchar(64) → varchar(255)（Flyway V32 启动自动迁移，唯一 schema 变更） |
| 3307 正式库数据 | 新增 ingestion 批次/pipeline run/快照/审计行（正常业务写入）；V8–V31 flyway 行不变 |
| 新增脚本 | `target/v25-it/g3110_*/scripts/{g3110-precheck.ps1, g3110-driver.ps1, g3110-acceptance.ps1}`、`oracle/oracle.py`、输入夹具 JSONL |
| 文档 | 本计划、结果文档 `BATCH-G31-10-D044-5-MERGED-RERUN-RESULT.md`、`docs/decisions/DECISION_LOG.md`（D-047）、`docs/verification/CURRENT_BATCH.md`、`docs/PROJECT_STATUS.md`（均先备份 `*.bak-20260925-g3110`） |
| 3306 | **零接触**（永久红线） |

## §3 依据

- G31-04 结果（`docs/verification/results/G31-04-FAULT-RECOVERY-RESULT.md` + `target/v25-it/g3104b_20260925_104346/F-G4-1-REGRESSION-FINDING.md`）：F-G4-1 复现形态、S1 指纹（S20260901_1，pv=7/uv=3/dau=3/paid=5/fav=2/cart_add=3/buy_rate=1.0/cart_rate=0.6667/refund_rate=0.6/full_refund_rate=0.2/avg_order_value=408.4/repeat_rate=0.3333/net_sale=1493/gmv=2042）、S2+ 零化回归形态。
- G31-08 计划/结果（D-045）：分区作用域 read-merge-dedup-overwrite（newRows ∪ 命中分区反连接 newRows.event_id）、动态分区覆写 try/finally、空批零写、new-wins；spec G1–G5b。
- G31-09 计划/结果（D-046）：V32 `ALTER TABLE file_checkpoint MODIFY COLUMN file_identity VARCHAR(255) NOT NULL DEFAULT ''`；HDFS 身份串 94 字符；255 宽度下索引键预算 3036≤3072。
- G31-07 验收报告 §9 + `target/v25-it/g3107_20260925_142915/scripts/g3107-acceptance.ps1`：终验 9 腿形态（leg5/leg6 锚点与 oracle 需参数化为新值）。
- 常驻栈启停模板：`target/v25-it/item3/item3-restore-g3103.ps1`（stub 预检、幂等 prep、F1 环境块、F2a/F2b 守卫、身份台账、LLM 探针）；停栈 `scripts/stop-platform-by-pidfile.ps1`。
- fail-closed 阻断机制：`target/v25-it/p2pub_20260925_021629/item2/item2_drill.py`（BAD_PAYMENT order_paid amount 与订单总额不符 → AMOUNT_RECONCILE BLOCKING → run FAILED）。
- oracle 底本：`target/v25-it/item4/item4_reconcile.py`（14 指标纯 Python 对账，本批复制为 g3110 oracle.py，输入切换为本次交付文件）。
- golden 数据集：`tests/golden-dataset/events/golden-20260901-positive.jsonl`（50 行；小时×类型矩阵已盘点：user_registered@09×3/12×1；behavior@10×5/11×3/13×3/15×3；订单链@10–15；hour 16 仅 refund_completed+stock_changed）。

## §4 执行阶段（合并链，一次顺序执行；每阶段产物落 attempt 根）

### P1 预检（停机判据1 的门，全部通过才进 P2）

产物：`precheck/`。

- precheck-01 连接身份：经 WSL `mysql -h127.0.0.1 -P3307` 执行 `SELECT @@port, @@version;` 且 `SHOW DATABASES LIKE 'stage7q1_20260918_152245_%';` 命中正式对。任何返回指向 3306 → **停止**。
- precheck-02 flyway 前态：`analytics_meta.flyway_schema_history` 全量导出留存（预期 top=31，**V32 不存在**）；`information_schema.COLUMNS` 记录 `file_checkpoint.file_identity` 现值（varchar(64)）。
- precheck-03 快照盘点：metric 库 `metric_snapshot` 全量 id/status（预期 ACTIVE 唯一 = S20260921_20）；`SELECT ... WHERE status='ACTIVE'` 计数 ≠1 → 停止。
- precheck-04 landing 池盘点：meta 库 landing/batch 相关系表现存状态（若存在**未被任何 run 消费**的历史批次 → 停止评估，防止 M1 被旧数据污染）。
- precheck-05 可恢复备份：`mysqldump` 双正式库 → `backup/<db>.sql`（+SHA256 台账 `backup/SHA256S.txt`）；校验方式 = 对关键表行数对账（sys_user=3、flyway 行=31、metric_snapshot 总数、decision_task 总数）与 `restore.md`（记载逐条恢复命令，含 3307 端口）。备份失败/对账不符 → **停止**。
- precheck-06 环境盘点：8091 现为 g3103 栈（java 50980←wrapper 41292）留证；HDFS 当前 down；stub LLM 18080 /healthz 200；HDFS conf 于 `target/fast-dev-20260924/hdfs-smoke/conf/`（fs.defaultFS=hdfs://127.0.0.1:19000）。

### P2 受控停 + 双 jar 重建（停机判据2 前半）

- `scripts/stop-platform-by-pidfile.ps1` 按 g3103 identity 台账受控停（identity 核验不符 → 停止）；停后 8091 释放、platform JVM=0 留证。stub LLM（18080）不动。
- `mvn -DskipTests package`（`analytics-server/spark-jobs` 与 `analytics-server/platform-app`）→ 记录两份 SHA256 + 构建日志 `jars/`。构建失败 → 停止。

### P3 平台以正式 analytics_meta 启动 → V32 落地

- `g3110-driver.ps1` = item3-restore 变体：RunId=g3110_*、dataRunId=stage7q1_20260918_152245、fresh in-process `V25_IT_*` 口令（零落盘）、幂等 prep（`-AllowRootOnIsolated -IncludeAnalytics`，零 DROP）、mini-gate（此时 ACTIVE 仍应为 S20260921_20 唯一、USR=3、DEC 记录实际值）、F2a 正例门禁、启动后 F2b（3307 双 URL + landing 有据、零 `:3306`）、身份台账写 g3110 `platform.identity.json`。
- 启动即 Flyway 自动迁移：验收点 `migration/`：
  - `flyway_schema_history` 出现 `32 | ... | success=1`；
  - `information_schema.COLUMNS`：`file_identity` = varchar/**255**；
  - V8–V31 行（version/checksum/success）与 precheck-02 前态逐行一致（diff 留证）；
  - 任一不符 → **停止**（判据1），按 restore.md 评估恢复。
- 启动后登录冒烟 + LLM 探针（stub-local/EXECUTED/suggestions≥1）。

### P4 LOCAL 行为腿（M1→M2→M3→F1，全部 businessTime=2026-09-01T00:00:00，独立 Idempotency-Key）

产物：`legs/m1-*.json … f1-*.json` + SJR/阶段表导出。

- **M1 基线腿**：golden 50 行 → LOCAL ingest（断言 recordCount=50、quarantineCount=0、noNewData=false）→ pipeline run1 → SUCCESS → ACTIVE=S_A。
  - 断言：S_A 14 指标与 G31-04 S1 指纹**全等**（g3104b drill-state legs.run1.snapshotS1；容差 0.0005）。不符 → 停止（说明当前工作树在全量日载入上已偏离 G31-04 行为）。
- **M2 同日增量腿（F-G4-1 核心）**：批 B = 3 条新事件（钉死）：
  - B1 `user_registered` @2026-09-01T09:20:00+08:00，user_id=`g4u02`（hour 09 与 3 条既有 user_registered 同分区）；
  - B2 `behavior/view` @15:10，user_id=1、product_id=1（hour 15 与 3 条既有 behavior 同分区）；
  - B3 `behavior/fav` @10:50，user_id=2、product_id=3（hour 10 与 5 条既有 behavior 同分区）。
  → ingest（3/0）→ pipeline run2 → SUCCESS → ACTIVE=S_B。
  - 断言（G1/G2/G4）：① 行为/交易/产品三类指标不清零（14 指标齐全且与 S_A 同形，无零化）；② 新增计入：pv 7→8（B2）、fav_cnt 2→3（B3）；uv/dau 的走向以 oracle.py 在**run2 执行前**对（golden+批B）独立计算的绝对值为准（oracle-first）；③ 同分区共存：SJR/ODS 行数证据表明 hour 09/10/15 命中分区为合并非清零。出现零化 → **停止（判据2）**，留原始错误与 SJR。
- **M3 重放腿**：同一批 B 文件不再新增（不放置任何新输入文件）再触发 ingest → 断言 `noNewData=true` 且 recordCount=0（LOCAL checkpoint 同文件不重读）。随后 pipeline run3：因 landing 根无新 READY 批次，WAIT_LANDING 按 `LandingManifestSelector` fail-closed 语义返回 null → run 以 **RUN_EMPTY_LANDING 拒绝**（此为本设计 §9.3 的预期行为，非故障）；断言 ACTIVE 仍为 S_B、overview 指纹 14/14 与 S_B 全等（重放不重复计数，G3 的链路形态）。若 run3 未按 RUN_EMPTY_LANDING 拒绝或 ACTIVE 漂移 → 停止取证。
- **F1 质量门阻断腿（判据3 后半）**：BAD_PAYMENT 形态事件 `order_paid` @23:55，order_id=1001（golden 总额 1097.00）、amount=1197.00、payment_id=`P-919-g3110`、新 event_id → ingest（1/0）→ pipeline run4 → **期望 FAILED + `PIPELINE_QUALITY_FAILED`**；随后 overview 必须仍返回 S_B 指纹 14/14 不变（旧 ACTIVE 仍可读）。若发布意外成功 → 如实记录并按 item2 race-lost 规则重演一次。

### P5 HDFS 腿（D-041 行为级；置于所有 pipeline run 之后，其后不再有任何 pipeline run）

- profile 1 切 `hdfs://`（G31-05 05.3 机制，test→activate）；启动 WSL Hadoop（conf 见 precheck-06；setsid nohup 起 NN/DN，jps 留证）。
- 推夹具 `inputs/g3110-hdfs-batch1.jsonl`（2 条 behavior view，event_id=golden-evt-921/922-g3110）→ ingest#1：断言 recordCount=2、noNewData=false；DB 回读 `file_checkpoint` 该行：`file_identity LIKE 'hdfs:%'` 且 **LENGTH(file_identity)=94**、列宽=255（D-046 落列证据）。
- **ingest#2 同文件重试**：断言 **noNewData=true && recordCount=0 —— D-041 行为级判据**。若重读 → D-041 不解除，如实记录原始证据，本批不宣称 D-041 收口。
- ingest#3 新文件 `g3110-hdfs-batch2.jsonl`（1 条）→ recordCount=1（未误拦新文件）。
- profile 切回 `file://`（G31-05 05.5 机制）；HDFS 进程处置如实记录（停或留）。

### P6 oracle 独立取证（判据3 前半）

- `oracle/oracle.py`（继承 item4_reconcile.py 定义，纯 Python 无平台依赖）：输入 = 本次**实际交付**的 JSONL（M1 golden 50 + 批 B 3 = 53 行，取 accepted/ 交付副本逐字节），输出 14 指标期望值。
- 时序：**run2 前先出 S_B 期望值钉档**（oracle-first）；S_B 即终锚（M3 重放不产出新快照）。
- 三方对账：oracle.py ↔ metric 库 ACTIVE 快照 payload（`stage7q1_20260918_152245_analytics_metric.metric_snapshot`）↔ API overview。三方 14/14 全等（容差 0.0005）→ 终锚 ACTIVE=S_<M2 快照实际 id> 登记台账。任一方不等 → **停止**。
- 禁止：沿用旧快照（S20260921_20 或 item4 90-03）做期望值；仅拿 API 与自身对比。

### P7 G31-07 终验重跑

- `g3110-acceptance.ps1` = g3107 变体，9 腿同构：leg5 ACTIVE 唯一 = **新锚快照 id（= M2 的 S_B）**（参数化）；leg6 指纹 = **P6 oracle 值**；leg7 F2b 日志 = g3110 `logs/platform.log`；身份链 = g3110 identity 台账；leg1/2/3/4/8 同 g3107（登录与 AI 探针按设计产生审计记录）。
- 9/9 全绿 → 终验 PASS（措辞按 §1 证据边界：WSL 单节点、非完全只读）。

### P8/P9 收口

- 平台保持运行为新常驻栈（g3110 域）；**注意**：后续任何恢复脚本的 mini-gate 锚点必须从 S20260921_20 更新为 S_B 新锚（在结果文档中显式登记，防 item3-restore 硬编码误判）。
- 结果文档 `docs/verification/results/BATCH-G31-10-D044-5-MERGED-RERUN-RESULT.md`（含验收→证据映射表、四停机判据逐条对答、D-047 台账指针）。
- `DECISION_LOG.md` 增 **D-047**（V32 正式落地 + F-G4-1/D-041 行为级证据 + 新终验锚点）；`CURRENT_BATCH.md`、`PROJECT_STATUS.md` 先备份后更新；报总控：以本批证据替换 F-G4-1/D-041 拦截项并请求签收；真实 LLM、远程集群/共享 Metastore 维持未验收范围。
- 提交：push 授权已用尽，仅本地提交；按批次归因清单分组（本批 = docs + scripts，绝不 bulk-tree）。

## §5 边界

- 本批不修改源代码、不新增产品测试；所有"通过"均为链路行为证据。
- 3306 零接触；`V25_IT_*` 口令零落盘零 argv 零 git；3307 root 口令仅经 WSL `MYSQL_PWD`（2026-09-25 D-048 起口令值已轮换，只存 credref，文档零口令）。
- Spark 循环回环 jar 下载挂起（local 模式 executor 自下载 ~12% 阻塞）为已知环境坑：run 卡 12% 即按 watcher+jstack 模板诊断留证，不盲目重试。
- Maven 路径用正斜杠（MSYS 转换坑）；WSL 发行版名为 **Ubuntu**。
