# BATCH-W 计划：Stage 7 真实浏览器 E2E（登录 + 分析页面 + AI/决策 fallback 展示）

> 状态：**READY**（2026-09-19 总控裁决：「推送成功后立即编制 BATCH-W Browser E2E READY 计划；范围仅真实浏览器 + 8091 + 3307-only 隔离数据，继续复用 BATCH-V 的启动前/后 JDBC 3307 fail-closed guard，不包含真实 LLM、第二来源、故障注入或 REMOTE_CLUSTER。计划完成后可连续执行到 PASS、明确 FAIL/BLOCKED 或 HARD DECISION。」）
> Exact source/test SHA：`883dcffa6136fef96385ebcd475550a71fa22310`（= 当前 HEAD；相对 `104db41` 仅有 docs-only 提交，代码态恒等，不触发重钉。本批零仓内代码/配置变更；驱动与产物全落 `target/v25-it/<RunId>/` 与 gitignore 覆盖的构建输出目录）
> Branch context：`feature/v3-development`（远端 HEAD `883dcff`，2026-09-19 总控授权 fast-forward push `836faca..883dcff` 已完成并复核）
> Predecessor：BATCH-V **PASS**（attempt-2 收口，结果 `docs/verification/results/BATCH-V-STAGE7-PLATFORM-FLUME-RAW-INGESTION-RESULT.md` §11–§12；F1/F2a/F2b/F3 守卫模式直接复用）
> RunId：`stage7w_20260919_223000`（**仅作 attempt/证据目录标签，不新建任何数据库**——被测数据域复用既有 `stage7q1_20260918_152245_*` 四库，见 §3）

## 1. 范围钉死（原裁决口径，不扩大）

- **验证对象**：真实浏览器（Chromium，browser-use 主代理直接驱动）× 真实 8091 平台 × **实际前端构建产物**（`web/` Vue3 + Vite `npm run build` → dist → 进 platform jar `static/`，生产形态同源访问 `http://127.0.0.1:8091/`，无 CORS、无 devServer 代理替代）。
- **数据**：**3307-only** 隔离库（WSL MySQL 3307，`stage7q1_20260918_152245_analytics_meta/_analytics_metric` 等四库）。全程对正式 3306 **零接触**（只读也算接触，一律不做）。
- **页面清单**（路由 `web/src/router.js`）：
  - **必验六类**（裁决点名）：登录 `/login`；经营概览 `/overview`；销售分析 `/sales`；行为分析 `/behavior`；RFM/用户画像 `/rfm`；AI `/ai` + 决策 `/decisions`。
  - **导航健康附带**（同批顺访，同受 W-4 约束）：`/products`、`/pipeline`、`/ops`。
- **AI 页面边界**：只验证 UI 渲染、后端接口（`POST /ai/queries`、`GET /ai/history/my`）与**当前 fallback/provenance 展示**（`providerUsed`、`limitations`、证据包 `snapshotId`）。**不声称真实 LLM Provider 已验**——平台启动环境不含任何 LLM API key，预期走 `RuleBasedSqlFallback` + `template` 解释回退，本批如实验证并展示该回退链。
- **明确排除**：真实 LLM Provider 调用（BATCH-X）；第二异构来源（BATCH-Y）；故障注入/故障样本（BATCH-Z）；REMOTE_CLUSTER / HDFS-Hive 环境验收（BATCH-AA）；Spark/ingestion/pipeline 实链重跑；业务口径/阈值/迁移/质量规则修改；顺带修改任何仓内脚本。

## 2. 被测对象与环境事实（执行前已实查）

| 项 | 事实 | 出处 |
|---|---|---|
| 前端 | Vue 3.4 + Vite 5，`npm run build` → `web/dist`；`scripts/build-web-and-package.ps1` [1/6]–[3/6] = 构建 → 拷贝 dist → `mvn -pl platform-app -am clean package -DskipTests` | `web/package.json`、`scripts/build-web-and-package.ps1:24-44` |
| 托管 | 8091 同进程托管静态资源（jar `BOOT-INF/classes/static`），`SpaFallbackController` 回退 `/index.html`；入口 `http://127.0.0.1:8091/` | `SpaFallbackController.java:16-19`、`application.yml:2` |
| gitignore | `web/dist`、`web/node_modules`、`analytics-server/platform-app/src/main/resources/static/` 均被覆盖（`git check-ignore` 已验证）⇒ 构建不污染工作树 | `.gitignore:9,10,51` |
| 登录 | `POST /api/v1/auth/login`；种子 `admin/admin123`（V5 BCrypt 预置，`sys_user`=3 已实查）；前端 localStorage `analytics_token`；路由守卫未登录 → `/login` | `V5__platform_users.sql`、`router.js:25-36`、`AuthController.java:43-63` |
| 数据 | `S20260918_12` = **ACTIVE**（另 S20260901_4 ARCHIVED、S20260918_11 FAILED）；ADS 行数实查：sale=2 / funnel=8 / active=2 / hot=4 / conv=4 / quality=8；`decision_task`、`ai` 相关表在 meta 库 | 3307 只读实查（2026-09-19 22:0x） |
| API 对照 | overview=`GET /dashboards/overview`（AnalysisController:47）；sales=`/analysis/sales`（:56）；funnel=`/analysis/funnel`（:84）；rfm=`/analysis/rfm`（:101）+ users=`/analysis/users`（:92）；products=`/analysis/products`（:70）；快照=`/metrics/snapshots`、`/metrics/overview`（MetricController:36-56）；AI=`POST /ai/queries`（AiController:117）；ACTIVE 解析=`metric_snapshot WHERE status='ACTIVE'`（MetricAdsReader:44） | Explore 调研（文件:行号） |
| 空态机制 | `ChartState.vue` 四态壳；`NO_ACTIVE_SNAPSHOT` 等信封告警文案（`utils/envelope.js:70-79`）；决策空列表「暂无决策」；AI 空范围「当前时间范围无数据，不编造结论」 | Explore 调研 |
| 端口/进程 | 8091 必须空闲；3307 由 WSL 提供（Windows netstat 看不到 WSL2 NAT 端口，用 TCP 连接测试）——BATCH-V 已登记先例 | CURRENT_BATCH.md |

