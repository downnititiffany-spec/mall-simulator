# S3-12 设计差异登记（2026-09-16）

> 类型：**A 类（修正实现遗漏：补一列 ＋ 补对账）** —— 给 `dws_region_sale_day` 追加
> **净销售额 `net_sale_amount`**（设计 §12.1 **L319** 逐字「source+region+dt，**sale/net**/order/buyer」），
> 口径与同层 `dws_trade_day` **同式**；并补「Σ各地区（**含 unknown**）＝ 大盘」的跨层对账。
> 关闭的登记项：`docs/PROJECT_STATUS.md:200`（`dws_region_sale_day` 无 `net` 列）。
> 纪律：未实测不写结论；本文件不冒充"真实到账净收入已验证"，退款**归属期**仍是已登记的未决口径（§6/§8）。

---

## 0. 一句话结论

`dws_region_sale_day` 自建立起只有 `region/buyer_count/order_count/sale_amount` **四列**，
而同层 `dws_trade_day` 早已有 `refund_amount/net_sale_amount` ⇒ **地区维度只有毛销售额**：
地区专题无法回答"扣掉退款后还剩多少"，且**与大盘口径不对称**（同一 dt 两张表给出两种"收入"）。

本轮做两件事：
1. **加一列**（追加在表末尾）`net_sale_amount DECIMAL(18,2)`，口径**逐字复用** `DwsSql.tradeDay` 的净额定义：
   已支付行金额求和 − 已支付行退款求和，退款 NULL/无退款取 0；未支付行在 `WHERE` 整行排除；
2. **补跨层对账断言**：`Σ各地区 sale/net（含 unknown）` 必须等于 `dws_trade_day` 同 dt 的 sale/net，
   且**排除 unknown 就不等**（设计 §12.1 **L455**「分类/地区金额求和必须包含unknown，不丢未匹配维度」）。

设计**早已**要求 `net`（不只 V3.0）：历史设计文稿 **V2.3/V2.4/V2.5** §10 同一行均写
「DWS dws_region_sale_day ｜ source+region+dt，**sale/net**/order/buyer」（`V2.5:377`、`V2.4:372`、`V2.3:388`）
⇒ 本项是**实现遗漏**（欠账从 V2.3 起），不是 V3.0 新立的要求。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §12.1 **L319** | 「DWS dws_region_sale_day ｜ source+region+dt，**sale/net/order/buyer** ｜ 未匹配地区保留unknown」 | 四列齐备：`sale_amount`/`net_sale_amount`/`order_count`/`buyer_count`；`unknown` 保留**且计入求和** |
| 设计 §12.1 **L455** | 「分类/地区金额求和必须包含unknown，不丢未匹配维度；…**地区、商品等distinct买家不能相加当总买家**」 | 断言 `Σ_region sale/net = 大盘`（**含 unknown**）；**不**断言 `Σ buyer_count = 大盘 buyer_count`（distinct 不可加） |
| 设计 §11.2 **L427/L428** | 「GMV ｜ 有效支付订单 paid_amount 之和，不直接扣退款」「净销售 ｜ 同口径支付金额−成功退款金额 ｜ **真实收入方向，退款归属期需冻结**」 | 本列 = **同日口径**净销售（与 `dws_trade_day` 同式）；**退款归属期**未决 ⇒ 不声称"真实到账"（§6） |
| 指导书 §7 阶段3 **L148** | 「对每个指标固定粒度、分子分母、时间窗口、**金额/退款口径**、空值规则和版本」 | §2 冻结粒度/分子分母/口径/空值/版本 |
| 指导书 §7 阶段3 **L149** | 「Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**」 | 新增 `Σ_region ↔ dws_trade_day` 对账（同 dt、同输入） |
| 指导书 §7 阶段3 **L150** | 「补**分类/地区**等尚无完整产出的专题；…」 | 地区专题的**指标面**补齐（下钻页/ADS 生产者仍受 G-04 约束，§8） |
| 设计 §12.1 **L322** | 「历史DDL声明…**不代表29张都有正确数据**…**目录schema与数据库真实表形状必须对照**」 | 参考副本（`03-dws.sql`）与唯一所有者/写入投影**同批**改齐（S3-11 守卫强制） |

---

## 2. 语义声明（本轮冻结）

