# 系统演示脚本（答辩/验收用）

> 演示目标：**一条 REST 命令跑通"数据生成 → 采集 → 数仓流水线 → 指标发布 → 看板 → AI 问答 → 决策闭环"**，
> 全程无需进入虚拟机、无需 SQL、无需启动集群（LOCAL 模式，见 `deployment.md`）。

## 准备（约 2 分钟）

```bash
# 终端 1：后端（在 mall-simulator 目录）
export MALL_DB_PASSWORD=你的MySQL密码
mvn spring-boot:run          # 启动后监听 8090，Flyway 自动建库建表

# 终端 2：前端（在 web 目录）
npm install && npm run dev   # http://127.0.0.1:5173
```

## 主线演示（约 3 分钟）

### 1) 一键生成并分析（演示控制台）
浏览器打开 `http://127.0.0.1:5173/pipeline` → 场景选「促销爆发」→ 点「生成并分析」。
页面显示：流水线 run 状态、阶段成功数（7/7）。
或命令行等价：
```bash
curl -X POST http://127.0.0.1:8090/api/v1/generator/runs \
  -H 'Content-Type: application/json' \
  -d '{"userCount":100,"eventsPerSecond":2,"baseConversionRate":0.05,
       "startTime":"2026-09-03T09:00:00","endTime":"2026-09-05T12:00:00",
       "randomSeed":20260903,"dirtyDataRate":0,"scenario":"promotion"}'
curl -X POST http://127.0.0.1:8090/api/v1/ingestion/runs
curl -X POST http://127.0.0.1:8090/api/v1/pipeline-runs \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"runtimeProfileId":1,"pipelineCode":"DAILY_CORE","businessTime":"2026-09-04T00:00:00","sourceDataVersion":"demo"}'
```

### 2) 固定看板（普通员工入口）
`http://127.0.0.1:5173/overview`：指标卡（GMV/净额/客单价/支付订单/UV/DAU…）+ 近 7 日销售趋势 + 活跃趋势。
`/behavior`：转化漏斗（view→intent→order→pay）；`/products`：商品热度 TopN；`/sales`：销售趋势。
每个图表底部显示快照 ID 与数据更新时间（§25.1 口径透明）。

### 3) 智能分析助手（AI 或降级模式）
`http://127.0.0.1:5173/ai` → 输入推荐问题如「转化漏斗各阶段人数」→ 左侧展示：
执行状态（EXECUTED）、真实结果表、SQL 证据（可展开）、结论与限制说明。
特权演示：输入「删除订单数据」→ 系统返回 FAILED（危险 SQL 拦截），数据完好。
> 未配置 `LLM_API_KEY` 时自动走规则回退（页面标注"规则回退模式"）；配置后走真实模型：
> `export LLM_BASE_URL=... LLM_API_KEY=... LLM_MODEL=...` 重启后端即可切换。

### 4) 决策闭环（创新点六）
AI 建议 → `http://127.0.0.1:5173/decisions` 列表出现「补充安全库存」草稿（DRAFT）→
提交审核 → 批准（填负责人，系统自动锁定当前快照基线）→ 开始 → 完成 → 评价。
评价展示：基线值、实际值、改善率、EFFECTIVE/PARTIAL 等结果（含"非因果推断"声明）。

### 5) 数据工程侧（管理员视角）
- `http://127.0.0.1:8090/api/v1/ingestion/status`：采集待处理/断点数
- `http://127.0.0.1:8090/api/v1/metrics/snapshots`：快照版本（ACTIVE/ARCHIVED）
- MySQL 举证：`SELECT * FROM mall_simulator.metric_value WHERE snapshot_id=(最新ACTIVE)`
  `SELECT question,status,sql_text FROM mall_simulator.ai_query_history ORDER BY id DESC LIMIT 5`

## 黄金数据对账演示（1 分钟，答辩加分项）

```bash
cd mall-simulator && mvn -Dtest=GoldenE2ETest test
# 输出：黄金 30 行 → 采集 30 SUCCESS → 流水线 7 阶段 → gmv=1275.00 等 9 项与标准答案一致
```

## 演示规模与耗时（本机实测，§7.9 记录口径）

| 事件规模 | 生成 | 发布 | 流水线 | 看板 P95 |
|---|---:|---:|---:|---:|
| 1.1 万 | ~30s | ~20s | <1s | 21-29ms（30 并发） |
| 10.8 万 | ~290s | ~145s | 1.3s | 21-29ms |

> 论文引用时必须标注本机配置（CPU/内存/MySQL 版本），见 `experiments/README.md`。