## 3. 数据域决策（3307-only，零数据破坏）

- **复用 T-R3 的 RunId-scoped 四库**（`stage7q1_20260918_152245_*`）：其中已含完整发布快照 `S20260918_12`（ACTIVE）与 46 行 ADS 数据——这是六类页面数字锚点的唯一同源数据基础。本批**不重跑 Spark/发布链**（那属其它批次），也不新建库（新建库无已发布快照 ⇒ 只能验空态，不满足 W-5）。
- **账号口令重置**：仓内幂等 prep 脚本 `scripts/it-prepare-isolation.ps1 -RunId stage7q1_20260918_152245 -IncludeAnalytics -Confirm -AllowRootOnIsolated`（**原样调用、零修改**）：`CREATE DATABASE/USER IF NOT EXISTS` + `ALTER USER` 重置口令 + GRANT，**零 `DROP`**（已实查脚本 189–208 行）——数据完好性由 P2 后置断言保证（快照与 ADS 行数与 prep 前逐值相等）。
- **口令通道**：沿用已四次登记的先例——`V25_IT_META_PASSWORD` / `V25_IT_METRIC_PUBLISH_PASSWORD` 由驱动进程内 crypto RNG 新生成，只走 PowerShell Process env（零落盘/零入参/零 git）；root 仅在 WSL mysql 进程内经 `MYSQL_PWD` 使用 W03 文档化值。
- **平台数据源**：F1 env 块的 meta/publish/read 三 URL 全部指向 `jdbc:mysql://127.0.0.1:3307/stage7q1_20260918_152245_analytics_meta|_analytics_metric`（read=publish 同值，BATCH-V 先例）。

## 4. PASS 判据（W-1~W-9，全部满足才算 PASS）

- **W-1 隔离守卫（复用 BATCH-V F1/F2a/F2b/F3）**：F1 = 启动前 13 变量 `PLATFORM_*` env 逐键读回 13/13 精确一致、URL 全 3307-scoped；F2a = 启动前 fail-closed 自检（任何实际 JDBC 指向 3306 立即 exit 12）；F2b = 启动后日志守卫（startup log 必含两个 `jdbc:mysql://127.0.0.1:3307/stage7q1…` URL + landing root、零 `:3306`，违反 exit 13）；F3 = 驱动 finally `Stop-OwnedProcessTree` 停止平台进程树；收尾 final sweep 零 `:3306`。
- **W-2 真实浏览器**：页面验证全部经真实 Chromium（browser-use 主代理直接驱动：打开、点击、输入、刷新、后退、截图）；curl/HTTP 客户端仅用于 P0 预检与健康探活，**不得替代任何页面级验证**。
- **W-3 登录与导航**：① 未登录访问受保护页被重定向 `/login`；② 错误口令登录显示错误且不入系统；③ `admin/admin123` 登录成功、`/auth/me` 返回 admin；④ 六类必验页逐页可达：侧边栏点击 + 直接 URL 输入两种方式；⑤ 每页 F5 刷新后仍正常渲染（token 持久化）；⑥ 浏览器返回键行为正常；⑦ 登出后回 `/login`。
- **W-4 渲染健康**：每个到访页面无白屏（断言页面特征元素存在）；浏览器 console **零未处理 JS exception**（error 级；warning 不判红）；关键 API 响应 2xx（network 摘要逐页留证）。
- **W-5 数字锚点同源对账**：六类必验页每类 ≥1–2 个页面渲染数字 == 该页后端 API JSON 同字段 == 3307 只读 SELECT 对应 ADS 表值，三者一致（页面截图含该数字）。对照表：overview↔`/dashboards/overview`↔KPI 源表；sales↔`/analysis/sales`↔`ads_sale_trend_m`；behavior↔`/analysis/funnel`↔`ads_behavior_funnel_m`；rfm↔`/analysis/rfm`+`/analysis/users`↔RfmService 源表（执行时按 RfmService 实读表枚举）；ai↔证据包 `snapshotId`+返回行数；decisions↔`decision_task` 计数。API JSON 与 3307 SELECT 双留证。
- **W-6 ACTIVE 快照一致**：每个分析页 `AnalysisContext` 展示的快照标识 == `S20260918_12` == `metric_snapshot` 表 ACTIVE 行 == `/metrics/snapshots` 列表 ACTIVE 标识 == AI 证据包 `snapshotId`。
- **W-7 空态正确**：① 决策中心空列表渲染「暂无决策」类空态（不异常、不伪造 0 数据行）；② 首次 AI 查询前 AI 历史为空态；③ AI 空数据时间范围查询返回空态语义（「不编造结论」类文案 + `limitations` 说明），不异常、不伪造数字。
- **W-8 证据归档**：`target/v25-it/stage7w_20260919_223000/` 下：每页截图、每页 console 摘要、每页 network 摘要、对账 JSON（锚点三源值）、prep/守卫/平台启动日志、jar SHA256、驱动 console 全文、`evidence-summary.json`。
- **W-9 边界合规**：零 3306 接触（F2a/F2b + final sweep）；零仓内 tracked 文件变更（`git status --porcelain` 收尾仅 `.zcode/` 与 gitignore 覆盖物）；平台环境无 LLM API key（AI 回答走 fallback 链并如实展示，**不声称真实 Provider 已验**）；无 REMOTE_CLUSTER/第二来源/故障注入；阈值/迁移/质量规则零改动。