| 项 | 冻结取值 | 依据 |
|---|---|---|
| 粒度 | `region × dt`（`region = COALESCE(city_level,'unknown')`，城市等级） | 设计 L319「source+region+dt」 |
| 计入行 | `final_paid_flag = 1`（**有效支付**；未支付行**整行**排除，含其 `refund_amount`） | 与 `dws_trade_day`/`dws_product_sale_day` 同层同口径；设计 L427「有效支付」 |
| 分子/分母 | `sale_amount = SUM(amount)`（已支付行）；`refund_amount` 非本表列，只进净额算式 | 本表不加 `refund_amount` 列（设计 L319 未要求）⇒ **不扩列集**，只加 `net` |
| **净额** | `net_sale_amount = SUM(amount) − COALESCE(SUM(refund_amount), 0)`，作用域同为 `final_paid_flag = 1` | **逐字复用** `DwsSql.tradeDay:119-122` 的净额定义（不是另起一套） |
| 空值规则 | ① 组内 `refund_amount` 全 NULL ⇒ 退款视作 `0`（`COALESCE(SUM(...),0)`），净额 = 销售额，**不得为 NULL**；② 某地区**无任何已支付行** ⇒ **不产生行**（不是"净额为 0 的行"）；③ `city_level` 为 NULL ⇒ `region = 'unknown'` | `SUM(NULL)=NULL` 会污染净额；空组不产行与 `GROUP BY` 语义一致 |
| 求和规则 | 地区金额求和**必须包含** `unknown` | 设计 L455 |
| 版本 | 定义版本随指标字典（本列不改任何已发布字典行/版本号；`ads_region_sale` 仍无生产者） | 设计 §12.3 L512 五类断言独立、逐条记录版本 |
| 列位置 | **表末尾**（`region, buyer_count, order_count, sale_amount, net_sale_amount`） | Hive 侧只能 `ADD COLUMNS` **追加**；本语句**按位置写入** ⇒ 列序必须与 DDL 一致（S3-11 三方守卫强制） |
| **不判什么** | 不判"真实到账净收入"（退款归属期未冻结）；不判 `Σ buyer_count = 大盘 buyer_count`（distinct 不可加）；不判 `ads_region_sale`（无生产者） | 越界即假结论 |

---

## 3. 为什么是 A 类（逐门核对）

| 门 | 是否触发 | 理由 |
|---|---|---|
| ① DROP TABLE/COLUMN | **否** | 无 `DROP`；只**追加**一列，既有四列一字未动 |
| ② 改已有字段类型/既有业务语义 | **否** | 既有列类型/含义/算法逐字未改（`sale_amount`、`order_count`、`buyer_count` 与 `unknown` 归并全部保持原样）；新增列只**扩大**表形，`INSERT` 列表在末尾追加 ⇒ 既有四列**位置与取值**不变。**特别说明**：本列**不**新造语义 —— 它逐字复用同层已发布表的净额定义 |
| ③ 改已发布 Flyway migration | **否** | 本轮**不新增也不修改**任何迁移；`db/meta/V1–V25`、`db/metric/V1–V10` 字节未动。`warehouse/ddl/**` 是历史声明脚本，**不是** Flyway 迁移（S3-08/S3-11 先例） |
| ④ 写/迁移正式 3306 数据 | **否** | 本轮 **0 次连库**；无任何 SQL 触库 |
| ⑤ 切 ACTIVE | **否** | 未运行发布作业 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **否** | 未改；且检索证明**契约里没有** `net_sale_amount`/`region_sale` 声明（§7 检索为空） |
| ⑦ 改 V3.0 总体架构 | **否** | 不加组件、不改分层；只加一列 ＋ 测试 |
| ⑧ 改正式项目范围 | **否** | 做的是设计 L319 已经写明的列；非新范围 |
| ⑨ 删除已发布功能 | **否** | 无删除 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 无新依赖、无新进程 |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 备选方案是"在 ADS 层算净额"⇒ ① 与设计 L319 冲突（该列被明确声明在 **DWS** 表上）② `ads_region_sale` 当前**无生产者**（G-04 未决）⇒ 备选方案根本不可用；设计已把落点定死，不构成分叉 |

**本切片不做（留在 §8 登记）**：真集群上**已存在**的 `dws_region_sale_day` **不会**因
`CREATE TABLE IF NOT EXISTS` 而获得新列 ⇒ 真环境需 `ALTER TABLE … ADD COLUMNS`（**未测**，部署事项）。

---

## 4. 本轮实施清单

