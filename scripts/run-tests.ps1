# 项目级统一测试入口（DEV-003c 收口）
#
# 解决什么：三棵 Maven 树的默认档、隔离档，加上 `spark-jobs` 的 143 个 ScalaTest，此前
# 分属三个互不相通的入口（分别手敲 Maven 命令 / `scripts/run-isolated-tests.ps1` / 无入口），
# 「今天该跑什么、跑出多少、算不算过」没有单一出口。本脚本是**上层调度器**，只做三件事：
#   1) 按档位顺序调用**既有**入口（不重实现任何隔离逻辑）；
#   2) 统一出「用例数 > 0 且 F/E 全 0」的硬门禁，把「零用例 / 假绿」一律判失败；
#   3) 打印各档独立摘要与最终退出码。
#
# 分层职责（总控 2026-09-15 裁决：Maven ＋ PowerShell 双层，**禁止新增根 pom.xml**）：
#   * Maven 侧负责**模块内**测试选择语义：surefire `includes/groups/excludedGroups` 与
#     `isolated-tests` profile，全部留在各模块自己的 `pom.xml`（`metric-analysis`；mall/generator）。
#   * PowerShell 侧负责**跨模块、跨 JDK、环境与汇总**：本脚本 ＋ `run-isolated-tests.ps1`。
#   * 本脚本**不使用** `-Dtest=` 点名任何用例（DEV-003b 已确立的纪律：点名会让「入口自动收集」
#     退化成人工记忆类名）。
#
# 四档：
#   default   → analytics-server（reactor 6 模块）→ mall-simulator → synthetic-data-generator
#               命令语义与既有默认入口**逐字相同**：`mvn -f <pom> test`（JDK17）
#   isolated  → 直接调度 `scripts/run-isolated-tests.ps1 -Module all -Confirm`（mall/generator/analytics）
#   spark     → `mvn -f spark-jobs/pom.xml test`（**显式 JDK8** ＋ 注入本轮 `-Dp2.test.runId`）
#   all       → default → isolated → spark，任一档失败/零用例/F/E 非 0 ⇒ 非 0 退出
#
# 成功判据（**必须**，缺一即失败）：
#   * default / isolated：逐模块 Maven 汇总行 `Tests run: N, Failures: F, Errors: E, Skipped: S`（前缀不限级别：有跳过为 `[WARNING]`、有失败为 `[ERROR]`）
#     满足 N > 0 且 F = 0 且 E = 0，且模块退出码 0；
#   * spark：**只认** `spark-jobs/target/surefire-reports/TestSuite.txt`（ScalaTest 产物）的
#     `Total number of tests run:` > 0，且 `failed = 0`、`aborted = 0`，且该文件是**本轮新写**的
#     （mtime ≥ 本轮启动时刻）。**禁止**用 surefire 的 `Tests run: 0 / BUILD SUCCESS` 当成功依据
#     （该模块的 ScalaTest 结果不走 surefire 汇总，历史 JDK8 运行里出现过 `Tests run: 0` ＋`BUILD SUCCESS`
#     的矛盾记录，见 `docs/acceptance/v25-r01-coverage-20260914/VERIFY-T01-T02.md`）。
#
# **spark 档的证明边界（不得越界表述）**：它只证明「Scala local[1] ＋ `catalogImplementation=in-memory`
# 下的测试通过」（`com.graduation.analytics.P2TestSupport` 的 SparkSession 配置）。**不得**表述为
# 「生产 Hive／Spark 集群已通过」——测试域与在产 Hive metastore 不等价（`P2TestSupport.scala:50-60` 自陈）。
#
# 不计入通过总数（总控 2026-09-15 裁决，转 backlog／另裁）：
#   * `MetricAdsMySqlIT`、`MetricPublisherMySqlIT` → 后续测试完善 backlog（未纳入本入口）
#   * `SourceRegistryMigrationMySqlIT` → DEV-004（结构性双必败，未修前不得纳入）
#   * `SparkStageExecutorSmokeIT` → Spark 专项冒烟 backlog（真实 `spark-submit`，Windows 路径硬编码）
#   * `AnalysisGoldenMySqlIT` → D 类历史黄金值只读复验，**永久排除** unified isolated-tests
#   * 前端 `web` 项目级入口 → 整理阶段范围外（后续 CI 阶段处理）
#
# 退出码（与 `scripts/run-isolated-tests.ps1` 契约对齐）：
#   0 = 所选档全部通过
#   1 = 参数/环境错误（找不到 mvn / JDK / 脚本自身错误）
#   5 = 执行前被拒（缺 `-Confirm`、缺口令、RunId 形状非法）；隔离档的门禁 5/6 亦原样透传
#   6 = 隔离档只读探针取数失败（原样透传）
#   7 = 套件失败：非 0 退出 / 零用例 / F 或 E 非 0 / spark 产物非本轮新写 / 计数与登记基线漂移
#
# 用法：
#   pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark
#   $env:IT_GUARD_PASSWORD_MALL='<...>'; $env:IT_GUARD_PASSWORD_GENERATOR='<...>'
#   pwsh -NoProfile -File scripts/run-tests.ps1 -Suite isolated -RunId dev003c_20260915_1145 -Confirm
#   pwsh -NoProfile -File scripts/run-tests.ps1 -Suite all -RunId dev003c_20260915_1145 -Confirm
param(
  [ValidateSet('default', 'isolated', 'spark', 'all')][string]$Suite = 'default',
  [string]$RunId = '',
  [string]$MavenCmd = 'D:\apache-maven-3.9.14\bin\mvn.cmd',
  [string]$MavenRepoLocal = 'D:\maven_repository',
  [string]$JdkDefault = 'D:\Develop\JAVA17',
  [string]$JdkSpark = 'D:\Develop\JDK1.8',
  [string]$IsolatedScript = '',
  [string]$LogDir = '',
  # 仅在总控批准口径更新后使用：允许用例数与登记基线不一致仍算通过
  [switch]$AllowCountDrift,
  [switch]$Confirm
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-StrictMode -Version Latest

$RunIdPattern = '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$'
$SparkTestSuiteTxt = 'spark-jobs\target\surefire-reports\TestSuite.txt'

# 登记基线（口径来源：docs/PROJECT_STATUS.md「测试与验收状态」；漂移即失败，除非 -AllowCountDrift）
# 2026-09-16 S2-04B 收口口径（analytics=871 / default=990）：相对上一版 834/953 只差 +37，
#   全部来自本轮新增用例，且只落在两个模块 —— connection-ingestion +30（LandingInputScannerTest 6
#   ＝枚举唯一所有者：完成文件规则、候选⊋完成、相对键、跨分区同名）；LandingLayoutTest 5（布局登记表、
#   未登记值 fail-closed、列宽唯一所有者）；IngestionLandingLayoutTest 4（同一份输入在两种布局下的
#   采集/账/清单差异）；RuntimeProfileLandingLayoutWriteTest 6（写入口径：null/空白/未登记值）；
#   FlumeSpoolConfigTest 9（`ingestion/flume/flume-spooldir.conf` 的静态门禁：规则 1/3/5/6）；
#   platform-app +7（RuntimeProfileLandingLayoutMigrationScriptTest：V22 只加一列、类型/可空/位置、
#   无 DROP/DEFAULT/ENUM、版本已分配且唯一、实体字段存在、脚本恰好一条语句）。
#   analytics-server = 871 = 90(platform-common) + 350(connection-ingestion，跳过 1) + 147(warehouse-pipeline)
#     + 48(metric-analysis) + 91(ai-decision) + 145(platform-app)；三树 990 = 871 + 13(mall) + 106(generator)。
#   前序口径链：S2-04A 时 analytics 为 834（connection-ingestion 320 / platform-app 138）、三树 953；
#     S2-03.1 时 analytics 为 821（320 / 138）；
#     S2-03 时 801（connection-ingestion 311 / platform-app 127）；
#     S2-02B 时 773（connection-ingestion 290 / platform-app 120）；
#     S2-01B 时 746（platform-app 119）；+18 = connection-ingestion +17（S2-02A 新增用例）+1。
#   注意：**数量基线与通过状态是两回事** —— 871 这个数字里仍含 1 个已登记的环境性红
#     （platform-app `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，F=1），故本档 F/E 门禁照旧判失败。
#     S2-04B 的默认档实测因此是「计数 MATCH + 唯一红＝该已登记环境性红」，**不是** exit=0。
# 2026-09-16 S2-05 收口口径（analytics=872 / default=991 / spark=143）：相对 S2-04B 只差 +1/+1/+32，
#   全部来自本轮新增用例，且只落在两个模块 ——
#   warehouse-pipeline +1（SparkStageExecutorTest：BUILD_DWD 阶段序 dim 先于 bdw；147→148）；
#   spark-jobs +32（DimDwdChainExecSpec 23：真跑 sci→odl→dim/bdw/tdw 主链；
#     DwdSourceIdentitySpec 8：P2-04-a 复合去重键 (source_system,event_id) 真跑取证；
#     JobArgsRegistrySpec +1：Kahn 真检环 + 拓扑序 + bdw 前置含 dim；111→143）。
#   analytics-server = 872 = 90 + 350(跳过 1) + 148 + 48 + 91 + 145；三树 991 = 872 + 13 + 106。
#   前序口径链：S2-04B 时 871/990/spark=111。
#   注意：**数量基线与通过状态是两回事** —— 872 这个数字里仍含同一个已登记的环境性红（F=1），
#     故默认档实测是「计数 MATCH + 唯一红＝该已登记环境性红」，**不是** exit=0。
# 解析器修正（2026-09-15，总控认定属普通实现细节）：surefire 的模块汇总行在有跳过时为 `[WARNING]`、
#   有失败时为 `[ERROR]`，旧正则只认 `[INFO]` ⇒ 这类模块**整块漏计**，其 F/E 一并丢失
#   （harness 实测复现：platform-app F=1 时摘要仍显示「analytics-server F=0」）。
#   现按「当前正在构建的模块」归集实际 run/F/E/S，失败一律由 F/E 判定，不因日志级别丢模块。
$BaselineDefault = [ordered]@{
  'analytics-server'        = 950
  'mall-simulator'          = 13
  'synthetic-data-generator' = 106
}
# S3-01：新增 AdsRfmRawValueSpec（6 条，ADS 画像 R/F/M 原值与窗口）+ MetricAdsCatalogDdlConsistencyTest
# （3 条，Java 侧迁移↔白名单一致性，计入默认档 analytics-server）⇒ spark 177→183，analytics-server 872→875。
# S3-02：新增 AdsSaleTrendNetSaleSpec（6 条，ads_sale_trend 净销售额透传/血缘/跨表对账/空值规则）
# + AiSqlDriftTest 加「迁移解析覆盖后续ALTER加列」（1 条）⇒ spark 183→189，analytics-server 875→876。
# S3-03：新增 AdsRepeatRateSpec（6 条，复购率有效口径/DWS valid_order_count/窗口声明/空值规则/列序）
# + MetricPublisherMappingTest（4 条，发布侧复购率映射与 window: 观察期声明，不连库）
# ⇒ spark 189→195，analytics-server 876→880。
# S3-04：新增 AdsCartRateSpec（6 条，加购率 = cart_add 去重 ÷ view 去重 / 与 intent 口径判别 /
# 四行同值透传 / 分母 0→NULL / 不新增第五阶段 / ADS 列序）
# + SqlTemplateSpec 加「漏斗 DWS 加购分子只取 cart_add」1 条
# + MetricPublisherMappingTest 加 cart_rate 映射与 NULL 跳过 2 条
# ⇒ spark 195→202，analytics-server 880→882。
# S3-05：新增 AdsQualityRuleVersionSpec（5 条，质量大盘四条规则逐行带规则定义版本 rule_version /
# 与外层列清单一致 / 不含版本时列序不变 / 表结构 formal+staging 追加版本列 / 仍是 8 张 ADS）
# + SqlTemplateSpec 加「大盘每行带版本且四分支同一常量」1 条
# + QualityRuleThresholdDriftTest（5 条，Java 反熵守卫：规则码/版本/阈值三方对账
#   —— quality_rule_definition 目录 ↔ AdsSql.dataQuality ↔ QualityChecker，漂移即红）
# ⇒ spark 202→208，analytics-server 882→887。
# S3-06：`mxp` 导出制品 checksum 贯通（Spark 产出摘要 → 清单 checksum 键 → 发布侧 BLOCKING 重算比对）
#   spark 侧新增 MetricExportChecksumSpec（3 条：CRC32 已知向量 / 200KB 实文件 == java.util.zip.CRC32 /
#     内容敏感性 —— 同样行数不同内容摘要必须不同），
#   DwsAdsChainExecSpec 加「逐表 checksum == 导出文件真实字节 CRC32」1 条
#   ⇒ spark 208→212。
#   analytics-server 侧新增 MetricExportManifestChecksumTest（5 条：crc32 已知向量 / 整文件读取 /
#     内容敏感性 / 清单缺 checksum 即拒绝 / 带 checksum 可解析且原样保留），
#   MetricPublishValidatorTest 加「内容被改写（行数不变）→ MP_EXPORT_CHECKSUM 拦下」「制品缺失两码各自点名」
#     2 条，QualityRuleVersionMigrationScriptTest 加「V23 只追加种子（单条 INSERT IGNORE、不建表/不改列）」
#     1 条，并随新规则码 MP_EXPORT_CHECKSUM 把种子并集 35→36、登记码 33→34
#   ⇒ analytics-server 887→895。
# S3-07：`ads_hot_product` 接齐**热度权重定义版本**（`rule_version` = 指标字典 `product_heat.definition_version`）
#   并补齐排行决胜键 `buy DESC`（V2 审计 L178 登记的目标形态 `order by heat desc, buy desc, product_id asc`），
#   同时把热度公式从「抄两遍」收敛为**一处文字属主**（子查询算 heat_score、窗口按该列排）。
#   spark 侧新增 AdsHotProductHeatRuleVersionSpec（8 条：夹具热并列前提 / 列清单末尾 rule_version 且仍 8 张表 /
#     每行版本 = v1 / heat_score 与独立复算逐位相等且分量列透传 / 两种物理落下 rank 完全一致
#     {103→1,102→2,101→3,105→4,104→5} / TopN=2 取 {103,102} 不再随扫描序漂移 / SQL 文本钉住公式只出现一次
#     + 三级次序键 + 版本常量渲染 / formal+staging DDL 列序 == MetricAdsSpec）
#   ⇒ spark 212→220。
#   analytics-server 侧新增 AdsHotProductHeatWeightDriftTest（5 条，反熵守卫：字典版本/权重 ↔ AdsSql 逐字对账
#   —— 版本一致 / 权重一致 / 公式单一属主 / 版本列引用单一常量 / 三级稳定次序键，漂移即红）
#   ⇒ analytics-server 895→900（均不连库；`MetricPublisherMySqlIT` 夹具补 rule_version 属已登记未测项）。
# S3-08：`ads_operation_overview` 落**收藏/加购次数**（设计 §11.2 L425「对应行为事件数」；字典
#   `metric-dictionary.md:21/:22` 的 `fav_cnt`/`cart_add_cnt`，源表 `dwd_user_behavior_detail`），
#   并补齐发布侧字典行（db/meta V24）使两码能真正发布进 `metric_value`。
#   spark 侧新增 AdsFavCartCountSpec（6 条：列序末尾 + 次数≠人数判别 / 与 DWD 重算逐值对账
#     / 无事件日为 0 而非 NULL / formal+staging DDL 列序 == MetricAdsSpec）
#   ⇒ spark 220→226。
#   analytics-server 侧新增 AdsFavCartCountDriftTest（5 条，反熵守卫：字典行 ↔ meta 种子 ↔
#   发布映射 ↔ metric V10 加性迁移 ↔ MetricAdsSpec 五方对账，且钉住"次数而非去重人数"）
#   + MetricPublisherMappingTest 加 fav_cnt/cart_add_cnt 映射与 day: 粒度 1 条
#   ⇒ analytics-server 900→906（均不连库；`MetricPublisherMySqlIT`/`MetricAdsMySqlIT` 夹具补两列
#   属已登记未测项：本轮仍未在真 MySQL 上应用 V10）。
# S3-09：`AdsExportReader` 表形（schema）双向严格化 —— 导出 JSONL 的**声明列缺任何一列即拒绝**
#   （旧实现 `putIfAbsent(null)` 静默补列，"版本落后的导出"会被当成"值全为空"发布成功，
#   且没有任何 check 会点名它：补齐后各行完全一致，`MP_ROW_SHAPE_CONSISTENT` 看不出差别）。
#   依据：设计 §12.5 L529「表形…验证」、指导书 §7 阶段三第 4 条「核 schema」。
#   `MetricPublishValidatorTest` 加「显式 null（键在、值为空）仍可读」与「缺声明列 → 拒绝」
#   2 条（原有「列白名单外字段」用例不变）⇒ analytics-server 906→908。纯 Java、不连库、不改 Spark。
# S3-10：ADS 漏斗**率列**跨层对账（关闭 S3-04 R-1）—— 新增在产阻断规则
#   `ADS_DWS_FUNNEL_RATE_RECONCILE`（Spark 规则 7，逐 stage/逐列比对 ADS 率列与 DWS 全站行同 dt 率列，
#   NULL 与 NULL 判等；只判跨层一致性，不判比率数值是否异常）+ Java 契约登记 + 加性迁移 V25（只插一行）。
#   spark 侧新增 AdsFunnelRateReconcileSpec（7 条：生产透传链路绿 + 三条率列族各自独立命中 +
#   NULL==NULL 不误报 / NULL vs 有值必红 + 只对齐全站行不被维度行带偏）
#   + DwsAdsChainExecSpec 钉子改为「dqc checks ≥ 7 且确实产出新码」 ⇒ spark 226→233。
#   analytics-server 侧 QualityRuleVersionMigrationScriptTest 新增 v25OnlyAppendsSeedRows
#   （结构守卫：单条 INSERT IGNORE、不建表/不改列）并把目录全集断言 36→37
#   ⇒ analytics-server 908→909（纯 Java、不连库；V25 在真库上**未执行**，属已登记未测项）。
# S3-11：DWS 物理表形的**三方一致**守卫（参考副本 03-dws.sql ↔ 唯一所有者 LocalSchemaInitJob
#   ↔ 写入投影 DwsSql）+ 修参考副本 `dws_user_behavior_day` 的列序漂移（实测：
#   参考副本原为 `… cart, buy, search, active_hours`，所有者/写入投影为 `… cart, search, active_hours, buy`
#   ⇒ 按参考副本建表会静默串列）。spark 侧新增 DwsSchemaOwnerSpec（7 条：参考副本↔所有者逐列（名/类型/序）、
#   分区列、禁 ALTER 旁路、写入投影列序、每表恰好一条 INSERT OVERWRITE、冻结快照、表集一致）
#   ⇒ spark 233→240。纯 Spark 测试侧 + `warehouse/ddl/03-dws.sql` 文本，未连库、未改任何 Flyway 迁移。
# S3-12：`dws_region_sale_day` 补 **净销售额** `net_sale_amount`（设计 §12.1 **L319** 逐字
#   「source+region+dt，sale/net/order/buyer」；同表 §12.1 L455「分类/地区金额求和必须包含 unknown」）。
#   口径与 `dws_trade_day` **同式**：已支付行（`WHERE final_paid_flag = 1`）金额求和 − 已支付行退款求和，
#   退款 NULL/无退款 ⇒ `COALESCE(SUM(refund_amount), 0)`；列**追加在表末尾**（Hive 只能 ADD COLUMNS 追加，
#   语句按位置写入）⇒ 四处同步（写入投影 DwsSql.regionSaleDay / 唯一所有者 LocalSchemaInitJob /
#   参考副本 warehouse/ddl/03-dws.sql / 冻结快照 DwsSchemaOwnerSpec.Frozen）。
#   spark 侧新增 DwsRegionNetSaleSpec（4 条：逐地区 net = 销售 − 已支付退款，未支付行整行不计（含
#     部分/全额退款、退款 NULL 三种夹具行，若违法累计未支付退款则合计 190.00 → −310.00）；
#     Σ各地区（含 unknown）sale/net = dws_trade_day 同 dt 值且排除 unknown 即不等（L455 非空转）；
#     净额无 NULL/负数且退款全 NULL 地区 net = sale；运行期表形含 net_sale_amount）
#   + DwsAdsChainExecSpec 的 region oracle/读回补 net（既有用例内加断言，条数不变）
#   ⇒ spark 240→244。纯 Spark 测试侧 + `warehouse/ddl/03-dws.sql` 文本，未连库、未改任何 Flyway 迁移。
# S3-13：DWD/DIM 物理表形的**三方一致**守卫（与 S3-11 同型，覆盖 DWD 3 张 + DIM 2 张有所有者的表）。
#   新增 DwdDimSchemaOwnerSpec（10 条：01-dwd.sql/02-dims.sql 参考副本↔唯一所有者逐列（名/类型/序）、
#   分区列 dt STRING 且不混进普通列、两份参考副本禁 ALTER 旁路、写入投影列序（DwdSql/DimSql/TradeDwdJob）、
#   动态分区列必须落 SELECT 末位**且错序必须被检出**（守卫自检）、每表恰好一条 INSERT OVERWRITE（第二所有者扫描）、
#   冻结快照、参考副本多出的 3 张 DIM 表白名单（dim_date/dim_region/dim_metric：无所有者且零写入）、表集一致）
#   ⇒ spark 244→254。**含 1 处生产 SQL 改动**：TradeDwdJob.orderDetailInsertSql 的 4 个投影元素补显式别名
#   （order_id/user_id/product_id/quantity）—— 位置写入下别名惰性、取值断言不变（DimDwdChainExecSpec/
#   DwsAdsChainExecSpec 全绿即为实测依据），目的是让整条投影列序可被静态守卫逐位对账。
#   纯 Spark 测试侧 + 该 SQL 文本，未连库、未改任何 Flyway 迁移、未改任何 DDL 表形。
# S3-14：ADS 物理表形的**三方一致**守卫（与 S3-11/S3-13 同型，覆盖 ADS 8 张正式表 + 8 张 __staging）。
#   新增 AdsSchemaOwnerSpec（12 条：04-ads.sql 参考副本↔唯一所有者逐列（名/类型/序）、
#   参考副本表集 = 所有者 8 张 + 2 张镜像表白名单（ads_category_sale/ads_region_sale：无所有者且零写入）、
#   已登记漂移 D-09/V25-C01 的**精确形态**（仅 ads_operation_overview 参考副本末位多一个 snapshot_id STRING，
#   所有者不含该列；参考副本一旦被修正此用例会红＝要求删除白名单项）、分区列 dt STRING 且不混进普通列、
#   参考副本禁 ALTER 旁路、所有者正式表集 == AdsSql.TABLES、暂存表列 == 正式表列且分区 snapshot_id+dt、
#   8 张正式 + 8 张暂存的写入投影列序、写入唯一入口（每表恰好 1 处 insertTarget 调用 + 无 ads_ 表名直写旁路）、
#   守卫自检（投影列序对调 / 静态分区子句换错时同一判定点必须红）、冻结快照第四份依据）
#   ⇒ spark 254→266。**纯 Spark 测试侧**，未连库、未改任何 Flyway 迁移、未改任何 DDL 表形与生产 SQL。
#   本轮**未**改 `warehouse/ddl/04-ads.sql`：删掉参考副本里已声明的一列落在门①（DROP COLUMN）邻域 ⇒
#   只登记 + 精确钉住（理由与事实链见 AdsSchemaOwnerSpec 的 RegisteredSnapshotIdDriftTables 注释）。
# S3-15：测试夹具**手写** `INSERT … SELECT` 的列形守卫（`PROJECT_STATUS:224` 盲区条目的 (a) 一半）。
#   S3-11/13/14 只盯 main 侧生产写入投影；S3-03 实测的 5 条红恰恰来自**夹具自己手写**的
#   `INSERT OVERWRITE … dws_user_trade_period`（DWS 加列后按位置写入必然串列，定向跑新套件看不见）。
#   新增 FixtureWriteShapeSpec（9 条，无需 Spark）：扫描 test 树全部手写 INSERT（本期 22 处／12 文件，
#   清单冻结：新增或删除写入点都必须显式落地）、目标表静态可解性（`${ns.<层>}.<表>` /
#   `${AdsSql.staging(formal)(ns,"x")}` / 同文件 val-def）↔ 已登记不可判清单双向核对、
#   投影**项数** == 所有者列数 + 动态分区数、投影里**有名字的项**与所有者**同位列名**逐一核对
#   （命名项 272/292 = 93.2%，防「列名核对形同虚设」）、`PARTITION(…)` 列名同序 + 静态值必须是字面量、
#   自检（排除清单有效 / 注释里的 INSERT 不算数 / 真实夹具投影对调或删项时同一判定点必须红）、
#   所有者裸表名唯一。判定只用唯一所有者 `LocalSchemaInitJob` + `StaticOdsDdl`，不连库。
#   ⇒ spark 266→275。**纯 Spark 测试侧**，未连库、未改任何 Flyway 迁移、未改任何 DDL 表形与生产 SQL。
#   守卫边界：只判**表形**（目标表／列数／同位列名／分区子句），不判口径、不判指标值、不判真 Hive 物理落盘；
#   「夹具与所有者同形」推不出「夹具数据正确」。扫描器只认大写 `INSERT`，且不识别字符串里的 `//`（偏严）。
# S3-16：服务层消费 ADS 已落库的 **RFM 原值**（`m_amount`/`f_count`/`r_days`）与**观察窗口**
#   （`period_start`/`period_end`），关闭 `PROJECT_STATUS:217` 登记项（设计 §11.2 L435「R距最后有效购买天数；
#   F观察期有效订单数；M观察期金额」＋ §11.4 L449「M不明时保留 null+警告，不能把缺列当0」＋ §15 L676
#   「R/F/M原值与score、8群体、观察期、复购」；指导书 §7 阶段4 L156「时间…信息」）。
#   五列落库链（ADS SQL → mxp 清单 → MySQL V4 → Java 白名单）早在 S2-06/S3-01 已贯通，本轮**只补读取侧**：
#   `RfmSegment` 追加 `orders`（Σf_count）、`amount` 改 Σm_amount、R 优先逐行 `r_days`（缺失才退回
#   `calc_date − last_buy_date` 旧口径并挂警告）、`RfmProfile`/`UsersData`/`RfmData` 追加
#   `periodStart`/`periodEnd`（行间不一致或缺列 ⇒ null，不猜）；新增两个唯一 owner 编码
#   `RFM_RAW_VALUES_UNAVAILABLE`/`RFM_PERIOD_UNAVAILABLE`（`AnalysisViewModel`），
#   `RFM_AMOUNT_UNAVAILABLE` 由「无条件」改为「仅原值不可用」（设计 §16.4 L732 单一 owner）。
#   RfmServiceTest +4（原值齐备／窗口不一致／M 列缺失／R 列逐行缺失）+ AnalysisServiceTest +1（原值与
#   窗口透传）⇒ analytics-server 909→914（纯 Java、不连库；真库 `m_amount` 非零与真镜像列存在性**未测**）。
# S3-17：分析**统一信封**补 `source`（= `metric_snapshot.source`，发布方；§17.6 只接受 spark-ads），
#   落实指导书 §7 阶段4 L156「返回明确 source…」与设计 §11.1 L414「MetricValue：source作用域…」
#   （改前实测：`metric-analysis` 全模块 `getSource()` 零命中 ⇒ 该字段确实缺失）。
#   加性改动：`AnalysisViewModel` 记录新增分量（紧随 snapshotId）、`of(...)` 增参、`empty()` 传 null；
#   `AnalysisService.view()` 用 `blankToEmpty(meta.getSource())` **原样回显**（空串保持空串，不臆造 spark-ads）。
#   口径边界：`source` **不是**业务源身份（P2-04 裁决 ①）——源身份由 per-source warehouse namespace 承载。
#   AnalysisServiceTest +1（空串回显）⇒ analytics-server 914→915（纯 Java、不连库；真库 `source` 取值**未测**）。
# S3-18：`/api/v1/analysis/products` 热度榜**真分页**（`page`/`size`）+ **稳定排行**
#   （`rank_no` 升序、同 rank 以 `product_id` 升序打破平局），落实指导书 §7 阶段4 **L158**「分页、限流、
#   超时统一」（本版**只落「分页」一项**：`sort`/限流/超时未实现，见 backlog）与 §8 阶段4 完成标准 L200
#   「分页正确」、设计 L693「分页 page/size/sort」/ L675「商品分析…稳定排行、分页」。
#   加性改动：`products(snapshotId, page, size, topN, from, to)`（旧 `topN` 退化为窗口大小，page 缺省 1
#   ⇒ 旧前端逐字段不变）、`ProductsData` 加性补 `page`/`size`/`total`/`hasMore`、
#   显式 `page<1`/`size<1` ⇒ `PARAM_INVALID`（复用既有码，不静默钳制），`conversion` 仍全量不分页。
#   AnalysisServiceTest +7（兼容/第二页窗口/旧 topN 退化/越界空页/空排行 total=0/平局决胜/非法参数）
#   + AnalysisControllerTest（新类，+2：page/size/topN 接线按位置下传 + 全缺省传 null）
#   ⇒ analytics-server 915→924（metric-analysis 73→80、platform-app 147→149；纯 Java、不连库；
#   真库商品行数、真 HTTP 分页端到端**未测**）。
# S3-19：**只读查询超时统一**（指导书 §7 阶段4 **L158**「…分页、限流、**超时统一**」剩余子项中的
#   「超时统一」；设计锚点 L544 Store 能力含 `queryTimeout`、L569 只读 SQL 查询超时 30 秒、
#   L572「setReadOnly + timeout/maxRows 同时生效」）。
#   开工前实测：`MySqlMetricStore` / `MetricAdsReader`（阶段4 两条读路径，共用 `metricReadJdbcTemplate`）
#   零超时设置；全仓只有 ai-decision `SqlExecutor:88,122` 自带字面量 30 ⇒ 同一平台两套超时。
#   加性改动：新 `platform-common` 数值属主 `QueryTimeoutPolicy`（默认 30，可配
#   `platform.query.read-timeout-seconds`，0/负数/非数字/溢出**一律回退默认值，绝不解释成"无超时"**）；
#   `metricReadJdbcTemplate` 装配点 `setQueryTimeout(...)`（一次设定两条读路径同时生效）；
#   新错误码 `QUERY_TIMEOUT` → 504（`mapStatus` 单点定状态、`PlatformBizException` 单点定码，
#   超时不再落进兜底 500 INTERNAL）；`SqlExecutor.QUERY_TIMEOUT_SECONDS` 改引用该属主（值不变=30）。
#   刻意排除：发布写模板与 meta 读写共用模板不加读超时（无真库长事务证据），`maxRows` 不进阶段4
#   读路径（整分区读取，静默截断会算错 total/排行）。
#   新测试：`GlobalExceptionHandlerQueryTimeoutTest`（+3）、`PlatformDataSourcesQueryTimeoutTest`（+5）、
#   `QueryTimeoutOwnerDriftTest`（+1）⇒ analytics-server 924→933
#   （platform-common 90→93、ai-decision 92→93、platform-app 149→154；纯 Java、不连库；
#   真库上 queryTimeout 到点是否抛 `QueryTimeoutException`、真 HTTP 504**未测**）。
#   边界：设计 L544 的 per-Store 能力描述（`supportsSnapshot`/`supportsDimensions`/`maxRows`/`queryTimeout`/
#   `availability`）本轮**未实现**（全模块零命中，已登记）；限流（L158 同句）因设计零锚点**待总控批注**。
# S3-20：**销售趋势净销售额消费侧**（设计 §9.3 **L333**「历史已发布，net_sale 等字段需补」、
#   §11.2 **L428**「净销售｜同口径支付金额−成功退款金额」、指导书 §7 阶段3 L148「金额/退款口径」；
#   backlog 原行：`ads_sale_trend_m.net_sale_amount` **尚无消费方**）。
#   开工前实测：该列**上游已齐**（Spark `MetricAdsSpec:34` 末列 → `AdsSql:212-223` 从 `dws_trade_day` 透传 →
#   MySQL `db/metric/V5__ads_sale_trend_net_sale.sql` 加性 ALTER → Java 白名单 `MetricAdsCatalog:31`），
#   且读侧 `MetricAdsReader.selectSql()` 按白名单拼列 ⇒ **DAO 本来就返回该列**，缺口只在 `AnalysisService` 消费侧
#   （`SalesTrendPoint` 旧 4 列直通，`net_sale_amount` 被丢弃）。
#   加性改动：`SalesTrendPoint` 末尾追加 `netSaleAmount`（`AdsRows.asDecimal` 透传：缺列/畸形 ⇒ null，
#   **不臆造 0**），`/dashboards/overview` 的 `salesTrend[*]` 与 `/analysis/sales` 的 `trend[*]` 同时生效
#   （同一记录，无第二实现）；契约文档 R7-4 **v1.4** 加性升版（两处示例 JSON + 口径边界）。
#   新测试：`AnalysisServiceTest` +3（ADS 缺列 ⇒ null 且**汇总 `netSale` 仍取 `metric_value`**、字符串/数值/
#   畸形三种驱动形态、overview 与 sales 两处都给净额）⇒ analytics-server 933→936（metric-analysis 80→83）。
#   边界：跨业务日退款归属期**未裁决**（L428「退款归属期需冻结」）、设计 L506「ADS GMV ≥ 净销售 ≥ 0」
#   **未实现**（读侧不加启发式纠正）、`from`/`to` 实测**只回显不过滤**、阶段5 页面**未展示**该字段、
#   真库取值与真 HTTP 响应**未测** —— 逐条登记在 `docs/PROJECT_STATUS.md` backlog 与 S3-20 登记文件。
#   门禁实测（RunId `s320_20260916_def2`）：三棵树 1055 = analytics 936 + mall 13 + generator 106；
#   唯一红 = 已登记环境性红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched` ⇒
#   「计数 MATCH + 唯一红＝该已登记环境性红」，**不是** exit=0。
# S3-21：**商品热度榜排序键**（设计 **L693**「分页 page/size/sort」第三项、**L675**「商品分析｜…稳定排行、分页」；
#   v1.3 契约明文留白「`sort` 参数…本版未实现」⇒ 本项补齐）。
#   开工前实测：起点提交 `c4c09dc` 全仓**无任何 `sort` 请求参数**（`git grep -n -E "RequestParam[^)]*sort"` = 0 命中；
#   仅有服务内 `List.sort(...)` 排序调用），`contract-specs/**` 中 `page|size|sort` = **0 命中**（门⑥不触）。
#   加性改动：`AnalysisService.products(snapshotId, page, size, sort, topN, from, to)` 新增 `sort`
#   （形式 `字段[,asc|desc]`；白名单 `rank|heat|pv|fav|cart|buy`——逐列落在 `MetricAdsCatalog:41`
#   `ads_hot_product_m` 既有白名单内，**不新增列**；缺省 `rank,asc` ⇒ 不传时逐行同序）；
#   末级固定 `product_id` 升序（沿用 v1.3 稳定排行），`heat` 缺值行**无论方向都排最后**（不当 0）；
#   未登记字段/非法方向/段数>2 ⇒ `PARAM_INVALID`（HTTP 400，不新增错误码、**不静默降级**）；
#   `filters.sort` 回显**生效值**（缺省也回显 `rank,asc`）；`page`/`size`/`total`/`hasMore`/`conversion` 语义不变。
#   契约文档 R7-4 **v1.5** 加性升版（§3.3 排序规则 + 旧调用方影响）。
#   新测试：`AnalysisServiceTest` +7（缺省序回显 / 同值 `product_id` 升序 / 单段按字段自身缺省方向 /
#   去空白与大小写 / `heat` 缺值恒定最后 / 4 种非法 `sort` 与空白回缺省 / 排序先于分页且不动 `conversion`）
#   ⇒ analytics-server 936→943（metric-analysis 83→90）；同步迁移 `EvidenceBuilder`、`AnalysisController`、
#   `AnalysisGoldenMySqlIT`、`AnalysisControllerTest` 的 7 参调用点（调用点数不变，不新增条数）。
#   边界：**未测**真 HTTP 查询参数解析（无 `@SpringBootTest`）、真库 ADS 数据与真实排序结果
#   （`AnalysisGoldenMySqlIT` 未运行）、前端**未使用** `sort`（`web/src` 无该参数）；L158 的**限流**仍未实现。
#   门禁实测（RunId `s321_20260916_def2`；基线更新前先用 `s321_20260916_def` 量到 943 真值）：三棵树 1062 = analytics 943 + mall 13 + generator 106；
#   唯一红 = 已登记环境性红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched` ⇒
#   「计数 MATCH + 唯一红＝该已登记环境性红」，**不是** exit=0。
# S3-22：**ADS 大盘同归属口径不变量**「GMV ≥ 净销售 ≥ 0」（设计 §12.3 质量 12 项**第 8 项**，
#   逐字锚点 **L506**；L512 要求「三者独立、不得用一个码覆盖」⇒ 本项**只**加第 8 项，
#   第 9 项「UV ≤ PV」（L507）**另立一码**、本轮**不落地**）。
#   开工前实测：起点提交 `8763667` 全仓**无** `ADS_GMV_NET_SALE_INVARIANT`（0 命中）；
#   `AdsQualityJob.keyPredicates` 对 `ads_operation_overview` **只**断言 `pv/uv/dau` 非空，
#   两个金额列**无任何在产守卫**（同表 `ads_sale_trend` 却已断言两列非空）⇒ 缺口真实存在。
#   加性改动：新规则码 `ADS_GMV_NET_SALE_INVARIANT`（`BLOCKING`、`FIXED`、threshold NULL、
#   `source_scope=*`、version 1）+ **加性**迁移 `V26__quality_rule_ads_gmv_net_sale_invariant.sql`
#   （单条 `INSERT IGNORE`；V19/V25 **字节未改** ⇒ 门③不触）+ 在产判定
#   `AdsQualityJob.gmvNetSaleInvariantCheck`（作用域=本次快照+本次 dt；**NULL 判不通过**；
#   **只判不改**；未新增/未改任何列与既有规则）。
#   偏差登记 D-1：落点取 `AdsQualityJob`（与规则 7 同族）而**非** `AdsSql.dataQuality` 的 UNION 分支
#   （后者 4 个分支全是 Landing/DWD 侧口径）——设计语义不变、仅落点差异，理由见
#   `docs/acceptance/s3-22-ads-gmv-net-sale-20260916/DESIGN-DIFF-REGISTER-20260916.md` §4.2。
#   新测试：`AdsGmvNetSaleInvariantSpec` +9（真链路通过且金额有值 / 净销售>GMV / 净销售<0 / GMV<0 /
#   金额 NULL / `GMV==净销售` 通过 / `0==0` 通过 / 快照隔离 / 判定后原值保留）；
#   守卫同步：`RuleSeverityTest`（码集合 35→36，**方法数不变**）、
#   `QualityRuleVersionMigrationScriptTest`（+`v26OnlyAppendsSeedRows`、目录 37→38）、
#   `FixtureWriteShapeSpec`（写入点清单加本 spec，3 条）、`DwsAdsChainExecSpec`（dqc ≥8 且含新码，
#   链路实测 `passed=true|check=1|err=0`，金额 2042.00/1493.00 = 设计 L437 黄金值）。
#   ⇒ analytics-server 943→944（platform-app 154→155），spark 275→284。
#   边界：**未测**真实 `spark-submit`/Hive metastore、V26 在真库的执行、生产 3306 的 ADS 实际值；
#   设计 §12.3 的 5/6/9/11 项与第 10 项剩余部分**仍未实现**（不得称「12 项已完成」）。
#   门禁实测（RunId `s322_20260916_def` / `s322_20260916_spark`；基线更新前先量到 944/284 真值）：
#   三棵树 1063 = analytics 944 + mall 13 + generator 106；唯一红同上（已登记环境性红）。
# S3-23：**ADS 大盘同过滤条件不变量**「UV ≤ PV」（设计 §12.3 质量 12 项**第 9 项**，
#   逐字锚点 **L507**；S3-22 已登记本项为「未实现、下一优先」，本轮承接）。
#   开工前实测：起点提交 `2540bd2` 全仓 `uv *<=? *pv` / `pv *>=? *uv` **0 命中**；
#   `AdsQualityJob.keyPredicates` 对大盘表**只**判 `pv/uv/dau` 非空（BLOCKING），
#   两列之间**无任何不等式守卫** ⇒ 缺口真实。
#   **「同过滤条件」是作用域判据**：`AdsSql.operationOverview` L77-78 两列同取
#   `CASE WHEN behavior_type = 'view'`（同表同 dt）⇒ 不变量构造性成立；
#   而同表 `dau = COUNT(DISTINCT user_id)`（L79，**全事件**）过滤条件不同 ⇒
#   `dau > uv` **合法**，**不得**纳入判据（新增用例把该边界钉死）。
#   加性改动：新规则码 `ADS_UV_PV_INVARIANT`（`BLOCKING`、`FIXED`、threshold NULL、
#   `source_scope=*`、version 1）+ **加性**迁移 `V27__quality_rule_ads_uv_pv_invariant.sql`
#   （单条 `INSERT IGNORE`；V19/V25/V26 **字节未改** ⇒ 门③不触）+ 在产判定
#   `AdsQualityJob.uvPvInvariantCheck`（作用域=本次快照+本次 dt；违反式 `uv > pv` 行数须为 0；
#   **NULL 不判** —— 唯一所有者是既有 `ADS_STAGING_KEY_NOT_NULL`（同层 BLOCKING），
#   与 S3-22 第 8 项「金额两列当时无任何非空守卫故自行判 NULL 不通过」的差异已在登记文件 §3④ 说明；
#   **只判不改**；未新增/未改任何列与既有规则）。
#   新测试：`AdsUvPvInvariantSpec` +9（真链路 `pv=3/uv=2` 通过且先钉判别力 / `uv>pv` 命中给出实际值 /
#   `uv==pv` 通过 / `0==0` 通过 / 快照隔离 / 只判不改 / `pv` NULL 时本规则不判但非空规则守卫仍在 /
#   `dau>uv` 必须通过 / 生产 SQL 两列过滤谓词逐字相等的结构守卫）；
#   守卫同步：`RuleSeverityTest`（码集合 36→37，**方法数不变**）、
#   `QualityRuleVersionMigrationScriptTest`（+`v27OnlyAppendsSeedRows`、目录 38→39）、
#   `FixtureWriteShapeSpec`（写入点清单加本 spec，3 条）、`DwsAdsChainExecSpec`（dqc ≥9 且含新码）、
#   `SourceRegistryMigrationMySqlIT`（迁移清单 +V27，该 IT 属 D 类真库用例本轮**未运行**）。
#   ⇒ analytics-server 944→945（platform-app 155→156，platform-common 仍 93），spark 284→293。
#   边界：**未测**真实 `spark-submit`/Hive metastore、V27 在真库的执行、真实 HTTP/页面呈现；
#   `dws_product_behavior_day` 的同型不变量（同过滤条件 pv/uv，DWS 侧）**本轮未落**；
#   设计 §12.3 的 5/6/11 项与第 10 项剩余部分**仍未实现**（不得称「12 项已完成」）。
#   门禁实测（RunId `s323_20260916_def` / `s323_20260916_spark`；基线更新前先量到 945/293 真值）：
#   三棵树 1064 = analytics 945 + mall 13 + generator 106；唯一红同上（已登记环境性红）。
# S3-24：**质量卡接齐规则定义版本**（`rule_version` 消费侧）。逐字锚点：指导书 V3.0 阶段4 **L156**
#   「MetricStore/专题服务返回明确 source、snapshot、**definitionVersion**、时间和**质量信息**」＋
#   设计 V3.0 §12.3 **L512**「每条规则记录作用域、阈值、**版本**、阶段、实际值、passed、原始/生效严重度」；
#   该列的**写入方** S3-05 已落地（加性迁移 `V8` 把 `rule_version` 追加到 `ads_data_quality_m` 末尾），
#   但**读取侧无消费方**（F-34 R-5 明确登记「暂无消费方…归阶段4」）⇒ 缺口真实。
#   开工前实测：`AnalysisService.quality()` 只读 `rule_code`/`passed`（哈希键只有规则码与通过位），
#   S3-23 结束时的响应体无任何版本信息 ⇒ 质量卡「按哪一版规则判的」不可追问。
#   加性改动：`QualitySummary` 加 `ruleVersions`（规则码 → 定义版本，不可变 + 规则码升序）＋
#   `quality()` 用 `AdsRows.asLongOrNull` 读该列（**不用 `asInt`**：后者取不到返回 0，会把
#   「未记录版本」写成 v0）；**取不到版本 ⇒ 该规则码不出现**在映射里（键集是 `ruleCount` 的子集），
#   与写入侧 V8「允许 NULL…不写 0 冒充 v1」同源。既有 `ruleCount`/`passedCount`/`failedRules`
#   语义**不变**（无版本的行仍计数）；未新增/未改列、未改迁移、未新增错误码、未新增权限码。
#   契约同步：`docs/contracts/analysis-viewmodel-r7-4.md` 加性 v1.6（§3.1/§3.2 的 `quality` 样例
#   + 键集语义与「仍未实现」边界；`contract-specs/**` **未动** ⇒ 门⑥不触）。
#   新测试：`AnalysisServiceTest` +3（有版本三行按码升序回显 / NULL 行不进映射且 ruleCount 仍为 4
#   且值域不含 0 / `sales` 与 `overview` 两处逐字相同——同一 quality() 所有者）；
#   `EvidenceBuilderTest` 仅随记录组件更新两处构造点（断言不变）。
#   ⇒ analytics-server 945→948（metric-analysis 90→93；platform-app/platform-common 不变），spark 293 不变
#   （本轮 `git diff --stat` 实测 **0 个 spark-jobs 文件** ⇒ Spark 档未重跑，沿用 S3-23 已实测结论）。
#   边界：**未测**真实 HTTP 响应 JSON 里的 `ruleVersions`、真实 MySQL/ADS 行上的 `rule_version` 实际取值、
#   V8 在真库的执行；阶段5 页面**未展示**规则版本（`web/**` 本轮未改）；
#   设计 §9.3 L335「规则版本与**实时结果**待接齐」的另一半（发布链实时回写）、§12.3 规则 5/6/11、
#   L158 限流、L157 归档读取授权均**不变**（不得称「L335 已满足」）。
#   门禁实测（RunId `s324_20260916_def2` 量数 / 基线更新后 `s324_20260916_def3` 收口；
#   量数轮先量到 948 真值：`93+350+163+93+93+156`）：
#   三棵树 1067 = analytics 948 + mall 13 + generator 106；唯一红同上（已登记环境性红）。
# ── S3-25（F-58）：设计 §12.3 第 9 项在 **DWS 层同型站点**的独立规则码 `DWS_UV_PV_INVARIANT` ──────
#   落地内容：`dws_product_behavior_day`（按 `product_id×category_id` 逐行、无 snapshot 维度）的
#   「UV ≤ PV」在产 BLOCKING 守卫 + 新规则码的目录/严重度登记 + 加性迁移 V28（只插一行）+
#   `AdsQualityJob.dwsUvPvInvariantCheck` 接线进 dqc（链路实测 checks 10 条，含新码）。
#   新测试：`DwsUvPvInvariantSpec` **+10**（真跑 `DwdSql`/`DwsSql` 链路通过且 pv=3/uv=2 有判别力 /
#   `uv>pv` 违反 / `uv==pv`、`0==0` 边界 / pv、uv 为 NULL 由本码自判 / 唯一所有者耦合守卫 /
#   只判不改 / 多行计数 / fav·cart·buy 不受牵连 / pv-uv 同过滤条件结构守卫 / 空分区不冒充违反）；
#   `QualityRuleVersionMigrationScriptTest` **+1**（`v28OnlyAppendsSeedRows`：单条 INSERT IGNORE、
#   不建表不改列、写明未在真库执行、独立成码不复用 ADS 码）。
#   ⇒ spark 293→**303**（+10）；analytics-server 948→**949**（platform-app 156→157）。
#   边界：**未测** V28 在真库的执行、`dws_product_behavior_day` 空分区/存在性的真实数据表现、
#   12 项规则剩余（5/6/11 与第 10 项剩余）**不变** ⇒ 不得称「12 项完成」或「全仓 UV≤PV 已守卫」。
#   门禁实测（量数轮 `s325_20260916_spark1`/`s325_20260916_def1`；基线更新后收口轮
#   `s325_20260916_spark2`/`s325_20260916_def2`）：量数轮实测 spark 303（DRIFT 基线 293）、
#   analytics 949（`93+350+163+93+93+157`）、三棵树 1068；收口轮应 MATCH。
# S3-29：`AiSqlDriftTest` 新增「迁移按版本序解析而非字典序」（1 条；把迁移解析顺序由**字典序**
#   收归**版本序**唯一所有者 `migrationVersion(Path)`/`migrationFilesOrdered(Path)`，并带牙齿自检：
#   变异回字典序时实测红 `[10, 1, 2, 3, 4, 5, 6, 7, 8, 9]`）⇒ analytics-server 949→**950**（ai-decision 93→94），
#   spark **不变**（零 Scala 改动，303 沿用 S3-25）。**只改测试守卫**：零生产代码/DDL/迁移/前端/新依赖。
#   门禁实测（量数轮 `s329_20260916_def1`）：analytics 950（`93+350+163+93+94+157`，DRIFT 基线 949，
#   漂移量 = 新增 1 条）、mall 13、generator 106、三棵树 1069（基线 1068）、唯一红仍是已登记环境性红、
#   `[FAIL exit=7]` 预期；基线更新后收口轮 `s329_20260916_def2` 应 MATCH。
#   边界：**未测** 真库应用迁移、`warehouse/ddl` 同类读取点（顺序无语义，未改）、`db/meta` 读取点（未改）、
#   isolated/spark 两档 ⇒ 不得称「DDL 守卫已完备」。
$BaselineSpark = 303
$BaselineIsolated = [ordered]@{ mall = 30; generator = 19; analytics = 6 }

function Fail([int]$code, [string]$msg) {
  Write-Host ("[REFUSE exit={0}] {1}" -f $code, $msg)
  exit $code
}
function Write-Section([string]$t) {
  Write-Host ''
  Write-Host ("=== {0} ===" -f $t)
}
function Resolve-JavaExe([string]$jdkHome) { Join-Path $jdkHome 'bin\java.exe' }

# ── 参数与环境门禁 ─────────────────────────────────────────────────────────
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $IsolatedScript) { $IsolatedScript = Join-Path $PSScriptRoot 'run-isolated-tests.ps1' }
if (-not $RunId) { $RunId = 'dev003c_' + (Get-Date -Format 'yyyyMMdd_HHmm') }
if ($RunId -notmatch $RunIdPattern) {
  Fail 5 ("RunId '{0}' 形状非法（要求 {1}）：库名/账号名/Spark 测试根目录都由它派生，形状错会打到别处" -f $RunId, $RunIdPattern)
}
if (-not $LogDir) { $LogDir = Join-Path $env:TEMP ("v25tests-{0}" -f $RunId) }
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if (-not (Test-Path -LiteralPath $MavenCmd)) { Fail 1 ("找不到 Maven：{0}" -f $MavenCmd) }
if (-not (Test-Path -LiteralPath (Resolve-JavaExe $JdkDefault))) { Fail 1 ("找不到默认档 JDK：{0}" -f $JdkDefault) }
if ($Suite -in @('spark', 'all') -and -not (Test-Path -LiteralPath (Resolve-JavaExe $JdkSpark))) {
  Fail 1 ("找不到 spark 档 JDK8：{0}" -f $JdkSpark)
}
if ($Suite -in @('isolated', 'all') -and -not (Test-Path -LiteralPath $IsolatedScript)) {
  Fail 1 ("找不到隔离档入口：{0}" -f $IsolatedScript)
}

