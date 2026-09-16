# S3-13 设计差异登记（2026-09-16）

> 类型：**A 类（修正实现遗漏 ＋ 补对账守卫）** —— 给 DWD 3 张 ＋ DIM 2 张有所有者的表补
> 「参考副本 ↔ 唯一所有者 ↔ 写入投影」**三方一致**守卫（S3-11 的 DWS 守卫同型扩面），
> 并修一处**投影不可核对**的实现遗漏：`TradeDwdJob.orderDetailInsertSql` 的 4 个投影元素补显式别名。
> 关闭的登记项：`docs/PROJECT_STATUS.md:212(c)`（`01-dwd.sql`/`02-dims.sql` 尚无同型三方守卫）。
> 纪律：未实测不写结论；本文件不冒充"真 Hive 表形已验证"，也不把 `dim_date`/`dim_region`/`dim_metric`
> 的**无生产链**说成"已解决"（§8 只登记，未实现）。

---

## 0. 一句话结论

DWD/DIM 的物理表形有**三份**来源：参考副本 `warehouse/ddl/01-dwd.sql`（3 张）与 `02-dims.sql`（5 张）、
唯一所有者 `LocalSchemaInitJob.statements`、以及写入投影（`DwdSql`/`DimSql`/`TradeDwdJob` 的
`INSERT OVERWRITE … SELECT` 列表）。Spark 按**位置**写 Parquet，三份列序差一位就是**值串列**的静默错数，
而**此前无人看守**（S3-11 只覆盖 DWS）。

本轮的实测发现是**两类**：

1. **一处投影不可静态核对（真缺陷，已修）**：`dwd_order_detail` 的 SELECT 列表里有 **4 个元素没有列名**
   —— `$orderKey` / `$userKey` / `CASE … END`（product）/ `CAST(t.quantity AS INT)`。它们的列义**只**存在于
   DDL 的列序里，任何静态守卫都无法核对（RED 逐字：投影元素既无顶层 AS 别名也不是纯列引用：
   `[CAST(REGEXP_EXTRACT(t.order_id, '^[A-Za-z]*([0-9]+)$', 1) AS BIGINT)]`）。而这正是
   `TradeDwdJob` L128-148 记录的 **run 44/45/46 连续失败**（`Cannot safely cast user_key "STRING" to "BIGINT"`）
   那一类位置错位缺陷的**唯一入口**：`t.dt` 是第 20 项还是第 23 项，肉眼与运行期都看不出来。
   ⇒ 修法是给这 4 项补**显式别名**（位置写入下别名**惰性**，取值断言不变，见 §5.4）。
2. **DIM 表集不一致（登记事实，非本轮实现）**：参考副本 `02-dims.sql` 声明 **5** 张 DIM 表，
   唯一所有者只建 **2** 张（`dim_user`/`dim_product`）；`dim_date`/`dim_region`/`dim_metric`
   **无所有者、无写入投影**。本轮把这 3 张写成**显式白名单**（谁偷偷建一半、谁偷偷写一半，测试立刻红），
   **不**擅自补生产链（涉及分区/版本/同步方式等未决口径，§8）。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §9.2 **L322** | 「历史DDL声明ODS4/DWD3/DIM5/DWS7/ADS10，**不代表29张都有正确数据**。已知DIM仅user/product有日常产出、分类/地区ADS缺生产链，需逐项补证。**目录schema与数据库真实表形状必须对照，不靠文件名推理**」 | 本切片就是这条的**逐字落地**：把"目录 schema"与"真实表形状（所有者＋写入投影）"逐列对照，并把"DIM 仅 user/product 有产出"写成**显式白名单**而非注释 |
