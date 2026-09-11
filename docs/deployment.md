# 部署说明（LOCAL 真实链路版）

> 依据指导书 V2.0 §3.3（三种运行环境）、§14.3（交付物）、§18.4/§31⑥（两系统边界）。
> **LOCAL 模式不等于"不需要 Spark/Hive"**：本仓库的 LOCAL 是「本机 MySQL + 文件 Landing +
> 本机 spark-submit + 嵌入式 Derby Hive」，Hive 数仓与 Spark 作业**真实执行**，只是不部署
> Hadoop 集群、不用 Flume（用平台采集器等价替代）。
> 集群模式（SINGLE_NODE / REMOTE_CLUSTER）沿用同一套代码，仅换 `runtime_profile` 与提交器。

## 1. 两个系统、两个进程、两个端口（边界不可混）

| 系统 | 模块 | 端口 | 职责 | 前端 | 数据库 |
|---|---|---|---|---|---|
| 分析平台 | `analytics-server`（`platform-app`） | **8091** | 采集、流水线编排、指标库发布、看板、AI 问数、决策中心 | `web/` → 打进 platform-app jar | `analytics_meta` + `analytics_metric` |
| 模拟商城 | `mall-simulator` | **8090** | 商城演示（下单/支付/退款）、商品后台、数据生成器、出箱落盘 | `mall-frontend/` → 打进 mall-simulator jar | `mall_simulator` |

> 历史坑（已修，勿回退）：分析 SPA 曾被整份打进 mall-simulator jar，导致"看板由商城进程提供"。
> 现在两个前端**各自构建、各自进自己的 jar**，`scripts/build-web-and-package.ps1` 在**源目录**与
> **jar 内**两层断言越界页面 chunk（`Assert-FrontendBoundary` / `Assert-JarBoundary`）。
> 商城进程不提供任何分析接口（8090 访问 `/api/v1/dashboards/overview`、`/api/v1/metrics/health` 均 401）。

## 2. 前置条件

| 组件 | 版本/要求 | 说明 |
|---|---|---|
| JDK | 17 | `JAVA_HOME` 需指向 JDK（打包脚本用 `%JAVA_HOME%\bin\jar.exe` 做 jar 边界自检） |
| Maven | 3.9+ | `analytics-server` 为多模块 reactor，`spark-jobs` 为独立 Scala 模块 |
| Node | 18+ | `web/`、`mall-frontend/` 两个前端 |
| MySQL | 8.x，服务运行 | 三库三账号由 `warehouse/migrations/init-three-dbs.sql` 创建 |
| Spark | 3.5.1（本机解压即可） | 路径**登记在 `runtime_profile.spark_submit_path`**，不是环境变量 |
| 可选 | LLM API Key | 不配也能用：AI 走模板/规则回退（§19.3） |

三库三账号（最小权限，§17.1）：`meta_app`（只碰 `analytics_meta`）、`metric_pub`（读写 `analytics_metric`）、
`metric_read`（**仅 SELECT** `analytics_metric`，看板与 AI 只读 SQL 必须走它；缺该源时 AI fail-closed，
不回退元库）。

## 3. 一键构建 → 一键启动

```powershell
# 1) 构建（六步：web 构建→拷入 platform-app→clean package→mall-frontend 构建→拷入 mall-simulator→clean package）
pwsh -File scripts/build-web-and-package.ps1

# 2) 启动（两个进程，工作目录固定为仓库根：landing / spark-warehouse / derby-metastore 都是相对路径）
pwsh -File scripts/start-all.ps1                 # 两个都起
pwsh -File scripts/start-all.ps1 -PlatformOnly   # 只看板/流水线/AI（8091）
pwsh -File scripts/start-all.ps1 -MallOnly       # 只造数据（8090）
pwsh -File scripts/start-all.ps1 -MallDbPassword '<商城库口令>'
```

- 分析平台探活：`GET http://127.0.0.1:8091/api/v1/metrics/health` → `ok=true`（detail 含 `metric_read`）；
  入口 <http://127.0.0.1:8091/>（脚本会打印演示账号）。
- 模拟商城探活：`GET http://127.0.0.1:8090/`（SPA）；入口 <http://127.0.0.1:8090/generator>（造数据）。
- `start-all.ps1` 会给 platform-app 传 `-Dplatform.metric.publish.export-dir=<repo>\metric-staging`
  （Hive→指标库导出目录，与流水线 `PUBLISH_METRIC` 阶段一致）。

