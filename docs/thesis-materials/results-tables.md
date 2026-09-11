# 实验结果汇总表（第八章写作素材）

> 本表汇总当前全部**实测**数据，论文引用时挂接 `experiments/*.json` 与环境指纹
> （`experiments/env-profile.json`）。未测项一律留白，不预填。
>
> **2026-09-11 校订**：依据 `docs/acceptance/r9-20260911-fabc6cb-run22-S20260901_22/` 与
> `.verify/r8-accept-report.json`（53/53）、`.verify/r8-evidence-truncation-proof.json`（12/12）、
> `.verify/r7-4-dom-report.json`（22/22）逐项复核。本次修订**删除/标注了 4 处无留档数字**，
> 并修正了黄金对账整表。当前 ACTIVE 快照 = `S20260901_22`（`S20260901_23/24` 已 ARCHIVED，
> R7-3/R7-4 时点引用的 `S20260901_24` 已不是当前值）。
> 逐条映射与缺口清单见同目录 `证据映射表.md`。

## 1. 黄金数据全链路对账（§27.1）

| 指标 | 标准答案 | 实测 |
|---|---:|---|
| pv / uv / dau | 7 / 3 / 3 | ✅ 一致（`11-metric-value-active.tsv`、`13-ads-overview-active.tsv`） |
| paid_order_cnt / buy_users | 5 / 3 | ✅ 一致 |
| gmv / net_sale | 2042.00 / 1493.00 | ✅ 一致 |
| avg_order_value / refund_rate / buy_rate | 408.40 / 0.6000 / 1.0000 | ✅ 一致 |
| full_refund_rate | 0.2000 | ✅ 一致（证据在真库 IT：`AnalysisGoldenMySqlIT.java:84`，不在标准答案文件中） |

证据链：
- 标准答案：`tests/golden-dataset/expected/golden-20260901-expected.json:17-31`；
- 夹具规模：`tests/golden-dataset/events/golden-20260901.jsonl` **55 行**（接受 52 / 拒绝 3，
  见证据包 `20-reconciliation.tsv` 的 L1 行）；
- 真库断言：`analytics-server/metric-analysis/src/test/java/.../AnalysisGoldenMySqlIT.java`
  **6/6**（需 `-Dmetric.it=true`，读 ACTIVE 快照）；
- 全链：`runId=22`、八阶段 SUCCESS（records 51/37/51/28/3/22/6/44）、ACTIVE=`S20260901_22`、
  **黄金不一致项 0**。

> ⚠️ **旧版本（本次已更正）**曾写 pv/uv/dau = 6/3/3、paid/buy_users = 2/2、
> gmv/net_sale = 1275.00/1225.00、aov/refund_rate/buy_rate = 637.50/0.50/0.6667，
> 且称夹具为"30 行"、"流水线 7 阶段"、证据类为 `GoldenE2ETest`。**这四项全部失效**：
> 数值是 R7-0 口径统一（提交 `1615b1c`）之前的旧值；`GoldenE2ETest` 该类**已删除**（现有替代为上列真库 IT + 证据包 tsv）。

## 2. Text-to-SQL 安全与可用性（§10.3/§10.5，**Mock 基线**）

| 指标 | 结果 |
|---|---:|
| 测试集规模 | 100 条（50 安全 + 50 越权，**8 类**：filter/agg/sort/time/dimension/join/window/privilege） |
| 安全题执行成功率 | 50/50（100%，Mock） |
| 越权题未得逞 | 50/50（Mock）—— ⚠️ **不得作为拦截能力证据，见下** |
| 修复触发次数 | 58 |
| 平均端到端耗时 | **12ms**（最新产物 `ai-eval-2026-09-10-210756.json`；旧稿写 9ms 与之不一致） |
| 对照组（Baseline A/B/C/D） | 待真实 LLM key 后运行（`LLM_API_KEY`） |

> ⚠️ **"越权题 50/50 未得逞"是假绿**（R8 缺陷②）：攻击问法经规则回退被无害化改写成普通
> SELECT，AST 校验器从未触发。真实拦截证据必须改用 `.verify/r8-accept.ps1` →
> `.verify/r8-accept-report.json` **E 组 7 条 PASS**（问句层 `SQL_QUESTION_UNSAFE` 拒绝、
> `rejected_rows=12`）。
> ⚠️ 本表全部数字来自 **Mock/模板路径**，`09-ai-call-log.tsv` 为 **0 行**（无任何真实 LLM 调用），
> 论文引用时必须标注"Mock 基线，非真实模型实测"。

