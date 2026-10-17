# WP-F9c（WP-F9b 真跑销项）总控亲跑报告

> 执行：2026-10-17 巡检轮，总控亲跑（非 agy 包）。范围：① AnalyticsIsolationFlywayIT V15 在 3307 隔离运行器真跑；② 重建 spark-jobs 与平台产物、重启 8091 平台使 live metric 库 Flyway 实迁移 14→15；③ 隔离档案 scale-vm-b（id=3）下 recalculate 发布一轮，按 channel 独立重算 DWD 与 ads_channel_summary_m 发布行逐项对账。红线遵守：仅 3307、未碰 3306、未用 Docker、未拨 DeepSeek API、无密钥打印/落盘。

## ① FlywayIT V15 真跑（隔离运行器）——通过

- 准备：it-prepare-isolation.ps1 -RunId f9cb20261017 -IncludeAnalytics -Confirm -AllowRootOnIsolated，exit 0（新建 f9cb20261017_* 隔离库/账号）。
- 运行器：run-isolated-tests.ps1 -Module analytics -IncludeAnalyticsWriteIts -Confirm（InstanceUuid=9c3bdd51-c908-11f1-a2d2-72dc7c342ffd、本地 Maven/MySQL 工具链、JAVA_TOOL_OPTIONS 注入 -Djava.net.preferIPv4Stack=true）：**[PASS exit=0]**。
- AnalyticsIsolationFlywayIT **2/2 PASS**（39.47s）；总控另直查隔离库 flyway_schema_history 复核——metric 头 **V15**（"ads channel summary"，success=1）、meta 头 V38，且隔离 metric 库 `ads_channel_summary_m` 表实存。
- 同腿写入型 IT 全绿：IsolationGuardMySqlIT 6/6、MetricAdsMySqlIT 2/2、MetricPublisherMySqlIT 3/3。
- 日志：/tmp/test-logs/f9cb20261017/（prepare.log、isolated-analytics-schema.log、isolated-analytics.log、runner.log）。

## ② 重建产物 + 平台重启 + live V15 实迁移——通过

- spark-jobs jar 总控重建 BUILD SUCCESS（JDK8、离线，含 WP-F9b 的 AdsSql.channelSummary）。
- platform-app jar 总控重建 BUILD SUCCESS（JDK17；离线首试因 maven-jar-plugin 的 plexus-archiver/plexus-utils 仅有 POM 无 jar 而失败，改经本地中继在线重建成功——环境供给问题，非仓库缺陷）。新 jar 内含 db/metric/V15__ads_channel_summary.sql（jar tf 实查）。
- 平台以新 jar 按 G2 口径重启（env=live-platform.env + llm-deepseek.env、仓库根 CWD、preferIPv4Stack + UTF-8，pid 84657）：启动期 metric 库 Flyway **实迁移 14→15**（日志：Successfully applied 1 migration、now at version v15），meta 库 V38 无迁移；GET /api/v1/metrics/health **200**（mysql metric store ok）。
- 总控直查 live 库复核：live20261016_analytics_metric.flyway_schema_history 头 V15 success=1、`ads_channel_summary_m` 实存。

## ③ 隔离档案发布 + 渠道对账（真实数据探针）——通过

- 档案 scale-vm-b（id=3）test 全过（applicable 全 PASS）→ activate；对已消费批次 3 走显式重算入口 POST /api/v1/admin/pipeline-runs/recalculate（理由「WP-F9c channel summary real-data verification run」审计落库），得 **run 8**，目标快照 **S20260918_8**。
- run 8 **八阶段全 SUCCESS**（约 543 秒）：WAIT_LANDING 9995 / INIT_SCHEMA **47**（run 7 为 45，+2 = 渠道正式+暂存两表，与 WP-F9b DDL 增量一致）/ LOAD_ODS 10035 / BUILD_DWD 8508 / BUILD_DWS 100 / BUILD_ADS **164**（run 7 为 161，+3 = 渠道表 3 行）/ QUALITY_CHECK 12 / PUBLISH_METRIC 328。
- 头条指标与 run 7（同数据）逐项一致：gmv 365160.30 / net_sale 273243.80 / avg_order_value 362.26 / paid_order_cnt 1008 / pv 5008 / uv·dau 100——渠道表落地未扰动既有口径。
- 发布行（ads_channel_summary_m@S20260918_8，dt=20260918）：

  | channel | pv | view_users | intent_users | cart_users | intent_rate | cart_rate |
  |---|---|---|---|---|---|---|
  | app | 1684 | 100 | 100 | 98 | 1.0000 | 0.9800 |
  | h5 | 1630 | 100 | 100 | 96 | 1.0000 | 0.9600 |
  | pc | 1694 | 100 | 100 | 95 | 1.0000 | 0.9500 |

- 独立复算（总控自写 PySpark 直读 DWD parquet dt=20260918 分区，6885 行行为明细；渠道归一规则与 AdsSql.channelSummary 逐字同式：NULL/trim 后空串→UNKNOWN、其余 trim）：逐行结果与发布行**逐项逐分一致**（三渠道 pv/三类去重用户/两率全等）；staging 导出 metric-staging/S20260918_8/ads_channel_summary_m.jsonl 三行亦同值——DWD→ADS→发布三层勾稽闭合。
- UNKNOWN 行核验：本数据集 DWD 原始 channel 仅 app/h5/pc 三值、**零 NULL/零空串**，故按口径不应产出 UNKNOWN 行，发布行亦无 UNKNOWN 行——一致。UNKNOWN 归一与 view_users=0 时两率 NULL 的行为已由 WP-F9b 的 AdsChannelSummarySpec 行为测试覆盖（本轮不以无此形状的真实数据冒充该项证据）。
- 跨渠道不可加和注记核验：Σview_users（分渠道）=300 ≠ 全站 distinct view 用户 100（每用户在三渠道各计一次，独立复算实证）；Σ pv=5008 = 头条 pv（pv 为事件行数，可加和）——与 metric-dictionary §8 / 设计 L443 注记一致，不构成对账失败。
- 隔离与收尾：_8 为档案 3 作用域 ACTIVE、同作用域 _7 转 ARCHIVED；档案 2 作用域 _4（S20260918_4）仍 ACTIVE 未动；跑后已复激档案 2 为 ACTIVE，平台健康 200。

## 状态

WP-F9c **关账**；WP-F9b 至此全线闭合（代码回归 + FlywayIT 真跑 + live 迁移 + 真实数据发布对账四段齐备）。WP-F9 余项：GAP-09、GAP-10，其后收官复核。
