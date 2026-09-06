# Slice06: 分析 API + Vue 3 看板 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阶段 7 前半：后端分析 API（§24.3 /analysis/*、/dashboards/overview）+ Vue 3 + ECharts 看板前端（web/），普通员工可直接浏览经营大盘、漏斗、商品、销售与流水线状态。

**Architecture:** 后端 `AnalysisService` 从 `landing/events` 实时解析事件并按指标字典口径聚合（销售趋势=order_paid 按日、商品热度=§21.7 对数公式、漏斗=§21.4 四阶段、活跃=按日 dau/行为量）；大盘合并最新 ACTIVE 快照。前端 Vite+Vue3+Router+ECharts+axios，`/api` 由 dev server 代理到 8090；页面按业务组织（views/overview、views/behavior、views/products、views/sales、views/pipeline、views/ai），图表只接收页面 DTO。

**Tech Stack:** Java 17 / Spring Boot 3.2.5（后端）；Vite 5 / Vue 3.4 / vue-router 4 / ECharts 5 / axios（前端）；Node 24 本机可用。

**外部约束（V2.2 文稿）:** §5.5 用户行为分析（漏斗=去重用户口径+行为次数口径分离）、§5.6 商品/销售分析（TopN 可配置、汇总与 ADS 一致）、§24.3 API 清单、§11.2 页面结构。

---

### Task 1: 后端分析服务与接口

**Files:** `analysis/AnalysisService.java`（salesTrend(start,end) / productRank(topN) / funnelDay(date) / userActiveTrend(start,end) / overview()）、`analysis/AnalysisDtos.java`、`controller/AnalysisController.java`（GET /api/v1/analysis/sales|products|funnel|users、GET /api/v1/dashboards/overview）
- salesTrend：order_paid 按 event_time 日期聚合（orderCount/saleAmount/buyerCount）
- productRank：按商品聚合行为计数 → heat=§21.7 公式排序 → TopN（含 product 名来自行为 payload + 商品表 join）
- funnelDay：宽松用户四阶段（复用 MetricCalculator 逻辑的展示版）
- overview：最新 ACTIVE 快照指标 + 近 7 日销售趋势 + 近 7 日活跃趋势
- Test: `analysis/AnalysisServiceTest`（黄金数据：sales 当日 gmv=1275；productRank 热度排序与公式一致；funnel 4 阶段人数；overview 含快照值）

### Task 2: web/ 前端工程

**Files:** `web/package.json`、`vite.config.js`（proxy /api→8090）、`index.html`、`src/main.js`、`src/router.js`、`src/api.js`（统一 traceId/code 处理）、`src/App.vue`（侧边导航）
- 页面：`views/Overview.vue`（指标卡+销售趋势+活跃趋势）、`views/Behavior.vue`（漏斗图+行为分布）、`views/Products.vue`（热度 TopN 条形图+表格）、`views/Sales.vue`（销售趋势+客单价）、`views/Pipeline.vue`（run 列表+触发+阶段明细）、`views/AiAssistant.vue`（占位，阶段 8）
- ECharts 封装 `src/components/BaseChart.vue`（resize 自适应）

### Task 3: 构建与端到端验证

- [ ] `mvn test` 全绿（56+新增）
- [ ] 生成 3 天数据（09-01/09-02/09-03 窗口）→ 流水线 → 分析 API 返回多日结构
- [ ] `npm install && npm run build` 通过；dev server 启动，curl 首页 200
- [ ] 更新 README（阶段 7 部分）、提交

**验收（本轮完成定义）：** 后端 5 个分析端点返回与口径一致且经黄金数据验证；前端 build 通过、dev 可访问、六个页面骨架与 API 联通（proxy 冒烟）。