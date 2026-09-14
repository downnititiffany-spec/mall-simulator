# 基于 Spark 大数据平台和智能分析模型的电商用户行为分析系统

毕业设计主体工程：把「事件接入 → Hive 四层数仓（ODS/DWD/DWS/ADS）→ Spark 真实作业链 → 指标快照发布 → Web 看板 / AI 解释 / 决策跟踪」
做成普通员工可用的 Web 系统，而不是手工登录虚拟机执行 Spark 脚本。

> 当前权威文档（三文件治理，自指导书 V2.8 起）：
> - 项目决策权威：[`docs/毕业设计指导书 V2.8.md`](docs/毕业设计指导书%20V2.8.md)
> - 正式设计权威：[`docs/design/项目设计文档 V2.5.md`](docs/design/项目设计文档%20V2.5.md)
> - 实际开发状态：`docs/PROJECT_STATUS.md`（治理规则已确立，待下一轮由代码 Agent 创建）
>
> 历史指导书（整改稿 V1.0、V2.0～V2.7）与历史设计文稿（V1.0、V2.1～V2.4）均为只读历史，有效期止于各自发布时点，不再作为当前实施或设计依据。

## 1. 历史状态快照（2026-09-11，仅供追溯，不代表当前项目状态）

> 本节是 2026-09-11 的时点快照，其中的测试数字、快照编号与验收结论均已过期，仅供追溯。
> 当前实际开发状态以后统一见 `docs/PROJECT_STATUS.md`；在该文件创建前，**不得把本节数字作为当前验收结果引用**。
> 本节数字与其它时点口径（303、566、624、111 等）的差异不在本节解决，统一交由 `PROJECT_STATUS.md` 的「证据 / 待总控裁决」栏处理，本轮不做统一。

R6、R7、R8 均已收口，**不再是「组件完成、生产接线尚未收口」**：

- **生产流水线已接线**：`PipelineService` 编排 8 阶段，计算阶段由 `SparkStageExecutor` 真实提交 `spark-submit` 作业
  （作业码 `sci/odl/bdw/tdw/usw/dim/fna/dqc/pub/mxp/ljp`），每阶段落 `pipeline_stage_run`，Spark 作业明细落 `spark_job_run`。
- **最近一次真实完整链**：run **22** 终态 **SUCCESS**，业务日 2026-09-01，指标库 `ACTIVE=S20260901_22`
  （`S20260901_23`、`S20260901_24` 为 ARCHIVED），导出 8 张 ADS 宽表；该 run 经
  `POST /api/v1/admin/pipeline-runs/22/retry-from-stage?stage=BUILD_ADS` 真实重跑后转绿。
  证据：`.verify/r7-3-run22-run.json`、`.verify/r7-3-run22.log`，以及 R9 证据目录
  [`docs/acceptance/r9-20260911-fabc6cb-run22-S20260901_22/`](docs/acceptance/r9-20260911-fabc6cb-run22-S20260901_22/)
  （run 22 终态 SUCCESS（attempt 4）、`ACTIVE=S20260901_22`、黄金标准答案对账**不一致项 0**、8 张 ADS 宽表行数）；
  口径与遗留边界见 `docs/remediation-status.md`。
- **黄金夹具**：`tests/golden-dataset/events/golden-20260901.jsonl`（55 行）+ 标准答案
  `tests/golden-dataset/expected/golden-20260901-expected.json`。

### 测试与验收结果

| 套件 | 命令 | 最近结果 | 证据 |
|---|---|---|---|
| 平台后端单元测试 | `mvn -f analytics-server/pom.xml test` | **303/303**（0 failure / 0 error） | `analytics-server/**/target/surefire-reports` 汇总（2026-09-11） |
| Spark 作业单元测试 | `mvn -f spark-jobs/pom.xml package`（ScalaTest） | **46/46** | `spark-jobs/target/surefire-reports/TEST-*.xml` 汇总 |
| 分析前端单元测试 | `cd web; npm test`（Node `node --test`） | **74/74** | 2026-09-11 本机实跑 |
| 模拟商城单元测试 | `cd mall-simulator; mvn test` | **54/54** | `docs/remediation-status.md` R7-4 条登记 |
| 平台真机验收 | `pwsh .verify/r8-accept.ps1` | **53/53 PASS** | `.verify/r8-accept-report.json`（身份 / 证据包 / 六段解释 / 安全问数 / 攻击集 / 决策闭环+审计） |
| 证据截断复验 | `pwsh .verify/r8-evidence-truncation-proof.ps1` | **12/12 PASS** | `.verify/r8-evidence-truncation-proof.json` |
| 分析端页面 DOM | `python .verify/r7-4-dom.py`（需两个进程已启动） | **22/22 PASS** | `.verify/r7-4-dom-report.json` |
| 商城端页面 DOM | `python .verify/r7-4-mall-dom.py` | **17/17 PASS** | `.verify/r7-4-mall-dom-report.json` |