## 5. 执行阶段（总控已授权连续执行至 PASS / 明确 FAIL/BLOCKED / HARD DECISION）

- **P0 预检**：git 工作树干净（`git diff 883dcff --stat` 空）；node/npm 可用；3307 TCP 可达；8091 空闲；RunId 证据目录建立；进程内生成两 IT 口令并按先例登记。
- **P1 前端构建与打包**：`web/` npm install（如需）+ `npm run build` → dist 产物校验（`index.html` 存在、chunk 清单无 `Mall-` 混入）→ dist 拷入 platform `static/` → `mvn -pl platform-app -am clean package -DskipTests` → platform jar SHA256 登记（**预期 ≠ BATCH-V 的 `942370c7…`**：新增静态资源所致，代码态不变）；`git status --porcelain` 复核零 tracked 变更。
- **P2 prep 与数据完好性**：运行幂等 prep（§3）→ 后置断言：`S20260918_12` 仍 ACTIVE、ADS 六表行数 2/8/2/4/4/8 不变、`sys_user`=3。
- **P3 平台启动（守卫全开）**：F1 13 变量 env + 逐键读回 → F2a 预启动自检 → `java -jar` 启动 → F2b 启动日志守卫 → `/api/v1/metrics/health` 探活 + `GET /` 返回 200 且含 SPA 入口 → 记录平台 PID。
- **P4 真实浏览器 E2E**（browser-use，主代理亲自驱动）：按 W-3 全导航矩阵走查；逐页收集截图 + console + network；AI 空历史 → AI 空范围查询 → AI 正常范围查询（fallback/provenance 断言）→ 决策空列表。
- **P5 对账**：W-5 三源对账逐锚点执行（页面截图值 ↔ API JSON ↔ 3307 SELECT）；W-6 快照一致性五点核对；结果写对账 JSON。
- **P6 收口**：F3 停止平台进程树；8091 释放确认；final sweep 零 `:3306`；`git status` 复核；证据归档 + `evidence-summary.json`；结果文档 `docs/verification/results/BATCH-W-STAGE7-BROWSER-E2E-RESULT.md`；更新 CURRENT_BATCH/PROJECT_STATUS；docs 提交（仅本地，push 需总控另行授权——上一笔授权已用尽）。

## 6. 停止条件与失败分类

- 任何页面白屏 / 未处理 JS exception / 关键 API 5xx / 三源对账不一致 ⇒ 先在同一 attempt 内诊断定位（截图 + console + network + 服务端日志四证合一）；属**产品缺陷**记 FAIL，属**环境问题**记 BLOCKED_ENV，属**范围/口径问题**升级 HARD DECISION 交总控。
- F2a/F2b 任一触发 ⇒ 立即终止（BATCH-V 事故同级红线），不尝试任何 3306 补救，交总控。
- npm 构建/mvn 打包失败（非代码原因的环境问题）⇒ BLOCKED_ENV。
- AI 查询若出现真实外呼（不应发生：无 key）⇒ 立即终止并登记 HARD DECISION。

## 7. 边界声明

- 本批不证明：真实 LLM Provider、第二异构来源、故障样本实链、REMOTE_CLUSTER/HDFS-Hive 集群验收、Spark/ingestion 实链（复用 T-R3/BATCH-V 既有证据）、移动端/多浏览器兼容性（单 Chromium 档）。
- 不修改：任何仓内代码/脚本/迁移/阈值/质量规则；`it-prepare-isolation.ps1` 与 `build-web-and-package.ps1` 均原样调用。
- 口令零落盘/零入参/零 git；3306 零接触；push 归总控授权（D-001）。