# 隔离档的前置（在跑 default 之前就判，避免跑完 default 才因缺口令失败）
if ($Suite -in @('isolated', 'all')) {
  if (-not $Confirm) {
    Fail 5 ('未显式给出 -Confirm：拒绝（isolated 档会连隔离实例并执行写入型测试）。确认后重跑并加 -Confirm。')
  }
  $pwdMall = [bool]$env:IT_GUARD_PASSWORD_MALL -or [bool]$env:IT_GUARD_PASSWORD
  $pwdGen = [bool]$env:IT_GUARD_PASSWORD_GENERATOR -or [bool]$env:IT_GUARD_PASSWORD
  if (-not ($pwdMall -and $pwdGen)) {
    Fail 5 ("缺隔离口令（mall={0} generator={1}）。本脚本不提供 -Password、不读 credref 文件。" -f `
        $(if ($pwdMall) { 'OK' } else { '缺失' }), $(if ($pwdGen) { 'OK' } else { '缺失' }))
  }
}

Write-Host '=== 项目级统一测试入口（DEV-003c）==='
Write-Host ("  档位      : {0}" -f $Suite)
Write-Host ("  RunId     : {0}" -f $RunId)
Write-Host ("  日志目录  : {0}" -f $LogDir)
Write-Host ("  Maven     : {0}   （-o 离线，repo={1}）" -f $MavenCmd, $MavenRepoLocal)
Write-Host ("  JDK       : 默认档/隔离档 {0} ；spark 档 {1}" -f $JdkDefault, $JdkSpark)
Write-Host ("  计数基线  : default analytics-server={0} / mall={1} / generator={2} ；spark={3} ；isolated mall={4}/generator={5}/analytics={6}{7}" -f `
    $BaselineDefault['analytics-server'], $BaselineDefault['mall-simulator'], $BaselineDefault['synthetic-data-generator'], `
    $BaselineSpark, $BaselineIsolated['mall'], $BaselineIsolated['generator'], $BaselineIsolated['analytics'], `
    $(if ($AllowCountDrift) { '（-AllowCountDrift：漂移不算失败）' } else { '' }))
Write-Host '  不计入通过总数：MetricAdsMySqlIT / MetricPublisherMySqlIT（backlog）、SourceRegistryMigrationMySqlIT（DEV-004）、'
Write-Host '                  SparkStageExecutorSmokeIT（spark 专项 backlog）、AnalysisGoldenMySqlIT（D 类，永久排除）、web 前端（范围外）'

# ── 工具函数 ───────────────────────────────────────────────────────────────
function Invoke-MavenRun {
  param(
    [string]$Name, [string]$Pom, [string[]]$ExtraArgs, [string]$JdkHome,
    [string[]]$SysProps, [string]$LogName
  )
  $env:JAVA_HOME = $JdkHome
  $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH
  Remove-Item Env:\MAVEN_ARGS -ErrorAction SilentlyContinue
  $log = Join-Path $LogDir $LogName
  $mvnArgs = @('-o', "-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps
  Write-Host ("  JAVA_HOME = {0}" -f $env:JAVA_HOME)
  Write-Host ("  mvn {0}" -f ($mvnArgs -join ' '))
  # `| Out-Host` 是必需的：Tee-Object 会把内容写进成功流，若不落 Host 就会污染本函数的返回值
  # （函数返回值必须是单个 pscustomobject，否则调用方拿到的是「日志行数组 + 对象」）。
  & $MavenCmd @mvnArgs 2>&1 | Tee-Object -FilePath $log | Out-Host
  $code = $LASTEXITCODE
  return [pscustomobject]@{ name = $Name; exit = $code; log = $log }
}

# 逐模块汇总行：`[INFO|WARNING|ERROR] Tests run: N, Failures: F, Errors: E, Skipped: S`（行尾无 `-- in`）
# 注意：多模块 reactor **没有** reactor 级汇总行 —— 「764」必须由 6 条模块汇总行相加得到。
# `-ModuleFilter`：隔离档的 analytics 目标是 `-pl metric-analysis -am`，日志里**同时**含依赖模块
# platform-common 的 90 个默认档用例（依赖构建，不属于隔离档）。因此必须按「当前正在构建的模块」
# 归集汇总行，只统计目标模块；否则会把 90 误算成隔离用例（本轮自查发现的自身缺陷 3）。
function Get-MavenTotals {
  param([string]$Log, [string]$ModuleFilter = '')
  $t = 0; $f = 0; $e = 0; $s = 0; $blocks = 0; $detail = @(); $skippedModules = @{}
  $current = ''
  foreach ($line in (Get-Content -LiteralPath $Log)) {
    if ($line -match '^\s*\[(?:INFO|WARNING|ERROR)\] Building (\S+) ') { $current = $Matches[1] }
    elseif ($line -match '\[(?:INFO|WARNING|ERROR)\] --- .* @ (\S+) ---') { $current = $Matches[1] }
    elseif ($line -match '^\[(?:INFO|WARNING|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$') {
      $n = [int]$Matches[1]; $ff = [int]$Matches[2]; $ee = [int]$Matches[3]; $ss = [int]$Matches[4]
      if ($ModuleFilter -and $current -ne $ModuleFilter) {
        if (-not $skippedModules.ContainsKey($current)) { $skippedModules[$current] = 0 }
        $skippedModules[$current] += $n
        continue
      }
      $t += $n; $f += $ff; $e += $ee; $s += $ss; $blocks++; $detail += $n
    }
  }
  return [pscustomobject]@{
    blocks = $blocks; tests = $t; failures = $f; errors = $e; skipped = $s; detail = $detail
    excluded = $(if ($skippedModules.Count -gt 0) { (($skippedModules.GetEnumerator() | Sort-Object Name | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }) -join ' ') } else { '' })
  }
}

function Get-ScalaTestTotals {
  param([string]$Path)
  $raw = Get-Content -LiteralPath $Path -Raw
  $o = [ordered]@{ total = -1; suitesCompleted = -1; aborted = -1; succeeded = -1; failed = -1; canceled = -1; ignored = -1; pending = -1; allPassed = $false }
  $m = [regex]::Match($raw, 'Total number of tests run:\s*(\d+)')
  if ($m.Success) { $o.total = [int]$m.Groups[1].Value }
  $m = [regex]::Match($raw, 'Suites:\s*completed\s*(\d+),\s*aborted\s*(\d+)')
  if ($m.Success) { $o.suitesCompleted = [int]$m.Groups[1].Value; $o.aborted = [int]$m.Groups[2].Value }
  $m = [regex]::Match($raw, 'Tests:\s*succeeded\s*(\d+),\s*failed\s*(\d+),\s*canceled\s*(\d+),\s*ignored\s*(\d+),\s*pending\s*(\d+)')
  if ($m.Success) {
    $o.succeeded = [int]$m.Groups[1].Value; $o.failed = [int]$m.Groups[2].Value
    $o.canceled = [int]$m.Groups[3].Value; $o.ignored = [int]$m.Groups[4].Value; $o.pending = [int]$m.Groups[5].Value
  }
  $o.allPassed = [bool]([regex]::IsMatch($raw, '(?m)^All tests passed\.\s*$'))
  return [pscustomobject]$o
}

function Compare-Baseline {
  param([string]$Label, [int]$Actual, [int]$Expected)
  if ($Actual -eq $Expected) { return ("{0}={1} MATCH" -f $Label, $Actual) }
  if ($AllowCountDrift) { return ("{0}={1} DRIFT(基线 {2}) -AllowCountDrift 放行" -f $Label, $Actual, $Expected) }
  return ("{0}={1} DRIFT(基线 {2}) ⇒ 基线漂移" -f $Label, $Actual, $Expected)
}

$suiteResults = [ordered]@{}
$failCode = 0

# ── default 档 ─────────────────────────────────────────────────────────────
function Invoke-DefaultSuite {
  Write-Section ("default-tests：analytics-server → mall-simulator → synthetic-data-generator（JDK17，命令语义不变）")
  $targets = @(
    [pscustomobject]@{ name = 'analytics-server'; pom = 'analytics-server\pom.xml'; extra = @() },
    [pscustomobject]@{ name = 'mall-simulator'; pom = 'mall-simulator\pom.xml'; extra = @() },
    [pscustomobject]@{ name = 'synthetic-data-generator'; pom = 'synthetic-data-generator\pom.xml'; extra = @() }
  )
  $rows = @(); $total = 0; $bad = @()
  foreach ($t in $targets) {
    Write-Host ''
    Write-Host ("--- 默认档 {0} ---" -f $t.name)
    $r = Invoke-MavenRun -Name $t.name -Pom (Join-Path $RepoRoot $t.pom) -ExtraArgs $t.extra `
      -JdkHome $JdkDefault -SysProps @() -LogName ("default-{0}.log" -f $t.name)
    $tot = Get-MavenTotals -Log $r.log
    $cmp = Compare-Baseline -Label 'tests' -Actual $tot.tests -Expected $BaselineDefault[$t.name]
    $ok = ($r.exit -eq 0) -and ($tot.tests -gt 0) -and ($tot.failures -eq 0) -and ($tot.errors -eq 0) -and ($cmp -notmatch 'DRIFT')
    $total += $tot.tests
    $rows += [pscustomobject]@{
      name = $t.name; exit = $r.exit; tests = $tot.tests; failures = $tot.failures; errors = $tot.errors
      skipped = $tot.skipped; blocks = $tot.blocks; detail = ($tot.detail -join '+'); cmp = $cmp; ok = $ok; log = $r.log
    }
    if (-not $ok) { $bad += $t.name }
  }
  Write-Host ''
  Write-Host '--- default-tests 摘要（本轮 fresh）---'
  foreach ($row in $rows) {
    Write-Host ("  {0,-26} exit={1}  Tests run: {2} (F={3} E={4} S={5})  模块汇总行 {6} 明细 {7}" -f `
        $row.name, $row.exit, $row.tests, $row.failures, $row.errors, $row.skipped, $row.blocks, $row.detail)
    Write-Host ("  {0,-26} {1}" -f '', $row.cmp)
  }
  Write-Host ("  {0,-26} 合计 = {1}（基线 {2}）" -f 'default 三棵树', $total, (($BaselineDefault.Values | Measure-Object -Sum).Sum))
  return [pscustomobject]@{ suite = 'default'; exit = $(if ($bad.Count -gt 0) { 7 } else { 0 }); total = $total; rows = $rows; bad = $bad }
}

# ── isolated 档 ────────────────────────────────────────────────────────────
function Invoke-IsolatedSuite {
  Write-Section 'isolated-tests：调度既有 scripts/run-isolated-tests.ps1（-Module all -Confirm）'
  Write-Host '  说明：不重实现 DEV-001/002/003a/003b 的任何隔离逻辑；本档只调度 + 复核计数。'
  # 隔离档走 JDK17（子进程自身还有 `if (-not $env:JAVA_HOME)` 兜底，这里显式给出，避免受 spark 档影响）
  $env:JAVA_HOME = $JdkDefault
  $isoMvnLogDir = Join-Path $LogDir 'isolated-mvn'
  $isoConsole = Join-Path $LogDir 'isolated-console.log'
  $isoArgs = @('-NoProfile', '-File', $IsolatedScript, '-RunId', $RunId, '-Module', 'all', '-Confirm',
    '-LogDir', $isoMvnLogDir, '-MavenCmd', $MavenCmd, '-MavenRepoLocal', $MavenRepoLocal)
  Write-Host ("  pwsh {0}" -f ($isoArgs -join ' '))
  Write-Host '  （口令经环境变量传入子进程；子进程自身门禁 1-6 原样生效）'
  & pwsh @isoArgs 2>&1 | Tee-Object -FilePath $isoConsole | Out-Host
  $isoExit = $LASTEXITCODE

  $rows = @(); $total = 0; $bad = @()
  $expectedMap = @{ mall = 30; generator = 19; analytics = 6 }
  foreach ($n in @('mall', 'generator', 'analytics')) {
    $log = Join-Path $isoMvnLogDir ("isolated-{0}.log" -f $n)
    if (-not (Test-Path -LiteralPath $log)) {
      $rows += [pscustomobject]@{ name = $n; exit = 7; tests = 0; failures = 0; errors = 0; skipped = 0; blocks = 0; detail = ''; cmp = '日志缺失'; ok = $false; log = $log }
      $bad += $n; continue
    }
    # analytics 目标是 `-pl metric-analysis -am`：日志里含依赖模块 platform-common 的默认档用例，
    # 必须按模块归集（见 Get-MavenTotals 注释），否则 90 个依赖构建用例会被误算成隔离用例。
    $filter = $(if ($n -eq 'analytics') { 'metric-analysis' } else { '' })
    $tot = Get-MavenTotals -Log $log -ModuleFilter $filter
    $cmp = Compare-Baseline -Label 'tests' -Actual $tot.tests -Expected $expectedMap[$n]
    $reqOk = $true; $reqNote = ''
    if ($n -eq 'analytics') {
      # DEV-003b 零用例硬门禁的独立复核：被自动收集的隔离类必须真的出现
      $hit = Select-String -LiteralPath $log -Pattern '-- in .*IsolationGuardMySqlIT' | Select-Object -Last 1
      $reqOk = [bool]$hit
      $reqNote = if ($hit) { 'IsolationGuardMySqlIT 已执行' } else { '✗ IsolationGuardMySqlIT 未执行' }
      if ($tot.excluded) { $reqNote += ("；同 reactor 依赖构建不计入：" + $tot.excluded) }
    }
    $ok = ($tot.tests -gt 0) -and ($tot.failures -eq 0) -and ($tot.errors -eq 0) -and ($cmp -notmatch 'DRIFT') -and $reqOk
    $total += $tot.tests
    $rows += [pscustomobject]@{
      name = $n; exit = $isoExit; tests = $tot.tests; failures = $tot.failures; errors = $tot.errors
      skipped = $tot.skipped; blocks = $tot.blocks; detail = ($tot.detail -join '+'); cmp = "$cmp $reqNote"; ok = $ok; log = $log
    }
    if (-not $ok) { $bad += $n }
  }
  Write-Host ''
  Write-Host '--- isolated-tests 摘要（本轮 fresh）---'
  foreach ($row in $rows) {
    Write-Host ("  {0,-10} Tests run: {1} (F={2} E={3} S={4})  模块汇总行 {5}  {6}" -f `
        $row.name, $row.tests, $row.failures, $row.errors, $row.skipped, $row.blocks, $row.cmp)
  }
  Write-Host ("  {0,-10} 合计 = {1}（基线 {2}）；runner exit={3}" -f 'isolated', $total, (($expectedMap.Values | Measure-Object -Sum).Sum), $isoExit)
  Write-Host '  口径：mall 30 ＋ generator 19 ＋ metric-analysis IT 6 = 55；analytics 侧同 reactor 的 platform-common 90 为依赖构建，不计入隔离档。'
  $code = 0
  if ($isoExit -ne 0) { $code = $isoExit } elseif ($bad.Count -gt 0) { $code = 7 }
  return [pscustomobject]@{ suite = 'isolated'; exit = $code; total = $total; rows = $rows; bad = $bad; childExit = $isoExit; console = $isoConsole }
}

# ── spark 档 ───────────────────────────────────────────────────────────────
function Invoke-SparkSuite {
  Write-Section 'spark-tests：mvn -f spark-jobs/pom.xml test（显式 JDK8 ＋ 本轮 -Dp2.test.runId）'
  $suiteTxt = Join-Path $RepoRoot $SparkTestSuiteTxt
  $pre = if (Test-Path -LiteralPath $suiteTxt) { Get-Item -LiteralPath $suiteTxt } else { $null }
  $preSha = if ($pre) { (Get-FileHash -LiteralPath $suiteTxt -Algorithm SHA256).Hash } else { '<不存在>' }
  Write-Host ("  TestSuite.txt 运行前：{0}" -f $(if ($pre) { "存在 mtime={0:yyyy-MM-dd HH:mm:ss} bytes={1} sha256={2}" -f $pre.LastWriteTime, $pre.Length, $preSha.Substring(0, 16) } else { '不存在（本轮必须新生成）' }))

  # JDK8 取证：用本轮 JAVA_HOME 直接问 Maven 自己用的是哪个 JVM
  $env:JAVA_HOME = $JdkSpark
  $jdkLog = Join-Path $LogDir 'spark-jdk-version.log'
  & $MavenCmd -version 2>&1 | Tee-Object -FilePath $jdkLog | Out-Host
  $jdkText = Get-Content -LiteralPath $jdkLog -Raw
  $jdkOk = [bool]([regex]::IsMatch($jdkText, 'Java version:\s*1\.8'))
  Write-Host ("  JDK8 取证：mvn -version 输出含 'Java version: 1.8' = {0}" -f $jdkOk)
  Write-Host ("  显式固定：JAVA_HOME={0} ；PATH 前置 {1}\\bin" -f $env:JAVA_HOME, $JdkSpark)

  $runStart = Get-Date
  $r = Invoke-MavenRun -Name 'spark-jobs' -Pom (Join-Path $RepoRoot 'spark-jobs\pom.xml') -ExtraArgs @() `
    -JdkHome $JdkSpark -SysProps @("-Dp2.test.runId=$RunId") -LogName 'spark-jobs.log'
  if ($r.log) {
    # -CaseSensitive 必需：默认不区分大小写时会把 TestSuite.txt 的良性行 `Suites: completed 15, aborted 0` 误报成中止
    $aborted = Select-String -LiteralPath $r.log -CaseSensitive -Pattern 'RUN ABORTED' | Select-Object -First 3
    if ($aborted) { Write-Host ("  ⚠️ 日志内出现中止标记：{0}" -f (($aborted | ForEach-Object { $_.Line.Trim() }) -join ' | ')) }
    $sfSum = Select-String -LiteralPath $r.log -Pattern '^\[INFO\] Tests run: \d+, Failures' | Select-Object -Last 1
    Write-Host ("  [对照·不作成功依据] surefire 汇总行：{0}" -f $(if ($sfSum) { $sfSum.Line.Trim() } else { '<无>' }))
  }

  # 只认 ScalaTest 产物，且必须是本轮新写
  if (-not (Test-Path -LiteralPath $suiteTxt)) {
    Write-Host '  ✗ TestSuite.txt 不存在：spark 档判失败（ScalaTest 结果只认该文件，不用 surefire 汇总兜底）'
    return [pscustomobject]@{ suite = 'spark'; exit = 7; total = 0; rows = @(); bad = @('spark-jobs'); jdkOk = $jdkOk; fresh = $false; mvnExit = $r.exit; suiteTxt = $suiteTxt }
  }
  $post = Get-Item -LiteralPath $suiteTxt
  $postSha = (Get-FileHash -LiteralPath $suiteTxt -Algorithm SHA256).Hash
  $fresh = $post.LastWriteTime -ge $runStart.AddSeconds(-1)
  $tot = Get-ScalaTestTotals -Path $suiteTxt
  Write-Host ("  TestSuite.txt 运行后：mtime={0:yyyy-MM-dd HH:mm:ss} bytes={1} sha256={2}" -f $post.LastWriteTime, $post.Length, $postSha.Substring(0, 16))
  Write-Host ("  本轮新写（mtime ≥ 启动时刻 {0:HH:mm:ss}）= {1}" -f $runStart, $fresh)
  Write-Host ("  Total number of tests run = {0} ；Suites: completed {1}, aborted {2} ；Tests: succeeded {3}, failed {4}, canceled {5}, ignored {6}, pending {7} ；All tests passed = {8}" -f `
      $tot.total, $tot.suitesCompleted, $tot.aborted, $tot.succeeded, $tot.failed, $tot.canceled, $tot.ignored, $tot.pending, $tot.allPassed)
  $cmp = Compare-Baseline -Label 'tests' -Actual $tot.total -Expected $BaselineSpark
  Write-Host ("  基线比对：{0}" -f $cmp)

  $ok = ($r.exit -eq 0) -and $fresh -and ($tot.total -gt 0) -and ($tot.failed -eq 0) -and ($tot.aborted -eq 0) -and `
    ($tot.succeeded -eq $tot.total) -and $tot.allPassed -and $jdkOk -and ($cmp -notmatch 'DRIFT')
  $row = [pscustomobject]@{
    name = 'spark-jobs'; exit = $r.exit; tests = $tot.total; failures = $tot.failed; errors = $tot.aborted
    skipped = $tot.ignored; blocks = $tot.suitesCompleted; detail = ''; cmp = $cmp; ok = $ok; log = $r.log
  }
  Write-Host ''
  Write-Host '--- spark-tests 摘要（本轮 fresh）---'
  Write-Host ("  {0,-10} Tests run: {1} (failed={2} aborted={3} ignored={4})  套件 {5}  mvn exit={6}  新写={7}  JDK8={8}" -f `
      $row.name, $row.tests, $row.failures, $row.errors, $row.skipped, $row.blocks, $row.exit, $fresh, $jdkOk)
  Write-Host '  证明边界：仅证明 Scala local[1] ＋ in-memory catalog 下测试通过；**不得**表述为生产 Hive/Spark 集群已通过。'
  # 注意：`bad` 不能用 `$(if (...) { @() } else { @('spark-jobs') })` —— 空数组经子表达式会被展开成 0 个对象
  # ⇒ 属性变成 $null，调用方 `$r.bad.Count` 会在 StrictMode 下抛错（本轮自查发现的自身缺陷 2）。
  $badList = @()
  if (-not $ok) { $badList = @('spark-jobs') }
  return [pscustomobject]@{ suite = 'spark'; exit = $(if ($ok) { 0 } else { 7 }); total = $tot.total; rows = @($row); bad = $badList; jdkOk = $jdkOk; fresh = $fresh; mvnExit = $r.exit; suiteTxt = $suiteTxt }
}

# ── 按档执行 ───────────────────────────────────────────────────────────────
if ($Suite -in @('default', 'all')) {
  $r = Invoke-DefaultSuite
  $suiteResults['default'] = $r
  if ($r.exit -ne 0 -and $failCode -eq 0) { $failCode = $r.exit }
}
if ($Suite -in @('isolated', 'all')) {
  $r = Invoke-IsolatedSuite
  $suiteResults['isolated'] = $r
  if ($r.exit -ne 0 -and $failCode -eq 0) { $failCode = $r.exit }
}
if ($Suite -in @('spark', 'all')) {
  $r = Invoke-SparkSuite
  $suiteResults['spark'] = $r
  if ($r.exit -ne 0 -and $failCode -eq 0) { $failCode = $r.exit }
}

# ── 最终摘要 ───────────────────────────────────────────────────────────────
Write-Section '最终摘要（各档独立，不合并计数）'
foreach ($k in $suiteResults.Keys) {
  $r = $suiteResults[$k]
  $verdict = if ($r.exit -eq 0) { 'PASS' } else { 'FAIL' }
  $note = ''
  if ($k -eq 'default' -and $r.PSObject.Properties.Name -contains 'rows') { $note = ("（{0} 个用例）" -f $r.total) }
  if ($k -eq 'isolated') { $note = ("（{0} 个用例；runner exit={1}）" -f $r.total, $r.childExit) }
  if ($k -eq 'spark') { $note = ("（Total number of tests run={0}；新写={1}；JDK8={2}）" -f $r.total, $r.fresh, $r.jdkOk) }
  Write-Host ("  {0,-9} {1} {2}" -f $k, $verdict, $note)
  if (@($r.bad).Count -gt 0) { Write-Host ("            失败项：{0}" -f (@($r.bad) -join ', ')) }
}
Write-Host ''
Write-Host '  不计入通过总数（总控 2026-09-15 裁决）：MetricAdsMySqlIT、MetricPublisherMySqlIT（backlog）；'
Write-Host '    SourceRegistryMigrationMySqlIT（DEV-004）；SparkStageExecutorSmokeIT（spark 专项 backlog）；'
Write-Host '    AnalysisGoldenMySqlIT（D 类，永久排除 unified isolated-tests）；web 前端（整理阶段范围外）。'
Write-Host ("  日志目录：{0}" -f $LogDir)

if ($failCode -ne 0) {
  Write-Host ''
  Write-Host ("[FAIL exit={0}] 所选档未全部通过。" -f $failCode)
  exit $failCode
}
Write-Host ''
Write-Host '[PASS exit=0] 所选档全部通过。'
exit 0