## 3. 看板 API 性能（30 并发 × 600 请求）

| API | 优化后 P95 | 数据来源 |
|---|---:|---|
| metrics/overview | 24ms | `experiments/perf-web-tier1.json` |
| analysis/sales | 21ms | 同上 |
| analysis/funnel | 21ms | 同上 |
| dashboards/overview | 29ms | 同上 |

四项均 `requests=600` / `concurrent=30` / `errors=0`。优化手段：30s 快照级 TTL 缓存
（指标随快照发布变化，语义正确）。达成 §2.2 目标（看板 P95 ≤2s）。

> ⚠️ **"优化前 P95 3.9s / 3886ms / 3728ms / 7383ms"及 ≈185×/177×/254× 提升倍数无任何留档**：
> `experiments/` 内只有优化后产物，仓库无优化前记录。本次已从表中删除。
> 若论文要保留前后对比，需补测并落盘 `experiments/perf-web-tier1-before.json`
> （关闭 TTL 缓存后用同一压测口径重跑），**不得用估算值或旧截图冒充**。

## 4. Spark 数据规模扩展（§10.2 实验一·单机部分）

| 事件数 | odl | bdw | usw | 吞吐(odl) |
|---|---:|---:|---:|---:|
| 14,400 | 10.9s | 12.2s | 11.2s | 0.13 万/s |
| 64,800 | 13.7s | 15.2s | 14.2s | 0.47 万/s |
| 129,600 | 11.1s | 12.4s | 10.2s | 1.17 万/s |

来源：`experiments/spark-scale-local-20260906.json` + `spark-chain-local-*.json`。
观察：约 10s 为 JVM/Spark/Derby 固定开销；吞吐随规模提升（启动摊薄）。

集群分档（100 万-1 亿）+ 广播/并行度对照：**待环境**。
> ⚠️ 不要写"脚本已备"了事：`scripts/run-spark-chain.ps1` 是**遗留脚本**，
> 跳过 `tdw`/`dim` 两个作业且默认仓为 `D:\Develop\tmp\spark-warehouse`（与平台仓不同），
> 不能作为"全链一键复现"或集群提交证据。平台链提交以 `PipelineService` +
> `SparkStageExecutor` 为准（证据包 `.jar` 见 `19-spark-jobs.jar`）。

## 5. 决策闭环示例（§22.7）

**⚠️ 本节原有两个 `EFFECTIVE` 示例（补充安全库存 gmv 3702.50 → 4320.00 +16.68%；
退款治理 refund_rate 0.12 → 0.08 +33.33%）在仓库内无任何验收证据，本次已删除。**
真机当前状态：`07-decision-task-by-status.tsv` 显示决策分布为
`INSUFFICIENT_DATA` **4** + `DRAFT` **4**，**没有任何 `EFFECTIVE` 记录**。

可写的真实内容是**评价口径**（有代码与验收证据）：

| 要素 | 口径 | 代码/证据 |
|---|---|---|
| 等长窗口 | 观察窗与对照窗等长 | `DecisionService.java:528-544` |
| 样本不足 | → `INSUFFICIENT_DATA`（可回到 `EVALUATING`） | `DecisionStateMachine.java:34-47` |
| 改善率 | `diff / baseline.abs()`，scale4 HALF_UP；`DIRECTION_DOWN` 取反 | `DecisionService.java:358-367` |
| 分级阈值 | effective ≥ 0.05；partial ≥ 0；窗口 3 天 | `DecisionService.java:63-69` |
| 验收 | F 组 12 条 PASS | `.verify/r8-accept-report.json` |

若论文需要真实的 `EFFECTIVE` 记录，需补做：连续两个业务日各发布一次快照 →
触发 `POST /api/v1/decisions/{id}/evaluate` → 留存 evaluation JSON（见 `证据映射表.md` 缺口 P0-2）。

## 6. 数据规模（LOCAL 全链路，MySQL + Landing）

| 事件数 | 生成 | 发布 | 采集 | 流水线(Java) |
|---|---:|---:|---:|---:|
| 14,400 | ~90s | ~30s | ~6s | <1s |
| 64,800 | ~300s | ~90s | ~20s | ~1s |
| 129,600 | ~600s | ~150s | ~35s | 1.3s |

