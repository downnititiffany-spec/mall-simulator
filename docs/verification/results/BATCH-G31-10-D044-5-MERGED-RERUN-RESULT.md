# 批次 G31-10 结果 — D-044⑤ 合并重跑：V32 正式落地（3307）+ F-G4-1/D-041 行为级证据 + G31-07 终验重跑

> 状态：**PASS（当前 WSL 单节点环境）**。执行序列 P1→P9 全绿；终验锚点从 `S20260921_20` 迁移为 **`S20260901_23`**。
> 计划：`docs/verification/batches/BATCH-G31-10-D044-5-MERGED-RERUN-PLAN.md`；裁定：D-044（⑤ 合并批次）；
> 上游：D-045（G31-08 分区作用域读-合并-去重-覆写）、D-046（G31-09 V32 列宽 64→255）。
> attempt 根：`target/v25-it/g3110_20260925_191753/`（driver 状态 `drill-state.json` 顶层 legs；终验状态独立落 `terminal/acceptance-drill-state.json`）。
> 本批零源代码改动、零产品测试改动；全部"通过"均为链路行为证据。

## 1. 批次目标

以一个合并批次闭合 D-044 拦截的三个尾巴，替代逐项重跑全量链：
1. 正式 3307 `analytics_meta` 应用 V32（G31-09 只完成了 schema 前提：per-run 库 ≠ 正式库）；
2. F-G4-1（G31-08 增量合并语义）与 D-041（HDFS 同文件重试不重读）在真实平台链路上取得**行为级**证据；
3. G31-07 终验按新锚快照重跑（oracle 从本批数据独立取证，禁止沿用旧快照）。

## 2. 执行序列与证据（验收→证据映射）

| 计划项 | 内容 | 结果 | 证据 |
|---|---|---|---|
| P1 预检 | 身份/连接/版本/口径盘点 | PASS | `precheck/01-identity.txt`（g3103 栈受控停前身份）、`02-flyway-before.txt`（V1–V31 共 30 行、`file_checkpoint.file_identity` varchar\|**64**、checkpoint 19 行）、`03-snapshots.txt`（前态唯一 ACTIVE=`S20260921_20`）、`06-env.json` |
| P2 受控停+重建 | pidfile 受控停 + 当前工作树重建双 jar | PASS | `jars/SHA256S.txt`：spark-jobs `71c0fc88…00e57`、platform-app `8366bc12…bfc0b`；构建日志 `jars/build-*.log` |
| P3 正式库启动 | 平台以正式 `analytics_meta` 启动，Flyway 自动迁移 V32 | PASS | `logs/sql-v32-after.out.txt`：`FLY\|32\|file checkpoint identity width\|1`；V8–V31 逐行与前态全等（30→31 行，仅新增 32）；`COL\|varchar\|255`；备份 `backup/`（meta sha256 `7edab792…`，metric `b79e5571…`，`restore.md`）；身份台账 `platform.identity.json`（wrapper 30348）；mini-gate：启动时唯一 ACTIVE=`S20260921_20`、DEC 记录实际值 12；F2a 正例门禁 + F2b（`logs/guard-positive-g3110.out.txt`） |
| P4-M1 基线腿 | golden 50 → ingest 50/0 → run1 → S_A | PASS | `drill-state.json` legs `m1`（batch 22 `ing-20260925193512-47945f55`）、`anchorS_A`：**S_A=S20260901_21** 14/14 与 G31-04 S1 指纹全等（tol 0.0005，比照 g3104b legs.run1.snapshotS1） |
| P4-M2 增量腿（F-G4-1 核心） | oracle-first 钉档 → 批 B 3 条 → run2 → S_B | PASS | `oracle/oracle.py --s1-check` **13/13** 先行 PASS（vs 人工标准答案）；`oracle/oracle-expected-sB.json` 于 run2 前钉档；legs `m2-run2`/`anchorS_B`：**S_B=S20260901_22** 14/14 == 钉档值；pv 7→8、fav_cnt 2→3、同分区合并不清零（D-045 语义在真实 Spark 表成立） |
| P4-M3 重放腿 | 同批重试 + run3 | PASS（语义修订见 §5-1） | legs `m3`：重试 `noNewData=true && recordCount=0`；run3 **SUCCESS**、`valuesUnchanged=true`（值==S_B）、新快照 **S_C=S20260901_23** 转 ACTIVE |
| P4-F1 质量门阻断腿 | BAD_PAYMENT → 期望 FAILED/PIPELINE_QUALITY_FAILED + 旧 ACTIVE 可读 | PASS（含 flake 登记 §5-2） | legs `f1-run4-flake`（run 24 `RUN_JOB_FAILED`@BUILD_DWD = 已知环境坑 spark-local-loopback-jar-download-hang）、`f1-run25`/`f1`（run 25 **FAILED/PIPELINE_QUALITY_FAILED**@QUALITY_CHECK；期间 overview 仍返回 S_C 14/14 —— 失败时旧 ACTIVE 可读成立） |
| P5 HDFS 腿（D-041 行为级） | profile 切 hdfs:// → 3 次 ingest → 切回 → 停机 | PASS | `evidence/p5-*.json/txt`：NN/DN 拉起（smoke conf 复用不 reformat）+ 写探针；profile1 仅改 `landingUri`→`hdfs://127.0.0.1:19000/landing/g3110_20260925_191753`（G31-05 05.3 test→activate，test allPassed）；ingest#1 batch 28 = **2/0/noNewData=false**；checkpoint 恰 1 行 `hdfs:…` 且 **LENGTH=CHAR_LENGTH=94**、列宽 varchar(255)；**ingest#2 batch 29 同文件重试 = 0/noNewData=true（D-041 行为级判据，未重读）**；batch2 → ingest#3 batch 30 = **1/0**（新文件未误拦）+ checkpoint 2 行均 94；profile 切回 file://（05.5 test allPassed）；HDFS 停机（先 DN 后 NN，`HDFS-PROCS-GONE`），smoke 数据保留。P5 后无任何 pipeline run（计划顺序保持） |
| P6 oracle 三方对账（判据3 前半） | oracle ↔ metric 库 ACTIVE ↔ API | PASS | `oracle/p6-three-way.json` 9/9：①交付副本逐字节（accepted/22 vs repo golden、accepted/24 vs 批B 源文件 sha256 全等）②交付副本复算==钉档 S_B（14/14，tol 0.0005）③DB 恰 1 行 ACTIVE=**S20260901_23** + 14 metric_value ④API snapshotId==ACTIVE、14 码 ⑤⑥⑦⑧⑨ 三方全等 + definitionVersion 一致；API 原始响应 `oracle/p6-api-overview.json` |
| P7 终验重跑 | g3107 变体 9 腿 | PASS | `scripts/g3110-acceptance.ps1`，状态 `terminal/acceptance-drill-state.json`、legs `evidence/*.json`：leg5 ACTIVE 唯一=**S20260901_23**；leg6 指纹 14/14 == P6 oracle；leg7 F2b 零 `:3306` + 3307 双 URL + g3110 landing 有据（`logs/platform.log` 尾 20 万字符）；leg8 stub-local/EXECUTED suggestions=2；身份链 java(34828) ← wrapper(30348) marker=`g3110_20260925_191753` |