| 设计 §9.2 **L309** | 「DIM dim_date/dim_region/dim_metric ｜ 日期、地区、指标编码 ｜ 日期区间幂等；地区未知成员；指标字典同步，**不只建空表**」 | **只登记不实现**：这 3 张表的产出属未决口径（§8）⇒ 本轮只钉"表形不许偷偷漂" |
| 设计 §9.2 **L310/L311** | 「DWD dwd_user_behavior_detail ｜ … **source+eventId去重，合法枚举、稳定排序**」「DWD dwd_order_detail ｜ source+orderId、user_key、状态、实付、累计退款、有效标志、时间 ｜ **顺序重放/退款最新状态、订单项金额核对**」 | 列序/列集与设计逐条对齐（`final_paid_flag`/`final_refunded_flag`/`net_paid_amount` 等"有效标志＋实付"列都在，且位置与所有者一致） |
| 设计 §9.2 **L308** | 「DIM dim_user/dim_product ｜ source+业务键；surrogate、属性、版本/生效时间」 | `*_key` 代理键在列尾（与 `02-dims.sql:8-11` 的 D-083…D-094 声明一致），三方一致断言覆盖 |
| 指导书 §7 阶段2 **L142** | 「按设计的 ODS 四主题、**DIM**、DWD、DWS、ADS 模型**补齐真实生产路径**」 | DIM 的 3 张缺生产链属**阶段2 遗留**（§8 登记，未擅自补） |
| 指导书 §7 阶段3 **L149** | 「Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**」 | 本守卫是"表形层对账"；指标值对账仍属各专题切片 |
| 设计 §11.2 **L455** | 「分类/地区金额求和必须包含unknown，不丢未匹配维度」 | 本切片不动任何指标口径；`dws_region_sale_day` 的 `region` 列在 DWD/DIM 侧无 `dim_region` 依赖（用 `city_level`），见 §7 检索 |

---

## 2. 语义声明（本轮冻结）

| 项 | 冻结取值 | 依据 |
|---|---|---|
| 覆盖表集 | DWD **3**（`dwd_user_behavior_detail`/`dwd_reject_record`/`dwd_order_detail`）＋ DIM **2**（`dim_user`/`dim_product`） | 参考副本 `01-dwd.sql` 3 张；`02-dims.sql` 5 张中**有所有者**的 2 张 |
| 参照物 | 列**名/类型/顺序**逐列相等；分区列相等；`dt` 不得混进普通列 | S3-11 同型（Spark 按位置写 Parquet） |
| 分区形态 | 5 张表全部 `PARTITIONED BY (dt STRING)`；**静态**分区 4 条（`PARTITION(dt = '$dt')`），`dwd_order_detail` 为**动态**分区（`PARTITION (dt)`，无值） | 实测 SQL 原文（§4） |
| 动态分区规则 | 分区列 `dt` **必须**是 SELECT 列表的**最末位**，且投影 = `所有者数据列 ++ 动态分区列` | `TradeDwdJob` L128-148 实测缺陷记录 ＋ 本轮 B2 断言 |
| 静态分区规则 | 分区值必须是**字符串字面量**（`'…'`）；投影 == 所有者数据列（分区列**不**出现在 SELECT 里） | 实测 SQL 原文；防止把动态写法误当静态 |
| 写入口唯一 | 每张有所有者的表在 `spark-jobs/src/main/scala` 里 `INSERT OVERWRITE` 命中数**恰好 1**；白名单 3 张表命中数**恰好 0** | 反熵"唯一所有者"；实测 16 条 main 侧 INSERT 中 DWD/DIM 共 5 条 |
| 冻结快照 | `Frozen`（5 张 × 列名→类型）＝ **第四份独立依据**，改它必须显式 | S3-11 先例（防"三份一起漂"） |
| **不判什么** | 不判任何口径/指标值；不判真 Hive 物理落盘；不断言白名单 3 张表"应当有数据"（只判"没人偷偷建/写一半"） | 越界即假结论 |

---

## 3. 为什么是 A 类（逐门核对）