## 4. 开发模式（改前端不必重新打包）

```powershell
cd web            ; npm install ; npm run dev   # 5173 → /api 代理到 8091（分析平台）
cd mall-frontend  ; npm install ; npm run dev   # 5173 → /api 代理到 8090（模拟商城）
```

两个前端的 dev server 端口相同，**不要同时开**；代理目标已在各自 `vite.config.js` 中按边界固定。

## 5. 跑一次真实链路（LOCAL）

```powershell
# ① 造数据（8090 生成器页面，或直接调接口）：生成的事件滚动落在 mall-simulator\landing\events\{yyyyMMddHH}.jsonl
# ② 采集（8091）：POST /api/v1/ingestion/runs → 批次状态机 + 断点续采，产出 READY manifest
# ③ 建流水线（8091）：POST /api/v1/pipeline-runs（可带 Idempotency-Key）
#    八阶段：WAIT_LANDING → LOAD_ODS → BUILD_DWD → BUILD_DWS → BUILD_ADS → QUALITY_CHECK → PUBLISH_METRIC → SUCCESS
#    （INIT_SCHEMA 建表在最前；每个 Spark 阶段真实启动 spark-submit，证据落 spark_job_run）
# ④ 查证据：GET /api/v1/pipeline-runs/{id}（阶段/attempt/externalJobId/outputPartitions）
# ⑤ 看结果：8091 Ops 页（快照状态 + 质量门）与看板页（只读 ACTIVE 快照）
```

数据位置（LOCAL）：

| 内容 | 位置 |
|---|---|
| 商城事件 Landing | `mall-simulator/landing/events/*.jsonl`（`MALL_LANDING_PATH` 可覆盖） |
| 平台采集落地/暂存 | `landing/`（`PLATFORM_LANDING_LOCAL_ROOT`）、`metric-staging/`（导出清单，gitignore） |
| Hive 仓（ODS/DWD/DWS/ADS 四层正式分区与 `__staging` 孪生分区） | `spark-warehouse/`（`PLATFORM_SPARK_WAREHOUSE_DIR`） |
| Hive 元数据库（嵌入式 Derby） | `derby-metastore/`（`PLATFORM_SPARK_METASTORE_DIR`） |
| 运行/质量/审计/决策元数据 | MySQL `analytics_meta` |
| 已发布快照 + 8 张 ADS 宽表 + 指标值 | MySQL `analytics_metric` |
| 商城业务数据 | MySQL `mall_simulator` |

> **Derby 单写者**：嵌入式元数据库同一时刻只允许一个进程持有。平台（8091）持有它并通过子进程
> `spark-submit` 读写；**不要再让第二个长期进程指向同一 `derby-metastore`**（会 `Another instance
> of Derby may be running`）。独立验证脚本 `scripts/run-spark-chain.ps1` 因此使用**另一个仓目录**
> （默认 `D:\Develop\tmp\spark-warehouse`），它是历史遗留的手工单作业链脚本，**不是**平台链路入口。

## 6. 环境变量与配置（LOCAL 默认值可直接跑）

