# 系统演示脚本（答辩/验收用）

> 演示目标：**一条真实链路跑通「数据来源 → 采集 → 数仓流水线 → 指标发布 → 看板 → AI 问答 → 决策闭环」**，
> 全程无需进入虚拟机、无需集群（LOCAL 模式，见 `docs/deployment.md`）。
>
> **边界（先讲清楚，避免被问倒）**：本系统是**三个独立程序**：分析平台、模拟商城、合成数据生成器
> —— 三进程、三端口、三套库、三份前端/构建产物。
> 平台 `platform-app` = `:8091`（`analytics_meta` + `analytics_metric`，**分析入口**）；模拟商城 `mall-simulator` = `:8090`（`mall_simulator`）；
> 合成数据生成器 `synthetic-data-generator` = `:8092`（独立元数据库 `generator_meta`，只负责造数）。
> 每个端点只属于一个程序：打错端口就是 404，不存在"同一个端点两个程序都提供"。
> 平台**不依赖**商城即可运行——黄金链路只用磁盘上的 55 行黄金 JSONL 作为来源（`docs/compatibility-matrix.md` L0 段）。

## 准备（约 2 分钟）

```pwsh
# 一键起三进程（平台 8091 + 商城 8090 + 生成器 8092；含前端构建产物）
pwsh -File scripts/start-all.ps1
#   只演示分析平台：  pwsh -File scripts/start-all.ps1 -PlatformOnly
#   只演示模拟商城：  pwsh -File scripts/start-all.ps1 -MallOnly
#   只造数据：        pwsh -File scripts/start-all.ps1 -GeneratorOnly
# 商城库口令：$env:MALL_DB_PASSWORD（默认取本机 LOCAL 演示口令）

# 前端（开发模式，可选）：web/ 下 npm install && npm run dev → http://127.0.0.1:5173（代理到 8091）
```

登录拿令牌（**两个程序各登各的，令牌不通用**：除登录/健康检查外，平台与商城全部接口都要 `Authorization: Bearer`）：

```bash
# 平台令牌（种子账号见 analytics-server/platform-app/src/main/resources/db/meta/V5__platform_users.sql）
TOKEN=$(curl -s -X POST http://127.0.0.1:8091/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"analyst","password":"<种子口令>"}' | jq -r '.data.token')

# 商城令牌（商城自己的会话，用来打 :8090 的商城端点；用平台 TOKEN 打商城会 401）
MALL_TOKEN=$(curl -s -X POST http://127.0.0.1:8090/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.token')
```

## 主线演示（约 3 分钟）

### 1) 造一批来源数据（生成器侧，:8092）

生成器是**独立程序**（不属于平台，也不属于商城）。它的 HTTP API 只有 `POST /api/v1/generation-runs`，
且**只接受 `{plan_id, version}`** —— 计划版本只能由生成器自己的 CLI 创建（契约里没有"创建计划"端点）：

```bash
# 第一步：追加一个不可变计划版本（CLI，生成器侧；输出 plan_id/version/event_count）
java -jar synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar \
  --generator.cli=plan-append --plan-id=demo --scenario=promotion --seed=20260903 \
  --start=2026-09-03T09:00:00 --end=2026-09-05T12:00:00 --event-count=5000

# 第二步：启动该计划版本的运行（CLI 或 HTTP 均可）
curl -X POST http://127.0.0.1:8092/api/v1/generation-runs \
  -H 'Content-Type: application/json' \
  -d '{"plan_id":"demo","version":1}'          # → {"runId":"..."}

# 产物落在生成器自有目录（./generator-output，synthetic=true），不写平台 landing
curl -s http://127.0.0.1:8092/api/v1/generation-runs/<runId>/artifacts
```

> ⚠️ **不要照抄旧命令**：`POST http://127.0.0.1:8090/api/v1/generator/runs` 已**不存在**（M1-7 把生成器整体
> 移出商城，`/api/v1/generator/**` 随之下线，实测带 token 打 8090 该路径为 **404**）。
> 另注意参数语义已变：旧脚本的 `eventsPerSecond` 对应 CLI 的 `--rate`，且**文件模式不生效**
> （`FileModeGenerationEngine.java:59`）；事件总量改由 `--event-count` 给定。

### 1b) 采集来源数据（商城侧，:8090；与上一步互不依赖）

```bash
# 商城 Outbox：查看积压 → 手动触发发布（平台采集消费的是发布出来的 landing 文件）
curl -s -H "Authorization: Bearer $MALL_TOKEN" http://127.0.0.1:8090/api/v1/mall/outbox/status
curl -s -X POST -H "Authorization: Bearer $MALL_TOKEN" http://127.0.0.1:8090/api/v1/mall/outbox/publish
```

### 2) 采集 + 流水线（平台侧，:8091）

```bash
# 采集：从活动档案（GET /api/v1/runtime-profiles）配置的 landingUri 读取待处理文件
curl -X POST http://127.0.0.1:8091/api/v1/ingestion/runs -H "Authorization: Bearer $TOKEN"

# 流水线：8 个阶段 WAIT_LANDING→…→PUBLISH_METRIC，立即返回 runId（异步执行，用 GET 查进度）
curl -X POST http://127.0.0.1:8091/api/v1/pipeline-runs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"runtimeProfileId":1,"pipelineCode":"DAILY_CORE",
       "businessTime":"2026-09-04T00:00:00","sourceDataVersion":"demo"}'

curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8091/api/v1/pipeline-runs/1  # 查 run 与阶段状态
```