| 门 | 是否触发 | 理由 |
|---|---|---|
| ① DROP TABLE/COLUMN | **否** | 无任何 `DROP`；**未增删任何列**，表形一字未改（唯一生产改动是 SELECT 列表加**别名**） |
| ② 改已有字段类型/既有业务语义 | **否** | 列名/类型/顺序/分区全部未动；`INSERT … SELECT` 按**位置**对齐 ⇒ 别名对写入结果**惰性**（§5.4 逐值断言不变为证） |
| ③ 改已发布 Flyway migration | **否** | 本轮**不新增也不修改**任何迁移；`db/meta/V1–V25`、`db/metric/V1–V10` 字节未动（`warehouse/ddl/**` 是历史声明脚本，非 Flyway 迁移） |
| ④ 写/迁移正式 3306 数据 | **否** | 本轮 **0 次连库**；无任何 SQL 触库 |
| ⑤ 切 ACTIVE | **否** | 未运行发布作业 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **否** | 未改；§7 检索证明改动面 0 命中 `contract-specs/**` |
| ⑦ 改 V3.0 总体架构 | **否** | 不加组件、不改分层；只加测试守卫 ＋ 一处 SQL 别名 |
| ⑧ 改正式项目范围 | **否** | 守卫对象是设计 §9.2 L322 逐字要求"目录 schema 与真实表形状必须对照"；白名单 3 张表**只登记不实现** |
| ⑨ 删除已发布功能 | **否** | 无删除 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 无新依赖、无新进程、无新表 |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 备选方案是"不补别名、让守卫只对 19 项打钩"⇒ 守卫对最危险的 4 项**永远失明**；设计 L322 要求的正是"不许靠文件名/肉眼推理"，故不构成分叉 |

---

## 4. 本轮实施清单

| # | 文件 | 动作 |
|---|---|---|
| 1 | `spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala` | `orderDetailInsertSql` 的 4 个投影元素补显式别名（`AS order_id`/`user_id`/`product_id`/`quantity`）＋ KDoc 增 S3-13 段（为何补、为何惰性、谁核对） |
| 2 | `spark-jobs/src/test/scala/com/graduation/analytics/DwdDimSchemaOwnerSpec.scala`（**新**，10 条） | A1 参考副本 3 张 DWD ↔ 所有者逐列；A2 参考副本 2 张有所有者 DIM ↔ 所有者；A3 分区列；A4 两份参考副本禁 `ALTER` 旁路；B1 写入投影列序（5 条）；B2 动态分区列落末位 ＋ **守卫自检**；B3 每表恰好一条 `INSERT OVERWRITE`（全 main 源码扫描）＋ 白名单零写入；C1 冻结快照；C2 参考副本多出的 3 张 DIM 表白名单；C3 表集一致 |
| 3 | `scripts/run-tests.ps1` | spark 基线 `244 → 254` ＋ S3-13 依据段 |

**未改（必须一字未动）**：任何 Java 源、任何 Flyway 迁移、`contract-specs/**`、两册正式文档、
`warehouse/ddl/*.sql`（DWD/DIM 两份参考副本**本来就没有漂移**，故本轮**零文本改动**）、
`LocalSchemaInitJob.scala`（所有者未动）、`DwdSql.scala`/`DimSql.scala`（这 4 条投影本来就全带别名）。

---

## 5. 反熵守卫与「无回归」证据

### 5.1 RED（先红后绿，两轮都如实留痕）

- **RED 首轮**（RunId `s313_20260916_red`）：`Tests: succeeded 8, failed 2`，两条红的根因**正确**
  （B1/B2 都卡在同一个解析拒绝），但**失败信息是我写坏的**：`case other => …[$other]` 里
  `other` 绑定的是匹配值 `None`，于是逐字输出 `[None]` —— 等于把肇事元素丢了（判为**夹具缺陷**，已修：
  改成 `case _ => …[$item]`）。留痕：`.verify/…/red1.log`。