| # | 文件 | 动作 |
|---|---|---|
| 1 | `spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala` | `regionSaleDay` 投影**末尾追加** `SUM(amount) - COALESCE(SUM(refund_amount), 0) AS net_sale_amount`；补 scaladoc（口径同式 / `unknown` 计入 / 列位置约束） |
| 2 | `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` | 唯一所有者建表语句加 `net_sale_amount DECIMAL(18,2)`（表末尾） |
| 3 | `warehouse/ddl/03-dws.sql` | 参考副本同步加列（带 `COMMENT`：净额 = 已支付金额 − 已支付退款，与 `dws_trade_day` 同式，S3-12） |
| 4 | `spark-jobs/src/test/scala/com/graduation/analytics/DwsSchemaOwnerSpec.scala` | 冻结快照 `Frozen` 的 `dws_region_sale_day` 加 `net_sale_amount -> DECIMAL(18,2)`（第四份独立依据，改它必须显式） |
| 5 | `spark-jobs/src/test/scala/com/graduation/analytics/DwsRegionNetSaleSpec.scala`（**新**，4 条） | ① 逐地区 `net` ＋ 未支付行整行不计（三/四/五类夹具探针）② 跨层对账（含 unknown ⇔ 排除 unknown 就不等）③ 空值与负值规则 ④ 运行期表形 |
| 6 | `spark-jobs/src/test/scala/com/graduation/analytics/DwsAdsChainExecSpec.scala` | region oracle/读回补 `net_sale_amount`（既有用例内加断言，**条数不变**）⇒ 端到端链路上也钉住 |
| 7 | `scripts/run-tests.ps1` | spark 基线 `240 → 244` ＋ S3-12 依据段 |

**未改（必须一字未动）**：任何 Java 源、任何 Flyway 迁移、`contract-specs/**`、两册正式文档、
`ProductSaleDay`（`dws_product_sale_day` 仍无退款列 —— 那是**未决口径**，见 §8）。

---

## 5. 反熵守卫与「无回归」证据

### 5.1 RED（先红后绿，两轮都如实留痕）

- **编译期一轮（如实记）**：新 spec 首跑 `mvnExit=1`，但是**我自己写坏了源文件** ——
  scaladoc 里写了 `**sale/net**/order/buyer`，其中 `**/` 提前**结束块注释** ⇒ 后续中文标点变"illegal character"
  （`.verify/…/red/spark-targeted-red.log`）。判为**夹具/文件缺陷**，改成 `sale/net/order/buyer（四列）` 后重跑。
- **RED 二轮（关键证据）**：`4 条全红`，`Tests: succeeded 0, failed 4`，根因逐条落在真缺陷上：
  ```
  [UNRESOLVED_COLUMN.WITH_SUGGESTION] A column or function parameter with name `net_sale_amount`
  cannot be resolved. Did you mean one of the following? [`sale_amount`, `buyer_count`, `order_count`, `region`, `dt`].
  Relation spark_catalog.dw_regionnet_dws.dws_region_sale_day[region,buyer_count,order_count,sale_amount,dt] parquet
  ```
  ⇒ **运行期**真实表形就是四列 ＋ 分区列（`mvnExit=1`，`.verify/…/red/spark-targeted-red.log`）。
- **RED 三轮（端到端链路同证）**：`DwsAdsChainExecSpec` 在补 oracle 后 `Tests: succeeded 1, failed 22, canceled 8`，
  捕获阶段即被同一 `UNRESOLVED_COLUMN` 打崩（`.verify/…/red/spark-targeted-chain-red.log`）
  ⇒ 证明该断言点**挂在真实链路上**，不是只对我自己的夹具成立。

### 5.2 GREEN（点名套件）

- 靶向三套件（`DwsRegionNetSaleSpec,DwsSchemaOwnerSpec,DwsAdsChainExecSpec`）首轮 `41 通过 / 1 失败`，
  唯一红是我**断言写错**：运行期 `fieldNames` 含 Spark 追加的**分区列** `dt`
  （实测 `[region, buyer_count, order_count, sale_amount, net_sale_amount, dt]`）⇒ 判为**夹具缺陷**并修正。
- 复跑 `DwsRegionNetSaleSpec`：`Tests: succeeded 4, failed 0`、`mvnExit=0`
  （`.verify/…/green/spark-targeted-green2.log`）。
- 靶向命令：`mvn -o -f spark-jobs/pom.xml test "-Dsuites=com.graduation.analytics.DwsRegionNetSaleSpec"`（JDK8）。

### 5.3 双档门禁（fresh 真跑，全部改动落盘后）

见 §5.4 实测记录（`gate/spark-gate.log`、`gate/default-gate.log`）。

### 5.4 门禁实测记录