> 说明：`POST /api/v1/pipeline-runs` 返回 **HTTP 200 + body(snapshot/runId, 阶段 PENDING…)**，异步语义靠 `runId` 查询，
> **不是** HTTP 202（指导书 §22 的 202 状态码未实现，已在审计中登记）。

### 3) 固定看板（普通员工入口）

`http://127.0.0.1:5173/overview`：指标卡（GMV/净额/客单价/支付订单/UV/DAU…）+ 销售趋势 + 活跃趋势。
`/behavior`：转化漏斗（view→intent→order→pay）；`/products`：商品热度 TopN；`/sales`：销售趋势；`/rfm`：RFM 八类分群。
每个图表底部显示**快照 ID / 数据更新时间 / 质量状态**（口径透明）；加载/空/错误/过期四态有独立呈现。

### 4) 智能分析助手（AI 或规则回退）

`http://127.0.0.1:5173/ai` → 输入推荐问题（如「转化漏斗各阶段人数」）→ 展示：执行状态（EXECUTED）、
真实结果表、SQL 证据、结论与限制说明（所有数字来自 `EvidencePackage` 并带 `evidenceRef`）。
特权演示：输入「删除订单数据」→ 返回 `SQL_QUESTION_UNSAFE`（生成 SQL 之前即拒绝），数据完好。
> 未配置 `LLM_API_KEY` 时自动走模板/规则回退（页面标注"规则回退模式"，`ai_call_log` 为 0 行——**不冒充真实模型**）；
> 配置 `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`（即 `llm.base-url`/`llm.api-key`/`llm.model`）后重启后端即切换。

### 5) 决策闭环

决策草稿（AI 只能写 `DRAFT`）→ `http://127.0.0.1:5173/decisions` 提交审核 → 批准（填负责人，锁定当前快照为基线）→
开始 → 完成 → 评价。评价展示基线值/实际值/改善率与 `INSUFFICIENT_DATA` / `EFFECTIVE` 等结论（含"非因果推断"声明）。
> 样本不足时如实返回 `INSUFFICIENT_DATA`（当前真库 4 条评价全为该状态），不编造效果。

### 6) 数据工程侧（管理员视角）

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8091/api/v1/ingestion/status    # 采集待处理/断点
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8091/api/v1/metrics/snapshots   # 快照版本 ACTIVE/ARCHIVED
```

```sql
-- 指标库唯一所有者：analytics_metric（不是商城库）
SELECT snapshot_id, status, version, business_time FROM analytics_metric.metric_snapshot ORDER BY id DESC LIMIT 5;
SELECT metric_code, metric_value, unit, period FROM analytics_metric.metric_value
 WHERE snapshot_id = (SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE status='ACTIVE');
-- AI 审计（库在 analytics_meta）
SELECT question, status, sql_text FROM analytics_meta.ai_query_history ORDER BY id DESC LIMIT 5;
```

## 黄金数据对账演示（1 分钟，答辩加分项）

```bash
# 黄金链路：磁盘上 55 行黄金 JSONL（tests/golden-dataset/events/golden-20260901.jsonl）作为唯一来源
# 1) 采集 + 2) 流水线（同上面的两个 POST，businessTime=2026-09-01T00:00:00）→ 产出唯一 ACTIVE 快照
# 3) 对账证据（真实落档，可直接打开）：docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/
#    20-reconciliation.tsv  黄金标准答案 vs 指标库 ACTIVE 逐项
#    15-hive-layers.txt     ODS/DWD/DWS/ADS 四层行数与金额
#    03-spark-job-run.tsv   10 条真实 Spark 作业（作业码/外部作业 id/输入输出行数）
```

黄金标准答案（与 `20-reconciliation.tsv` 逐项一致）：
`pv 7`、`uv 3`、`dau 3`、`paid_order_cnt 5`、`gmv 2042.00`、`net_sale 1493.00`、`avg_order_value 408.40`、
`refund_rate 0.6000`、`full_refund_rate 0.2000`、`buy_rate 1.0000`。
> 唯一注意点：`full_refund_rate` 是 R7-0 新增口径，黄金文件未列该值，故 A 段对账标 `—`（只与 ADS 宽表互校）。
> 遗留脚本 `scripts/run-spark-chain.ps1` 只覆盖 11 个作业中的 5 个，**不得作为链路证据**（README 已标注）。

## 演示规模与耗时

| 事件规模 | 生成 | 发布 | 流水线 | 看板 P95 |
|---|---:|---:|---:|---:|
| 1.1 万 | ~30s | ~20s | <1s | 21–29ms（30 并发） |
| 10.8 万 | ~290s | ~145s | 1.3s | 21–29ms |

> ⚠️ **上表是 2026-09-06 整改前旧链的本机实测**（`experiments/`），本轮未在整改后平台重测 P95；
> 论文引用时须标注为旧链数据并注明本机配置（CPU/内存/MySQL 版本），见 `experiments/README.md`。
> 1M–100M 分档与集群对照**未做**（无 Hadoop/Hive 集群）。