- **RED 二轮（关键证据）**（RunId `s313_20260916_red2`）：`Tests: succeeded 8, failed 2`，点名逐字：
  ```
  java.lang.IllegalArgumentException: dwd_order_detail: 投影元素既无顶层 AS 别名也不是纯列引用：
    [CAST(REGEXP_EXTRACT(t.order_id, '^[A-Za-z]*([0-9]+)$', 1) AS BIGINT)]
  ```
  ⇒ 四个无别名元素中的**第一个**；另三个同因（`$userKey`、product 的 `CASE … END`、`CAST(t.quantity AS INT)`）。
  **注意**：`CAST(t.quantity AS INT)` 也证明"朴素末尾 AS 正则"会把它误读成别名 `INT`
  —— 本解析器只在**顶层**找 `AS`，故拒绝而不是猜错。

### 5.2 GREEN（点名套件）

- 补别名后靶向复跑（RunId `s313_20260916_green`）：`Tests: succeeded 10, failed 0`、`mvnExit=0`
  （`.verify/s313/green1.log`）。
- 靶向命令：`mvn -o -f spark-jobs/pom.xml test "-Dsuites=com.graduation.analytics.DwdDimSchemaOwnerSpec"`（JDK8）。

### 5.3 双档门禁（fresh 真跑，全部改动落盘后）

见 §5.4 实测记录（`.verify/s313/spark-gate.log`、`.verify/s313/default-gate.log`）。

### 5.4 门禁实测记录