- **spark 档 `[PASS exit=0]`**（RunId `s312_20260916_spark`）：
  `Total number of tests run: 244`、`Suites: completed 30, aborted 0`、
  `Tests: succeeded 244, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、
  `本轮新写（mtime ≥ 启动时刻）= True`、`JDK8 取证：Java version: 1.8 = True`、
  `基线比对：tests=244 MATCH`（基线 `240 → 244`，增量 **+4** ＝ 新 spec 4 条；套件 `29 → 30`，+1 ＝ 新 spec）。
- **default 档计数全 MATCH、`[FAIL exit=7]`**（RunId `s312_20260916_def`）：
  `analytics-server 909 (F=1 E=0 S=1)`（明细 `90+350+163+67+92+147`）、`mall-simulator 13`、
  `synthetic-data-generator 106`、**三棵树 `1028`（基线 1028）**、**无 DRIFT**。
  唯一红仍是**已登记环境性**缺口 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
  （本工作树无 `landing/manifests`）—— 本轮**未修、未复制 manifest、未用开关掩盖**。
  **本档为"未波及"对照证据**：本轮改动面 = Scala/spark-jobs 源 ＋ `warehouse/ddl/03-dws.sql` 文本 ＋
  `scripts/run-tests.ps1` 基线注释（**不含任何 Java 源**）⇒ Java 侧计数应与上轮**完全相同**
  （`909/13/106/1028` MATCH 印证）。

### 5.5 不得越界表述

本切片**不证**：真 Hive 上已存在表的物理列序/加列已生效、`spark-submit`、metastore 行为、
"净额＝真实到账收入"（退款归属期未冻结）、`ads_region_sale` 的产出（无生产者）。

---

## 6. 未测与边界

| 项 | 状态 |
|---|---|
| 真集群上**已存在**的 `dw_dws.dws_region_sale_day` | **未测**：`CREATE TABLE IF NOT EXISTS` **不加列** ⇒ 真环境需 `ALTER TABLE … ADD COLUMNS net_sale_amount DECIMAL(18,2)`；本轮 0 次连库，**未**在真库上执行 |
| 退款**归属期**跨业务日 | **未决口径（已登记）**：本列与 `dws_trade_day` 同为**同日**口径（订单行上的 `refund_amount`）；「退款归属期需冻结」（设计 L428）未决 ⇒ **不得**把本列读作"真实到账净收入" |
| 真 `spark-submit` / Hive metastore / Parquet 落盘 | **未跑**（测试域＝Scala `local[1]` ＋ in-memory catalog，`P2TestSupport` 自陈） |
| 隔离档（3307） | **未测**（无监听） |
| `ads_region_sale` 的 ADS 产出 | **不存在**：无生产者（G-04/门⑦⑧ 未决，`PROJECT_STATUS:189`）；本轮不动 |

---

## 7. 检索证据（"未改即证据"）

| 检查 | 命令/方式 | 结果 |
|---|---|---|
| 本轮改动面 | `git status --short` | 7 个路径：` M scripts/run-tests.ps1`、` M …/LocalSchemaInitJob.scala`、` M …/DwsSql.scala`、` M …/DwsAdsChainExecSpec.scala`、` M …/DwsSchemaOwnerSpec.scala`、` M warehouse/ddl/03-dws.sql`、`?? …/DwsRegionNetSaleSpec.scala` |
| 契约里有无该列声明（门⑥） | 对 `contract-specs/**`（9 文件）检索 `net_sale_amount\|region_sale` | **0 命中** ⇒ 门⑥ 未触发（`city_level` 仅出现在 canonical-event 事件 schema，与 DWS region 列无关） |
| 迁移是否被碰 | `git status --short` 未列出 `db/meta/**`、`db/metric/**` | 未碰；本轮**不新增迁移** |
| 该表还有无别的生产者/消费者 | 全仓检索 `dws_region_sale_day`（排除 `target`/`.verify`/验收留痕） | 生产侧仅 `DwsSql.regionSaleDay`（写）＋ `UserProductDwsJob`（调用/分区证据）；无任何 Java 侧消费者、无质量规则引用 ⇒ 加列不影响在产规则 |
| 设计沿革 | `docs/design/history/项目设计文档 V2.3/V2.4/V2.5.md` 同表行 | 三份历史文稿均写 `sale/net/order/buyer` ⇒ 欠账自 V2.3 起，非 V3.0 新立 |

---

## 8. 遗留 / 后续（登记，不擅自实施）

| 项 | 类型 | 处置 |
|---|---|---|
| `dws_product_sale_day` 无退款/净额列（设计 §12.1 **L316** 含「退款」） | **未决口径（非 A 类）** | 订单行**多行分摊**退款尚未冻结（部分退款在商品粒度如何摊？）⇒ 属门⑪ 级方案选择；**只登记**，待总控口径 |
| 真集群 `ALTER TABLE … ADD COLUMNS` | 部署事项（**未测**） | 需在有数据表上实测并记录（参考 S3-07-R-2/S3-08-R-1 同类登记）；本轮未连库 |
| `ads_region_sale` 无生产者（G-04） | 待总控 | `PROJECT_STATUS:189` 已登记；本轮不动 |
| `PROJECT_STATUS:200` ① 的另一半（测试夹具手写 `INSERT` 列清单）、② ADS 侧守卫、③ `01-dwd.sql`/`02-dims.sql` 同型守卫 | 候选 | 均属 S3-11 已登记遗留，本轮未动 |
| 退款归属期跨业务日 | 已登记 | 影响 `dws_trade_day`/`dws_region_sale_day` 两处净额语义；未决前不得下"真实收入"结论 |