已提交的相关里程碑：`fabc6cb`（R8 AI 证据包 / 安全问数 / 身份与决策）、`247559e`、`4e42fe6`、`e32cca9`、`1615b1c`（R7）、`891164e`（文档收尾）。
**R9（最终验收与论文证据）进行中**，进度见 `docs/remediation-status.md` 末尾未勾选项。

## 2. 硬边界：两个系统、两个进程、两个库、两个前端

| 系统 | 模块 | 端口 | 数据库 | 前端 |
|---|---|---|---|---|
| 分析平台 | `analytics-server`（启动模块 `platform-app`） | **8091** | `analytics_meta`（元数据）+ `analytics_metric`（指标库） | `web/`（Vue 3 + ECharts）构建后进入 platform-app 静态目录 |
| 模拟商城 | `mall-simulator` | **8090** | `mall_simulator` | `mall-frontend/` 构建后进入 mall-simulator 静态目录 |

- 平台不依赖商城业务库、不含生成器代码；商城只为平台提供可重复测试数据（事件滚动文件）。
- 平台可直接以黄金夹具作为 Landing 输入跑完整链，不需要商城进程（`.verify/r7-3-run22.ps1`、`.verify/r6-8b-full-chain-smoke.ps1` 均可复现）。
- 两个前端各自构建、各自进自己的 jar；`scripts/build-web-and-package.ps1` 在源目录与 jar 内两层断言越界页面 chunk。
- 商城进程不提供任何平台接口（8090 访问 `/api/v1/dashboards/overview`、`/api/v1/metrics/health` 均 401）。

## 3. 架构与数据链

### 3.1 数据库（MySQL 8.0.41，本机 `127.0.0.1:3306`）

| 库 | 归属 | 内容 | 迁移 |
|---|---|---|---|
| `analytics_meta` | 分析平台 | 流水线 / 阶段 / Spark 作业、采集、质量、AI 审计、决策、操作审计、`runtime_profile` 等 | Flyway `classpath:db/meta`，已应用到 **V15** |
| `analytics_metric` | 分析平台（指标库） | 8 张 ADS 宽表 + `metric_snapshot` + `metric_value` | Flyway `classpath:db/metric` V1–V3 |
| `mall_simulator` | 模拟商城 | 商城业务表 30 张（用户 / 商品 / 订单 / 退款 / outbox 等） | 商城自带 `classpath:db/migration` |
| `mall_business` | 无（`warehouse/migrations/init-three-dbs.sql` 创建） | 当前为空；商城进程实际连 `mall_simulator` | — |

账号最小权限：`meta_app`（`analytics_meta` 读写）、`metric_pub`（`analytics_metric` 读写 + 迁移 DDL）、
`metric_read`（**仅 `SELECT ON analytics_metric.*`**，看板与 AI 只读 SQL 必须走它；缺失时 AI fail-closed，不回退元库）。
初始化命令：`mysql -uroot -p < warehouse/migrations/init-three-dbs.sql`。

### 3.2 数仓与计算（LOCAL 真实执行）

- Hive on Spark，四层 `ods/dwd/dws/ads` + 维度库 `dw_dim`；质量规则 + 指标导出在同一作业链内完成。
- Spark 3.5.1（本机 `D:\Develop\spark-3.5.1-bin-hadoop3`）、JDK 17；`runtime_profile` 登记 `spark_submit_path` 与作业 jar，master `local[2]`。
- Hive 仓库目录：`file:///D:/Develop_code/GraduationProject/spark-warehouse`（库目录 `dw_ods` / `dw_dwd` / `dw_dws` / `dw_ads` / `dw_dim`）；
  元数据库为嵌入式 Derby `derby-metastore/`（同一时刻只允许一个进程持有，见 `docs/deployment.md` §5）。

### 3.3 生产流水线（8 阶段，唯一真实链）

```text
WAIT_LANDING → INIT_SCHEMA → LOAD_ODS → BUILD_DWD → BUILD_DWS → BUILD_ADS → QUALITY_CHECK → PUBLISH_METRIC
```

| 阶段 | 真实动作 |
|---|---|
| `WAIT_LANDING` | 只认 READY manifest（采集批次门） |
| `INIT_SCHEMA` | `sci` 幂等自举四层库表 |
| `LOAD_ODS` | `odl` 装载四主题 |
| `BUILD_DWD` | `bdw` 行为明细 / 拒绝 + `dim` 维度 + `tdw` 订单交易明细 |
| `BUILD_DWS` | `usw` 生成 DWS 宽表（观察期 = 业务日） |
| `BUILD_ADS` | `fna` 只写 `{table}__staging/snapshot_id=S/dt=D` 暂存分区 |
| `QUALITY_CHECK` | ODS/DWD 内联规则 + `dqc` 暂存层质量门 |
| `PUBLISH_METRIC` | 正式分区发布 → `mxp` 导出 → 导入指标库并原子切换快照 |