**终锚登记：ACTIVE = `S20260901_23`**（= P4-M3 的 S_C；值与钉档 oracle S_B 全等）。快照链：S20260901_21（M1）→ S20260901_22（M2）→ S20260901_23（M3 重放产出，D-045 去重下值不变）。

## 3. 四停机判据逐条对答

1. **正式库迁移前核实 + 可恢复备份**：迁移前状态三证——连接确为 3307 `analytics_meta`（driver 启动 URL + F2b 双 URL 日志守卫）、Flyway 前态 `02-flyway-before.txt`（V1–V31、file_identity varchar|64、checkpoint 19 行）、V32 未在 32 行出现；迁移前全库 dump 于 `backup/`（meta+metric 双库，SHA256S + `restore.md` 恢复命令；恢复即回到 varchar(64) 前态）。全程 3306 零接触。✅
2. **当前工作树重建 + 实际 Spark 表行为证据**：双 jar SHA 记录（§2 P2 行）；G31-08「读旧分区再覆写」未以单测通过替代链路证据——M2 在真实 Spark/parquet 表上验证增量合并（pv 7→8、fav_cnt 2→3、同分区旧记录并存非清零），M3 在同表上验证重放零新增（anti-join 去重，值==S_B）。未触发停机。run4 属**已知环境坑**（jar 下载挂起），非合并语义失败，已如实登记（§5-2）。✅
3. **oracle 独立取证 + 失败时旧 ACTIVE 可读**：期望值在 run2 **前**由 `oracle.py` 独立复算钉档（s1-check 13/13 vs 人工标准答案先行）；P6 追加交付副本逐字节 + 副本复算相等，未沿用 S20260921_20 或 item4 90-03 作期望值，也未只拿 API 与自身对比（oracle/DB/API 三方独立来源）；F1 期间旧 ACTIVE（S_C）overview 仍 14/14 可读。✅
4. **验收边界措辞**：本批全部"通过"限于**当前 WSL 单节点环境**（本地 Hadoop smoke conf + 单机 Spark local + 3307 双库）；不证明远程集群或共享 Hive Metastore。终验登录与 AI 探针按设计产生审计记录（DEC 自启动时 12 → 探针后 +1），**非"完全只读"**。真实 LLM、远程集群维持未验收范围。✅

## 4. D-044 拦截项替换（报总控请求签收的核心）

- **F-G4-1（同日增量 ODS 保留历史）**：由 P4-M2（真实 Spark 表增量合并、同分区不清零）+ P4-M3（重放不重复计数）行为级闭合；D-045 单测判据（OdsMergeIncrementalSpec 7/7）在本批链路上得到对应行为证据。
- **D-041（HDFS 同文件重试不重读）**：由 P5 ingest#2 行为级闭合（`noNewData=true && recordCount=0`，batch 29），且 94 字符身份在正式库 V32 varchar(255) 列原样落列（G31-09 schema 前提 → 本批正式落地 + 行为闭环）。D-041 拦截**解除**。
- 产品验收签收权在总控；本批仅提交证据并请求以本批替换上述两项拦截。