| 变量 | 默认 | 用途 |
|---|---|---|
| `PLATFORM_META_URL` / `PLATFORM_META_USER` / `PLATFORM_META_PASSWORD` | `analytics_meta` / `meta_app` / 演示口令 | 平台元数据库 |
| `PLATFORM_METRIC_PUBLISH_URL` / `_USER` / `_PASSWORD` | `analytics_metric` / `metric_pub` | 指标库写路径 |
| `PLATFORM_METRIC_READ_URL` / `_USER` / `_PASSWORD` | `analytics_metric` / `metric_read` | 指标库只读路径（看板/AI） |
| `PLATFORM_LANDING_LOCAL_ROOT` | `./landing` | 采集落地区 |
| `PLATFORM_SPARK_WAREHOUSE_DIR` / `PLATFORM_SPARK_METASTORE_DIR` | `./spark-warehouse` / `./derby-metastore` | 嵌入式 Hive 仓与元数据（显式固定，防 CWD 漂移） |
| `PLATFORM_SPARK_JOB_TIMEOUT_MS` / `PLATFORM_SPARK_POLL_INTERVAL_MS` | `900000` / `2000` | 阶段等待上限与轮询间隔 |
| `MALL_DB_USER` / `MALL_DB_PASSWORD` | `root` / 空（启动脚本默认演示口令） | 商城库连接 |
| `MALL_LANDING_PATH` | `./landing` | 商城事件落盘 |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`（即 `llm.base-url` / `llm.api-key` / `llm.model`） | 空 / 空 / `deepseek-chat` | AI 增强；不配则模板回退 |

> 演示口令只用于本机 LOCAL；生产/答辩环境必须显式覆盖，且**不写进代码与文档**（§21.3）。
> Spark 提交路径、作业 jar、master 等来自 `runtime_profile` 表（LOCAL 档案默认指向本机
> `spark-submit.cmd`），改动请走 RuntimeProfile，而不是散落的命令行参数。

## 7. 验证与验收命令

```powershell
# 编译 + 单元测试（快）
mvn -q -f spark-jobs/pom.xml package                                  # Scala 作业（不启动 Spark）
mvn -f analytics-server/pom.xml test                                  # 平台全模块
$env:MALL_DB_USER='root'; $env:MALL_DB_PASSWORD='<口令>'
cd mall-simulator; mvn test                                           # 商城（含黄金数据集回归）
cd web; npm test ; cd ..\mall-frontend; npm test                      # 两个前端

# 真库 IT（需 MySQL + 已发布快照；默认跳过，避免无库假绿）
mvn -f analytics-server/pom.xml test "-Dmetric.it=true"

# 页面级验收（Playwright，需两个进程已启动）
python .verify/r7-4-dom.py          # 分析端 8 页
python .verify/r7-4-mall-dom.py     # 商城端
```

## 8. 集群模式（SINGLE_NODE / REMOTE_CLUSTER，待环境实跑）

1. 部署 Hadoop 3.3.x + Hive 3.1.x + Spark 3.5（版本以 `docs/compatibility-matrix.md` 实测为准）。
2. 执行数仓 DDL：`warehouse/ddl/00-ods.sql` ~ `04-ads.sql`（含建库）。
3. 配置 Flume：按 `ingestion/flume/flume-taildir.conf` 修改路径后启动（Taildir 断点采集 → HDFS `/landing`）。
4. 构建作业包：`mvn -f spark-jobs/pom.xml package`（jar 不含 Spark 依赖）。
5. 在平台登记 RuntimeProfile（类型、SSH 主机、提交用户、`spark-submit` 路径、作业 jar 路径）——
   提交器按类型分派（`LocalProcessSparkSubmitter` / `SshSparkSubmitter`），入口契约见 `spark-jobs/README.md`。
6. 指标服务首版仍为 MySQL（`analytics_metric`）；Doris 属第二阶段。
7. 验证：黄金数据集全链路回归 + Flume 断点恢复（§5.2.9 验收）。

## 9. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 8090/8091 端口占用 | 上一实例未退出；先停旧进程（`Get-Process java`）再启动 |
| 商城启动即失败（数据源错误） | `MALL_DB_PASSWORD` 未设置或错误；用 `-MallDbPassword` 指定 |
| `Another instance of Derby may be running` | 有第二个进程指向同一 `derby-metastore/`；只保留 8091 |
| 启动后看板为空、`warnings=["NO_ACTIVE_SNAPSHOT"]` | 还没有 ACTIVE 快照：走第 5 节造数→采集→流水线 |
| AI 页显示"规则回退模式 / 未调用大模型" | 未配置 `LLM_API_KEY` —— 符合设计（AI 不阻塞主链路） |
| AI 问数报"只读数据源未配置" | `metric_read` 账号/URL 缺失；这是**有意的 fail-closed**（禁止回退元库） |
| 质量门 FAILED、指标未发布 | 看 `data_quality_result`（LANDING 层 BLOCKING 规则如 `AMOUNT_RECONCILE`），修数据后新 run 重跑 |
| `mvn package` 后 jar 里仍有旧页面 | 打包必须 `clean package`（`mvn package` 不清 `target/classes`） |
| 重试 `retry-from-stage` 报暂存分区缺失 | 该 run 的暂存已被后续成功发布的清理动作删掉（已登记边界）；改为新建 run 或从 `BUILD_ADS` 起重跑 |