`SUCCESS` 是 run 终态，不是阶段。

### 3.4 发布与保旧快照语义

Hive ADS 宽表 → `metric-staging/<snapshotId>/*.jsonl` → 导入 `analytics_metric` → `metric_snapshot` **原子切换** `ACTIVE`；
质量门未过或发布失败时保留旧 `ACTIVE`（新快照停在 FAILED / BUILDING）。看板只读 `ACTIVE` 快照，页面数值与 AI 证据包同源。

## 4. 快速开始

### 4.1 前置条件

JDK 17（本机 17.0.12）、Maven 3.9+（本机 3.9.14）、Node 18+（本机 v24.16.0）、MySQL 8.0（本机 8.0.41，服务运行）、
Spark 3.5.1 解压即可（`D:\Develop\spark-3.5.1-bin-hadoop3`，路径登记在 `runtime_profile.spark_submit_path`，不是环境变量）。
可选 `LLM_API_KEY`：不配置时 AI 走模板回退，不阻塞主链路。

### 4.2 构建与启动（两个进程）

```powershell
# 一键构建（web dist → platform-app 静态资源 → clean package；mall-frontend dist → mall-simulator → clean package）
pwsh -File scripts/build-web-and-package.ps1

# 启动（工作目录固定为仓库根：landing / spark-warehouse / derby-metastore 都是相对路径）
pwsh -File scripts/start-all.ps1                 # 平台 8091 + 商城 8090
pwsh -File scripts/start-all.ps1 -PlatformOnly   # 只起分析平台
pwsh -File scripts/start-all.ps1 -MallOnly       # 只起模拟商城
pwsh -File scripts/start-all.ps1 -MallDbPassword '<商城库口令>'
```

- 分析平台入口 <http://127.0.0.1:8091/>，探活 `GET /api/v1/metrics/health`；演示账号
  **admin/admin123**（管理员）、**operator/operator123**（运营）、**analyst/analyst123**（分析师）。
- 模拟商城入口 <http://127.0.0.1:8090/>（商城演示 / 商品后台 / 数据生成器），账号 **admin/admin123**。

### 4.3 跑一次真实小链（55 行黄金夹具，LOCAL）

实测复现路径见 `.verify/r7-3-run22.ps1`（run 22 的脚本）：

```powershell
# ① 把夹具放进平台 Landing（新文件名 → 采集 checkpoint 从 0 读，形成新批次）
#    夹具 55 行，需带末尾换行：tests/golden-dataset/events/golden-20260901.jsonl
Copy-Item tests\golden-dataset\events\golden-20260901.jsonl landing\events\golden-r9-clean-20260911.jsonl

# ② 登录（8091）取 token：POST /api/v1/auth/login  {"username":"admin","password":"admin123"}

# ③ 采集一轮（断点续采，产出 READY manifest）
#    POST /api/v1/ingestion/runs
#    对账：GET /api/v1/ingestion/status、/api/v1/ingestion/batches；该夹具的契约口径为 52 接受 / 3 拒绝
#    （见 R9 证据目录 20-reconciliation.tsv「黄金数据集契约口径」）

# ④ 创建流水线（8 阶段真实提交 Spark 作业）
#    POST /api/v1/pipeline-runs
#    {"runtimeProfileId":1,"pipelineCode":"ODS_TO_ADS","businessTime":"2026-09-01T00:00:00","sourceDataVersion":"<tag>"}

# ⑤ 查证据与看结果
#    GET /api/v1/pipeline-runs/{id}   → 阶段、records、errorCode、evidence
#    8091 Ops 页（快照状态 + 质量门）、看板页（只读 ACTIVE 快照）
```

只跑 Spark 侧单作业链（不经平台编排）可用 `.verify/r6-8b-full-chain-smoke.ps1`；它是验证脚本，不是平台链路入口。

### 4.4 跑测试

命令与当前通过数见 §1 的测试表。日常用小样本（55 行黄金夹具）小链，完整规模实验在里程碑 / R9 执行。

## 5. 文档入口