## 5. 计划偏差诚实登记（必读）

1. **M3 语义修订**：计划预期 run3 因「landing 根无新 READY 批次」被 `RUN_EMPTY_LANDING` 拒绝；实际链路为 **consumed manifests 保持 READY + `PipelineService` 发布无条件（publish 不经门禁）+ `LandingManifestSelector` 扫描最新 READY 非空同源清单** → run3 以重放批（checkpoint 判 noNewData）再次发布，D-045 去重保证零新增行，产出值不变的新快照 S_C。driver 的 m3 断言按两种形态皆可接受的修订语义执行（`valuesUnchanged=true` + ACTIVE 漂移如实记录）。该偏差是**发布语义现状**的暴露，非本批引入；是否收紧为 fail-closed 留给总控裁定（记入 D-047）。
2. **F1 run4 flake**：首次 run（id 24）在 BUILD_DWD `tdw` 段命中已知环境坑 spark-local-loopback-jar-download-hang（~12% 卡滞 → `RUN_JOB_FAILED`），非质量门行为；按 retry-tolerant 设计换独立 Idempotency-Key 重试（run id 25）后取得期望的 `PIPELINE_QUALITY_FAILED`。原错误保留于 legs `f1-run4-flake`。
3. **P5 批次文件来源**：计划写「推夹具 `inputs/g3110-hdfs-batch1.jsonl`」；实际由 P5 driver 在 `attempt/hdfs-inputs/` 现场生成同内容文件（batch1=golden-evt-921/922-g3110 两条 behavior view、batch2=golden-evt-923-g3110 一条，与计划钉死 payload 一致），HDFS 侧 sha256 校验一致。
4. **oracle 钉档输入路径**：钉档时 golden50 取 repo fixture、批B 取 `landing/events/` 源文件（oracle-first 时序要求 run2 前出期望值，彼时 accepted/ 尚不存在）；计划「取 accepted/ 交付副本」要求由 P6 追加闭合：逐字节 sha256 全等 + 交付副本复算 == 钉档值。
5. **证据落位形态**：P4 legs 按 driver 顶层键落 `drill-state.json`（非计划的 `legs/m1-*.json` 散件；`legs/` 仅存 g3103 受控停台账）；终验独立状态文件避免覆盖既有证据。
6. **流程偏差：DECISION_LOG 本批未留编辑前备份**——D-047 追加执行于 `*.bak-20260925-g3110` 备份动作之前（CURRENT_BATCH/PROJECT_STATUS 有备份，DECISION_LOG 无；历史备份止于 `bak-20260925-g3109`）。编辑前态可确定重构 = 当前文件去除「### D-047」整段（追加式编辑，无其他改动）；此为流程疏漏，诚实登记不补造"伪备份"。

## 6. mini-gate 锚点变更（影响后续恢复脚本）

后续任何恢复/常驻栈维护脚本的 mini-gate 锚点**必须从 `S20260921_20` 更新为 `S20260901_23`**（本批终态唯一 ACTIVE）。沿用旧硬编码将误判 DRIFT（item3-restore 同款硬编码风险在此显式登记）。

## 7. 边界与未验收范围（随批重申）

- 验收范围 = 当前 WSL 单节点环境；远程集群、共享 Hive Metastore、真实 LLM（G31-06）维持未验收。
- 3306 永久冻结零接触；`V25_IT_*` 口令零落盘零 argv 零 git；3307 root 口令仅经 WSL `MYSQL_PWD`。
- HDFS smoke 数据保留于 `/home/asus/.cache/graduation-hdfs-smoke-20260924/`，conf 未改动（`chmod 777` 为 HDFS 服务端对常驻平台 JVM 无 `HADOOP_USER_NAME` 的运行时放行，非配置变更；复跑需先停平台或按同法放行）。
- push 授权已用尽：仅本地提交，见 §8 分组。

## 8. 归属清单（本批触碰文件，供提交分组）

提交域（git 跟踪）：
- `docs/verification/batches/BATCH-G31-10-D044-5-MERGED-RERUN-PLAN.md`（新增）
- `docs/verification/results/BATCH-G31-10-D044-5-MERGED-RERUN-RESULT.md`（本文件，新增）
- `docs/decisions/DECISION_LOG.md`（追加 D-047）
- `docs/verification/CURRENT_BATCH.md`、`docs/PROJECT_STATUS.md`（备份 `*.bak-20260925-g3110` 后更新）

非提交域（`.gitignore: target/`，盘内证据留存）：`target/v25-it/g3110_20260925_191753/**`（driver/p5/acceptance 脚本、drill-state、evidence/、oracle/、logs/、backup/、jars/、precheck/、migration/、terminal/）。绝不 bulk-tree，不混入其他在途工作。

## 9. 下一步

- 报总控：以本批证据替换 F-G4-1/D-041 拦截项并请求产品验收签收裁定（含 §5-1 M3 发布语义是否收紧）。
- 待总控签收后：G31-06（真实 LLM）与远程集群验证另行开批。