> ⚠️ **本节数字来源未在本目录标注，2026-09-11 复核未找到对应落盘产物**，论文引用前需
> 补记来源（脚本 + 日志）。可对账的权威规模产物只有
> `experiments/spark-scale-local-20260906.json`（Spark 侧耗时）与
> `experiments/perf-web-tier1.json`（30 并发压测）。
> 另注意 `docs/acceptance-checklist.md:28-33` 写的是 1.1 万 / 10.8 万（Landing 50 文件 383MB），
> 与本节及 experiments 的 1.44/6.48/12.96 万是**两套口径**，论文需统一
> （R9 已把 `docs/acceptance-checklist.md` 重写为整改后口径，旧的 1.1 万/10.8 万表述已删除）。

## 7. 故障注入与可靠性（§23.3，2026-09-11 实测 → 论文表 8-6）

原始证据：`docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/` 下
`21-reliability-experiments.tsv`（逐条 PASS/FAIL）、`22-qualityfail-run26.json`、
`23-qualityfail-isolation.sql`、`26-prune-fix-verification.tsv`（10/10 PASS）、
`27-prune-fix-verify.log`、`28-prunefix-run.json`、`29-resume-evidence.tsv`、
`31-metric-publish-it-regression.log`。

| 编号 | 注入的故障 | 观察点 | 实测结果 |
|---|---|---|---|
| C1 | 同键重复提交 | 是否产生第二个 runId | 同一 `runId=25`，DB 1 行 |
| C2 | 同键 6 次并发提交 | 并发下是否重复执行 | 唯一 runId、DB 1 行 |
| C3 | 阶段失败后 `retry-from-stage` | 是否重跑已完成阶段 | 前 5 阶段与后 3 阶段时间戳间隔 **800.1 分钟**，完成阶段未重跑 |
| C4 | 篡改夹具 `paid_amount=0.01` 触发质量门 | 正式层/ACTIVE 是否被污染 | run 26 终态 `FAILED`/`PIPELINE_QUALITY_FAILED`；ACTIVE 未切换、快照总数未增、黄金行未被改写 |
| C5 | 杀进程重启后 `resume` | 状态是否丢失、是否只续跑失败阶段 | 状态由 DB 复原；`HTTP 200 {"code":"OK","attemptNo":2}`；仅新增 1 行 `QUALITY_CHECK`（10:54:16→10:54:28），前 6 阶段时间戳不变；**未**误判 SUCCESS |
| C6 | 快照字典版本不符 | 是否写库 | 真库 IT 拦截 `MP_VERIFY_FAILED`/`MP_METRIC_DICT_VERSION`（`-Dmetric.it=true`，1/1 PASS、0 skipped） |

本轮由真实链挖出并修复的两个缺陷（详细复验数字见 `26-prune-fix-verification.tsv`）：

| 缺陷 | 症状（修复前实测） | 修复 | 复验（修复后实测） |
|---|---|---|---|
| **D-R9-1** `AdsPublishJob` 暂存清理缺业务日期限定 | 发布一个业务日期会删掉**其他日期**未发布的 `__staging` 分区；交错运行时 8 张 ADS 表全空 → `ADS_STAGING_PRESENT` 阻断，且该失败运行的 `resume` 永久失败 | 清理前按 `p.dt == dt` 过滤 | `S20260907_TEST/dt=20260907` 存活；`S20260901_29/dt=20260901` 仍被回收；证据文案含 `dt=20260901`（P2/P3/P4） |
| **D-R9-2** `TradeDwdJob` 维度关联未按生效日期过滤 | `dim_user`/`dim_product` 每日一份快照 → 关联笛卡尔放大：订单明细 7→**28 行**、GMV 2042.00→**8168.00**、净销售额 1493.00→**5972.00** | 两处 JOIN 增加 `AND dt = '$dimDt'` | `dwd_order_detail=7`、`dws_trade_day=5/2042.00/1493.00/408.40`、ACTIVE 指标=黄金（P7/P8） |

> ⚠️ **不可扩大表述**：C5 的"杀进程→重启→`resume`→SUCCESS"整段闭环只在**当时仍带缺陷**的代码上
> 走完（该失败运行暂存已不可恢复，属既有损伤）；修复后验证的是"重启可恢复 + 仅重跑失败阶段 +
> 全链重跑 SUCCESS"（run 30）。论文不得写成"修复后完整重录了一次杀进程恢复流程"。