| 文档 | 说明 |
|---|---|
| [`docs/毕业设计指导书 V2.8.md`](docs/毕业设计指导书%20V2.8.md) | **当前项目决策权威**（项目目标 / 范围 / 技术路线 / 阶段规划 / 验收原则 / 项目级裁决 / 重大架构决策）；仅总控可写，代码 Agent 只读 |
| [`docs/design/项目设计文档 V2.5.md`](docs/design/项目设计文档%20V2.5.md) | **当前正式设计权威**（架构 / 模块 / 数据库 / 数仓 / 数据流 / 接口 / Spark / AI / 部署 / 测试设计 / 正式实现方案）；仅总控可写，代码 Agent 只读 |
| `docs/PROJECT_STATUS.md` | **实际开发状态报告**（当前 commit、阶段、可运行状态、已完成/进行中/待实现、阻塞、DEV 问题、测试与验收状态、证据、工作记录、待总控裁决）；由代码 Agent 持续维护、不设版本号。治理规则已确立，待下一步由代码 Agent 创建 |
| [`docs/项目完整实施指导书 V2.0.md`](docs/项目完整实施指导书%20V2.0.md) | 历史指导书（只读）；不再是最高依据 |
| [`docs/design/项目设计文稿 V2.2.md`](docs/design/基于Spark大数据平台和智能分析模型的电商用户行为分析系统设计与实现——项目设计文稿%20V2.2.md) | 历史设计方案（只读）；不再是当前设计依据，V1.0 / V2.1 亦为历史版本 |
| [`docs/remediation-status.md`](docs/remediation-status.md) | 整改状态登记（含各阶段「如实登记的边界」） |
| [`docs/deployment.md`](docs/deployment.md) | 部署说明（两进程 LOCAL 真实链路、配置与排障） |
| [`docs/api-overview.md`](docs/api-overview.md) | API 总览（已按 R8 真实实现对齐） |
| [`docs/acceptance/`](docs/acceptance/) | R9 真机验收证据（库导出 + API 响应 + 页面截图 + 对账表，每目录自带 README） |
| [`docs/contracts/`](docs/contracts/) | 事件契约、指标字典、指标血缘、R7-4 看板信封、R8 证据 / 安全 / 决策契约 |
| [`docs/README.md`](docs/README.md) | 文档总索引 |

## 6. 如实标注：未做项与已知边界

- **集群 / 远程环境未实跑**：`SINGLE_NODE`、`REMOTE_CLUSTER` 目前只有代码路径与文档（`docs/deployment.md` §8）；
  Hadoop client 在 `docs/compatibility-matrix.md` 中标记为 ⏳（仅编译期依赖），Flume 只交付模板、未在集群运行。
- **真实 LLM 对照实验需 API key**：未配置 `LLM_API_KEY` 时 AI 走模板 / 规则回退，官方模型对照数值未做。
- **大规模性能实验刚在 R9 起步**：已有本机三档记录（1.44 万 / 6.48 万 / 12.96 万事件，
  `experiments/spark-scale-local-20260906.json`），但那是**遗留单作业链脚本** `scripts/run-spark-chain.ps1`
  （`sci→odl→bdw→usw→fna`）的产物，不是平台 8 阶段链；平台链的 10 万+ 规模与集群分档实验尚未完成。
- **Hive 为单机嵌入式 Derby metastore**，不是独立 Hive 集群；同一时刻只允许一个进程持有（`docs/deployment.md` §5）。
- **其它已登记边界**（如 `metric_snapshot.version` 恒为 1、归档快照未做权限校验、类目 / 地区 ADS 无数据载体等）
  见 `docs/remediation-status.md` 的 R7-4 / R8 各条「如实登记的边界」，本文件不重复展开。

## 7. 目录结构

```text
analytics-server/       Java/Spring Boot 分析平台后端（多模块 reactor，platform-app 为启动模块）
web/                    分析平台前端（Vue 3 + ECharts），构建后进入 platform-app 静态目录
mall-frontend/          模拟商城前端，构建后进入 mall-simulator 静态目录
mall-simulator/         独立模拟商城进程（商城演示 / 商品后台 / 数据生成器 / outbox）
spark-jobs/             Scala Spark 作业（四层数仓、质量、导出；作业码注册于 JobRegistry）
warehouse/              四层 DDL、数据库与账号初始化脚本
ingestion/              Flume 采集模板（集群模式用）
tests/                  黄金夹具与标准答案
experiments/            可复现实验记录
scripts/                构建、启动、验证、演示脚本
docs/                   指导书、契约、部署、论文与答辩材料（总索引 docs/README.md）
.verify/                真机验收脚本与证据报告（不是产品代码）
```

`landing/`、`metric-staging/`、`spark-warehouse/`、`derby-metastore/`、`target/`、`node_modules/` 是本地运行或构建产物，不属于源代码交付物。

## 8. 文档修改规则

修改任何文稿前，先把原文件复制到 `docs/backups/` 并带日期或阶段名；结论以《毕业设计指导书 V2.8》《项目设计文档 V2.5》与 `docs/PROJECT_STATUS.md`（待建）为准，
历史计划、旧截图和旧日志不可当作当前完成证据。