- **spark 档 `[PASS exit=0]`**（RunId `s313_20260916_spark`）：
  `Total number of tests run: 254`、`Suites: completed 31, aborted 0`、
  `Tests: succeeded 254, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、
  `本轮新写（mtime ≥ 启动时刻）= True`、`JDK8 取证：Java version: 1.8 = True`、
  `基线比对：tests=254 MATCH`（基线 `244 → 254`，增量 **+10** ＝ 新 spec 10 条；套件 `30 → 31`，+1）。
- **default 档计数全 MATCH、`[FAIL exit=7]`**（RunId `s313_20260916_def`）：
  `analytics-server 909 (F=1 E=0 S=1)`、`mall-simulator 13`、`synthetic-data-generator 106`、
  **三棵树 `1028`（基线 1028）**、**无 DRIFT**。唯一红仍是**已登记环境性**缺口
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（本工作树无 `landing/manifests`）
  —— 本轮**未修、未复制 manifest、未用开关掩盖**。
  **本档为"未波及"对照证据**：本轮改动面 = Scala/spark-jobs 源 ＋ `scripts/run-tests.ps1` 基线注释
  （**不含任何 Java 源**）⇒ Java 侧计数应与上轮**完全相同**（`909/13/106/1028` MATCH 印证）。
- **"别名惰性"的实测依据**：`DimDwdChainExecSpec`（dim/dwd 链路逐值读回）与 `DwsAdsChainExecSpec`
  （端到端 dwd→dws→ads 逐值断言）本轮**全部绿**，且两套件的断言内容是**取值**而非仅行数
  ⇒ 补别名前后写入结果逐值未变（若别名改变了语义，这两套件必红）。

### 5.5 不得越界表述

本切片**不证**：真 Hive 上的物理表形/DESCRIBE、`spark-submit`、metastore 行为；
`dim_date`/`dim_region`/`dim_metric` 的**产出**（无所有者、无写入，§8）；
任何指标口径与指标值；"三方一致 ⇒ 数据正确"（守卫只判**表形**，不判数据内容）。

---

## 6. 未测与边界

| 项 | 状态 |
|---|---|
| 真 Hive/集群上的 `DESCRIBE`/物理列序 | **未测**（测试域＝Scala `local[1]` ＋ in-memory catalog，`P2TestSupport` 自陈） |
| 真 `spark-submit` / Parquet 落盘 | **未跑** |
| 隔离档（3307） | **未测**（无监听） |
| `dim_date`/`dim_region`/`dim_metric` 的产出 | **不存在**（无所有者、无写入）——本轮只把它们钉成显式白名单 |
| 动态分区列落末位在**真集群**上的行为 | 本地链已由 B2 ＋ 端到端套件钉住；真集群 `PARTITION (dt)` 动态分区仍属**未测**（`TradeDwdJob` L144-148 的一次性对齐实验是**历史**证据，不在本轮） |

---

## 7. 检索证据（"未改即证据"）

| 检查 | 命令/方式 | 结果 |
|---|---|---|
| 本轮改动面 | `git status --short` | 3 个路径：` M scripts/run-tests.ps1`、` M …/TradeDwdJob.scala`、`?? …/DwdDimSchemaOwnerSpec.scala` |
| 契约是否被碰（门⑥） | `git status --short` 未列出 `contract-specs/**` | 未碰 |
| 迁移是否被碰（门③） | `git status --short` 未列出 `db/meta/**`、`db/metric/**` | 未碰；本轮**不新增迁移** |
| DWD/DIM 参考副本是否本就漂移 | 三方逐列比对（A1/A2/C1 实测） | **未漂移**：参考副本与所有者、写入投影三方本就一致 ⇒ 本轮对 `warehouse/ddl/01-dwd.sql`/`02-dims.sql` **零文本改动** |
| 参考副本是否有第二所有者 | 全仓检索 `dim_date\|dim_region\|dim_metric`（排除 docs） | 生产代码**零命中**；仅 `warehouse/ddl/02-dims.sql:49-83` 声明 ＋ `warehouse/README.md` 提及 ⇒ 白名单成立（与 `docs/audit/v2-completeness-audit.md:133-135`、`docs/status-history/项目实施进度与任务看板.md:437` 的"有 DDL 无数据"登记一致） |
| DWD/DIM 有没有 Java 侧写者 | 本轮 B3 全 `spark-jobs/src/main/scala` 扫描 ＋ `git status` | main 侧 16 条 `INSERT OVERWRITE` 中 DWD/DIM 共 5 条，各表恰好 1 条；无 Java 侧写者 |
| `dws_region_sale_day` 是否依赖 `dim_region` | 检索 `DwsSql.regionSaleDay` 的 `region` 来源 | `region = COALESCE(city_level,'unknown')`（来自 DWD/`dim_user` 的城市等级），**不**读 `dim_region` ⇒ S3-12 与本切片的 DIM 白名单互不阻塞 |

---

## 8. 遗留 / 后续（登记，不擅自实施）

| 项 | 类型 | 处置 |
|---|---|---|
| `dim_date`/`dim_region`/`dim_metric` **有 DDL 无生产链**（设计 §9.2 **L309**「日期区间幂等；地区未知成员；指标字典同步，**不只建空表**」；指导书 §7 阶段2 **L142**「DIM … 补齐真实生产路径」） | **未决口径（非 A 类）** | 三张表各有真问题：① `dim_date` 的分区/版本语义（参考副本**未分区**，而"日期区间幂等生成"要求固定覆盖区间与重跑语义）；② `dim_region` 的**静态版本化**字典内容与版本字段；③ `dim_metric` 要求"从 MySQL `metric_definition` **同步**"⇒ 引入 Spark→MySQL 读取通道（当前语义层由 Java `MetricAdsCatalog` 代偿）。**只登记**，待总控口径与范围裁定 |
| `PROJECT_STATUS:212` 其余两项：(d) ADS 侧同类守卫（`04-ads.sql` 多出 `snapshot_id` 数据列、8 张 `__staging` 不在副本、2 张无生产者镜像表）；`PROJECT_STATUS:199` Spark 规则码字面量与登记集跨模块守卫 | 候选（A 类） | 均属 S3-11 已登记遗留，本轮未动 |
| `PROJECT_STATUS:212(a)` 测试夹具手写 `INSERT` 列清单（`dws_user_trade_period`） | 候选（A 类） | 本轮未动 |
| 真集群动态分区（`PARTITION (dt)`）行为 | **未测** | 见 §6 |
