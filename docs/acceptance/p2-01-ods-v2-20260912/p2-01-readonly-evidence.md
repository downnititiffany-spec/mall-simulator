# P2-01 ODS v2 施工单 — 只读取证结果（实现级事实，≤400 行）

分支 `remediation/r1-boundary`｜工作区 `D:\Develop_code\GraduationProject`｜本轮**未**执行 Maven/Spark/8090-8092/DB 读写/git 写；只读手段 = `grep`/`read`/`glob` 工具 + `git status|log`、`Get-Content`、`Get-FileHash`、`Get-ChildItem`
本次唯一新建物 = 本文件（`git status --porcelain` 中 `?? p2-01-readonly-evidence.md`；`warehouse/`、`spark-jobs/`、`contract-specs/` 零改动）

---

## 1. 两个 ODS 列所有者（当前原文）

### 1.1 owner A：`warehouse/ddl/00-ods.sql`（106 行，sha256 `36EDBF7929D9AA6441F898A6D5CF3346C99E7BB09E13F0F2914907BFA1161693`）

四表共同形态：`NN|CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ods.<T> (` … `NN|)` + `COMMENT '<中文表注释>'` + 分区子句 + `STORED AS PARQUET` + `LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ods.db/<T>';`
表注释/分区/STORED/LOCATION 行号：`ods_user_event` L27/28/29/30（分区子句为 `PARTITIONED BY (dt STRING COMMENT '业务日期 yyyyMMdd', hour STRING COMMENT '小时 HH')`）；`ods_product_event` L54/55/56/57；`ods_behavior_event` L76/77/78/79；`ods_trade_event` L103/104/105/106（后三表分区子句为 `PARTITIONED BY (dt STRING, hour STRING)`）。

四表列定义（`行号|列名 类型  [COMMENT '…']`，顺序即 DDL 顺序）：

```
ods_user_event   (L12-25)
12|event_id STRING COMMENT '全局唯一事件ID'      13|event_type STRING
14|event_time STRING COMMENT '业务时间(ISO-8601带时区)'
15|ingest_time STRING COMMENT '采集时间，链路延迟=ingest_time-event_time'
16|source_system STRING                          17|schema_version STRING COMMENT '未知版本隔离，不发布'
18|trace_id STRING
19|payload_user_id STRING   20|payload_age_group STRING   21|payload_city_level STRING
22|payload_member_level STRING   23|payload_register_time STRING
24|source_file STRING COMMENT '来源 Landing 文件'   25|ingest_batch_id BIGINT COMMENT '采集批次号'
```
```
ods_product_event (L34-52)
34|event_id STRING   35|event_type STRING   36|event_time STRING   37|ingest_time STRING
38|source_system STRING   39|schema_version STRING   40|trace_id STRING
41|payload_product_id STRING   42|payload_product_name STRING   43|payload_category_id STRING
44|payload_category_name STRING   45|payload_parent_category_id STRING
46|payload_parent_category_name STRING   47|payload_brand_id STRING
48|payload_price DECIMAL(18,2)   49|payload_cost DECIMAL(18,2)   50|payload_status STRING
51|source_file STRING   52|ingest_batch_id BIGINT
```
```
ods_behavior_event (L61-74)
61|event_id STRING   62|event_type STRING   63|event_time STRING   64|ingest_time STRING
65|source_system STRING   66|schema_version STRING   67|trace_id STRING
68|payload_user_id STRING   69|payload_product_id STRING   70|payload_session_id STRING
71|payload_behavior_type STRING COMMENT 'view/favorite/cart_add/cart_remove/search'
72|payload_channel STRING   73|source_file STRING   74|ingest_batch_id BIGINT
```
```
ods_trade_event (L83-101)
83|event_id STRING   84|event_type STRING   85|event_time STRING   86|ingest_time STRING
87|source_system STRING   88|schema_version STRING   89|trace_id STRING
90|payload_order_id STRING   91|payload_user_id STRING   92|payload_payment_id STRING
93|payload_refund_id STRING   94|payload_product_id STRING
95|payload_amount DECIMAL(18,2) COMMENT '金额字符串转 DECIMAL 后存放'
96|payload_total_amount DECIMAL(18,2)   97|payload_status STRING   98|payload_reason STRING
99|payload_items STRING COMMENT 'order_created 的 items JSON 数组（DWD 展开）'
100|source_file STRING   101|ingest_batch_id BIGINT
```

### 1.2 owner B：`spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` ODS 段 L43-82

形态（4 条语句一致）：`(ns.ods, s"""` + `CREATE TABLE IF NOT EXISTS ${ns.ods}.<T> (` + 列定义 + `)` + `USING parquet PARTITIONED BY (dt STRING, hour STRING)""")`。
语句起始行：`ods_user_event` L43-50、`ods_product_event` L52-62、`ods_behavior_event` L64-71、`ods_trade_event` L73-82。

```
ods_user_event (L45-49)
45|event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
46|source_system STRING, schema_version STRING, trace_id STRING,
47|payload_user_id STRING, payload_age_group STRING, payload_city_level STRING,
48|payload_member_level STRING, payload_register_time STRING,
49|source_file STRING, ingest_batch_id BIGINT)
```
```
ods_product_event (L54-61)
54|event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
55|source_system STRING, schema_version STRING, trace_id STRING,
56|payload_product_id STRING, payload_product_name STRING, payload_category_id STRING,
57|payload_category_name STRING, payload_parent_category_id STRING,
58|payload_parent_category_name STRING,
59|payload_brand_id STRING, payload_price DECIMAL(18,2), payload_cost DECIMAL(18,2),
60|payload_status STRING,
61|source_file STRING, ingest_batch_id BIGINT)
```
```
ods_behavior_event (L66-70)
66|event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
67|source_system STRING, schema_version STRING, trace_id STRING,
68|payload_user_id STRING, payload_product_id STRING, payload_session_id STRING,
69|payload_behavior_type STRING, payload_channel STRING,
70|source_file STRING, ingest_batch_id BIGINT)
```
```
ods_trade_event (L75-81)
75|event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
76|source_system STRING, schema_version STRING, trace_id STRING,
77|payload_order_id STRING, payload_user_id STRING, payload_payment_id STRING,
78|payload_refund_id STRING, payload_product_id STRING,
79|payload_amount DECIMAL(18,2), payload_total_amount DECIMAL(18,2),
80|payload_status STRING, payload_reason STRING, payload_items STRING,
81|source_file STRING, ingest_batch_id BIGINT)
```

### 1.3 两所有者差异（逐列比对结果）

| # | 差异项 | owner A | owner B |
|---|---|---|---|
| D1 | 表类型 | `CREATE EXTERNAL TABLE IF NOT EXISTS`（L11/33/60/82） | `CREATE TABLE IF NOT EXISTS`（L44/53/65/74） |
| D2 | 存储子句 | `STORED AS PARQUET`（L29/56/78/105） | `USING parquet`（L50/62/71/82） |
| D3 | 物理路径 | 显式 `LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ods.db/<table>'`（L30/57/79/106） | 无 LOCATION，由 `spark.sql.warehouse.dir` 决定 |
| D4 | 库名来源 | Hive 变量 `${WAREHOUSE_PREFIX}` | Scala 插值 `${ns.ods}`（L37/44/45…） |
| D5 | COMMENT | 有：列注释 L12/14/15/17/24/25、L71、L95/99；分区列注释 L28；表注释 L27/54/76/103 | **全部无** COMMENT |
| D6 | 列名/类型/顺序 | 与 owner B **逐列一致，零差异**（四表列序列两侧相同，DECIMAL(18,2) 仅 price/cost/amount/total_amount） | 同左 |
| D7 | 位置 | 独立文件 | 嵌在 `statements(ns): List[(String,String)]` 的 37 条 DDL 中 |

- 分区列两侧均 `(dt STRING, hour STRING)`（A L28/55/77/104；B L50/62/71/82），顺序一致。
- 两个所有者**同时都不存在**这 4 列：`raw_event_type`、`landing_file`、`payload_json`、`payload_hash`（全 `warehouse/`、`spark-jobs/`、`analytics-server/` grep 零命中）。
- owner A 的测试约束：`WarehouseNameLiteralGateTest.java:127`（`warehouse/ddl` 下 SQL 文件 `hasSize(5)`）、`:144`（每文件含 `${WAREHOUSE_PREFIX}_`）、`:155-158`（层集合 {ods,dwd,dim,dws,ads}；变量集合 `containsExactly("WAREHOUSE_PREFIX")`）。
- 运行期列序旁证（历史日志）：`.verify/r8-items-dwd-verify.log:39` = `dw_ods.ods_trade_event[event_id#4,event_type#5,event_time#6,ingest_time#7,source_system#8,schema_version#9,trace_id#10,payload_order_id#11,payload_user_id#12,payload_payment_id#13,payload_refund_id#14,payload_product_id#15,payload_amount#16,payload_total_amount#17,payload_status#18,payload_reason#19,payload_items#20,source_file#21,ingest_batch_id#22L,dt#23,hour#24] parquet`（仅覆盖 trade_event；其余三表同形态日志仓内无命中）。
- 存量表不自动获得新列：`LocalSchemaInitJob.reconcile` L287-297 只遍历 `R7_ADDED_COLUMNS`（L259-261，仅 `ads_operation_overview` 与其 `__staging` 的 `full_refund_rate DECIMAL(8,4)`），**不含任何 ODS 表**；ODS 走 `CREATE TABLE IF NOT EXISTS`。

---

## 2. 写入侧：`OdsLoadSql.scala` 全部 SELECT 表达式

`spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala`（215 行，sha256 `061A0CF65A85140FAB325F403B40A656E06C9B0E2B4996C51AA82C9D680C2DD8`）
模板签名：`def userFromLanding/productFromLanding/behaviorFromLanding/tradeFromLanding(ns: WarehouseNamespace, batchId: Long): String` = L92/L113/L142/L163；`rejectedSelect()` = L192。

| 目标列 | 现状表达式（原文，括号内 = 四个模板中的出现行号） |
|---|---|
| `event_id` | `event_id`（96/117/146/167） |
| `event_type` | `event_type`（96/117/146/167） |
| `event_time` | `event_time`（**裸列、无任何转换**，96/117/146/167） |
| `ingest_time` | `ingest_time`（96/117/146/167） |
| `source_system` | `source_system`（**裸列，取行内值**，96/117/146/167） |
| `schema_version` | `schema_version`（97/118/147/168） |
| `trace_id` | `trace_id`（97/118/147/168） |
| `payload_user_id`(user) | `payload.user_id AS payload_user_id`（98） |
| `payload_age_group` | `payload.age_group AS payload_age_group`（99） |
| `payload_city_level` | `payload.city_level AS payload_city_level`（100） |
| `payload_member_level` | `payload.member_level AS payload_member_level`（101） |
| `payload_register_time` | `payload.register_time AS payload_register_time`（102） |
| `payload_product_id`(product) | `payload.product_id AS payload_product_id`（119） |
| `payload_product_name` | `payload.product_name AS payload_product_name`（120） |
| `payload_category_id` | `payload.category_id AS payload_category_id`（121） |
| `payload_category_name` | `payload.category_name AS payload_category_name`（122） |
| `payload_parent_category_id` | `payload.parent_category_id AS payload_parent_category_id`（123） |
| `payload_parent_category_name` | `payload.parent_category_name AS payload_parent_category_name`（124） |
| `payload_brand_id` | `payload.brand_id AS payload_brand_id`（125） |
| `payload_price` | `CASE WHEN payload.price IS NULL OR payload.price = '' THEN NULL ELSE CAST(payload.price AS DECIMAL(18,2)) END AS payload_price`（126-127） |
| `payload_cost` | `CASE WHEN payload.cost IS NULL OR payload.cost = '' THEN NULL ELSE CAST(payload.cost AS DECIMAL(18,2)) END AS payload_cost`（128-129） |
| `payload_status`(product) | `payload.status AS payload_status`（130） |
| `payload_user_id`(behavior) | `payload.user_id AS payload_user_id`（148） |
| `payload_product_id`(behavior) | `payload.product_id AS payload_product_id`（149） |
| `payload_session_id` | `payload.session_id AS payload_session_id`（150） |
| `payload_behavior_type` | `payload.behavior_type AS payload_behavior_type`（151） |
| `payload_channel` | `payload.channel AS payload_channel`（152） |
| `payload_order_id` | `payload.order_id AS payload_order_id`（169） |
| `payload_user_id`(trade) | `payload.user_id AS payload_user_id`（170） |
| `payload_payment_id` | `payload.payment_id AS payload_payment_id`（171） |
| `payload_refund_id` | `payload.refund_id AS payload_refund_id`（172） |
| `payload_product_id`(trade) | `payload.product_id AS payload_product_id`（173） |
| `payload_amount` | `CASE WHEN payload.amount IS NULL OR payload.amount = '' THEN NULL ELSE CAST(payload.amount AS DECIMAL(18,2)) END AS payload_amount`（174-175） |
| `payload_total_amount` | `CASE WHEN payload.total_amount IS NULL OR payload.total_amount = '' THEN NULL ELSE CAST(payload.total_amount AS DECIMAL(18,2)) END AS payload_total_amount`（176-177） |
| `payload_status`(trade) | `payload.status AS payload_status`（178） |
| `payload_reason` | `payload.reason AS payload_reason`（179） |
| `payload_items` | `payload.items AS payload_items`（180） |
| `source_file` | `'landing' AS source_file, $batchId AS ingest_batch_id,`（**同一行**，103/131/153/181） |
| `ingest_batch_id` | 同上：`$batchId AS ingest_batch_id`（103/131/153/181） |
| `dt`（分区） | `REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt`（104/132/154/182） |
| `hour`（分区） | `SUBSTR(event_time, 12, 2) AS hour`（105/133/155/183） |

标注（均为事实）：
1. `payload` 由 `EventLandingSchema.structType`（L28-73）的**闭合嵌套 StructType**（L36-72）读取；**没有任何表达式产出 `payload` 原文本、`payload_json` 或 `payload_hash`**。schema 中 `payload.items` 是 `StringType`（L68）。schema 里存在但**无任何 SELECT 引用**的字段：`payload.available`/`payload.reserved`（L54-55）、`payload.change_type`（L56）、`payload.pay_amount`（L67）、`payload.paid_at`/`payload.completed_at`（L70-71）等。
2. `items` 形态：L205-212 `val itemsArrayType: ArrayType = ArrayType(StructType(Seq(product_id, product_name, quantity, unit_price, discount, amount)), containsNull = true)`；L214 `val itemsDecimal: DecimalType = DecimalType(18, 2)`。全 `spark-jobs` grep：**只有定义处 L205/L214 命中，零消费点**；`EventLandingSchema` 唯一消费者是 `EventOdsLoadJob.scala:37`（只取 `.structType`）。故 `items` 实际形态由 L68 的 `StringType` 决定，L180 原样写入 `payload_items`。
3. `source_system` 写入侧唯一来源 = 行内值（L96/117/146/167）；`--extra` 注入在写入侧不存在（`EventOdsLoadJob.scala` 只读 `--landingDir` L31 与 `--batchId` L32）。
4. `source_file` = 常量字面量 `'landing'`（L103/131/153/181）；`OdsLoadSql.scala:20` 注释自述「保留 source_file（'landing'）」。
5. landing 视图不携带文件身份：`LANDING_VIEW = "landing_valid"`（L25），由 `EventOdsLoadJob.scala:49` 注册自 L41-44 的 `filter` 结果，无来源文件列；全 `spark-jobs` grep `_metadata|input_file_name|file_name` **零命中**（无既有先例）。
6. SQL 内插值只有 `${ns.ods}`（库名）与 `$batchId`（批号）两个。
7. 视图与校验：`FROM $LANDING_VIEW`（106/134/156/184）+ `WHERE schema_version = '1.0'`（107/135/157/185）+ `PARTITION (dt, hour)` 空分区规格（94/115/144/165）。`rejectedSelect()` L192-199 输出 `event_id, event_type, schema_version, event_time, trace_id, 'BAD_VERSION_OR_KEY' AS reject_reason`（L194-195）。

---

## 3. 注入侧：`--extra` 现有实现原文

### 3.1 `JobCommandBuilder.java`（133 行）

```java
46|    public static List<String> build(RuntimeProfileSnapshot profile, String jobCode, String businessDate,
47|                                     long runtimeProfileId, int attemptNo,
48|                                     String inputVersion, String outputSnapshotId,
49|                                     Map<String, String> extraArgs, Map<String, String> confs) {
57|        // P1-04：数仓库名空间由唯一所有者解析。非法前缀在这里（spark-submit 之前）失败，
58|        // 本方法只被提交路径调用，因此“非法前缀绝不进入 Spark”是结构保证，不靠下游再校验。
59|        WarehouseNamespace namespace = WarehouseNamespace.ofNullable(profile.hiveDatabasePrefix());
80|        if (confs != null) { for (Map.Entry<String,String> e : confs.entrySet()) {
82|                cmd.add("--conf"); 83|cmd.add(e.getKey() + "=" + e.getValue()); } }
87|        cmd.add("--class");  88|cmd.add(MAIN_CLASS);                  // MAIN_CLASS 见 L28
90|        cmd.add(profile.sparkJobJarUri());
92|        cmd.add("--runtimeProfileId=" + runtimeProfileId);
93|        cmd.add("--jobCode=" + jobCode);
94|        cmd.add("--businessDate=" + businessDate);
95|        cmd.add("--attemptNo=" + attemptNo);
96|        // 解析后的前缀（缺省 dw）显式下发：作业侧 JobRunner 启动前复核，两侧同一份规格
97|        cmd.add("--" + WarehouseNamespace.ARG_KEY + "=" + namespace.prefix());
104|        if (extraArgs != null) {
105|            for (Map.Entry<String, String> e : extraArgs.entrySet()) {
106|                if (e.getValue() == null) { 107|continue; }
109|                cmd.add("--" + e.getKey() + "=" + e.getValue());
110|            }
111|        }
```
（L80-85、L104-111 为原样缩进；L87-95 逐行 `cmd.add`，此处按行号一一对应书写。L28 `MAIN_CLASS = "com.graduation.analytics.job.JobRunner"`。）

### 3.2 `WarehouseNamespace.scala`（128 行）与参数解析

```scala
 87|  /** Java 侧（`JobCommandBuilder`）与本侧共用的透传键名 */
 88|  final val ArgKey: String = "hiveDatabasePrefix"
126|  def fromArgs(args: JobArgs): WarehouseNamespace =
127|    of(args.extra.getOrElse(ArgKey, DefaultPrefix))
```
- `DefaultPrefix = "dw"` L68；`PrefixPattern = "^[a-z][a-z0-9_]{0,23}$"` L73；`validate` L96-103；`of` L113-116（非法 → `IllegalArgumentException("<错误码>: <原值>")` L115）。
- Java 侧键名所有者：`analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java:50` = `public static final String ARG_KEY = "hiveDatabasePrefix";`；`ofNullable` L149-151。
- 作业侧解析点：`EventOdsLoadJob.scala:33`、`LocalSchemaInitJob.scala:18`。
- `extra` 的构造（无白名单）：`spark-jobs/src/main/scala/com/graduation/analytics/job/JobArgs.scala:14`（`val extra: Map[String,String]`）、`:22-26`（逐条 `--key=value` `split("=", 2)` 入 Map）、`:49`（`extra = kv`）⇒ **任何** `--k=v` 都进 `extra`。

### 3.3 `source_registry.source_code` 的 Java 读取点

**在 spark-submit 命令构造路径与 ODS 装载路径上：不存在。**

- 取证：`grep`（glob `*.java`，root=仓库根，pattern `source_code|sourceCode|source_registry`）→ **146 命中**，全部落在 `connection-ingestion/src/main/java/.../ingestion/**`（清单 `sourceCode` 字段）、`connection-ingestion/src/test/**`（`SourceRegistryTestSupport.java:42,110,297`）、`platform-app/src/test/**`（`SourceRegistryMigrationMySqlIT.java:92,214,227,228`、`SourceRegistryMigrationScriptTest.java`）、`platform-common/src/main/java/.../PlatformBizException.java:34`（错误码注释 `SOURCE_CODE_IMMUTABLE`）。**无一条在 `warehouse-pipeline/src/main/**`**。
- 定向复核：`grep`（path `analytics-server/warehouse-pipeline/src/main`，glob `*.java`，pattern `source_registry|sourceCode|SourceRegistry|hive_database_prefix|hiveDatabasePrefix`）→ **唯一命中** `.../spark/JobCommandBuilder.java:59`（读 `profile.hiveDatabasePrefix()`，**非** `source_registry`）。
- 正向对照（证明该 grep 有检出能力）：同 pattern 命中 `connection-ingestion` 清单写出点与 `SourceRegistryMigrationMySqlIT.java:227-228`（`… FROM source_registry WHERE source_code = 'mock-mall'`）。
- `--extra` 内容所有者：`PipelineService.java:416-420`
  ```java
  416|            Map<String, String> odsExtra = new LinkedHashMap<>();
  417|            odsExtra.put("landingDir", acceptedDir == null ? "" : acceptedDir.toUri().toString());
  418|            if (manifest != null && manifest.get("batchId") != null) {
  419|                odsExtra.put("batchId", String.valueOf(manifest.get("batchId")));
  420|            }
  ```
  ⇒ LOAD_ODS 只下发 `landingDir` + `batchId`；`INIT_SCHEMA` 为 `Map.of()`（L407-408）；**无 `sourceSystem` 键**。
- 调用链：`PipelineService.java:614`（`extraArgs` 形参）→ `:624`（`executeStage(..., extraArgs, confs)`）→ `SparkStageExecutor.java:115-141` → `:147-148`（`JobCommandBuilder.build(profile, jobCode, businessDate, profile.id(), attemptNo, extraArgs, confs)`）。
- 全仓 `--sourceSystem` 零命中（与 `RULINGS.md:98` 自述一致）。

---

## 4. 会变红的测试与断言

| # | 文件:行号 | 断言原文（关键部分） | 触发条件 |
|---|---|---|---|
| T1 | `spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala:39` | `lower should include("'landing' as source_file")`（`lower` ← `OdsLoadSql.userFromLanding(ns, 8L)`，L32） | 消灭常量 `'landing'` ⇒ **必红** |
| T2 | `spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala:107` | `behaviorOds should include("payload.user_id AS payload_user_id")`（`behaviorOds` ← L106） | 把 `payload_*` 改为从 `payload_json` 派生 ⇒ 红；纯加列不变 |
| T3 | `IdCodecSpec.scala:78` | `assertNoDirectCast("OdsLoadSql.behaviorFromLanding", OdsLoadSql.behaviorFromLanding(ns, 7L))`；判定正则 L52：`(?i)cast\s*\(\s*[a-z_]*\.?payload_(user\|product\|order\|category\|parent_category\|brand)_id\s+as\s+bigint` | 新表达式命中该正则 ⇒ 红（`get_json_object(payload_json,'$.user_id') AS payload_user_id` 不命中） |
| T4 | `IdCodecSpec.scala:108` | `behaviorOds.toLowerCase should not include "as bigint"` | ODS 模板出现 `AS BIGINT`（大小写不敏感）⇒ 红 |
| T5 | `SqlTemplateSpec.scala:22-28` | `include("schema_version = '1.0'")`、`include("event_type = 'behavior'")`、`include("landing_valid")`、`include("partition (dt, hour)")`、`include("regexp_replace(substr(event_time, 1, 10), '-', '')")`、`include("substr(event_time, 12, 2)")`、`include("7 as ingest_batch_id")` | 改 `event_time` 类型/表达式或 `dt/hour` 派生式 ⇒ 红（D-053 保持 `STRING` ⇒ 不应红） |
| T6 | `SqlTemplateSpec.scala:59-60` | `include("payload_items")`、`include("payload_refund_id")` | 删/改 v1 `payload_*` 列 ⇒ 红（D-054 双写 ⇒ 不应删） |
| T7 | `SqlTemplateSpec.scala:34-38,48-49` | `include("event_type in ('user_registered')")`、`payload_user_id`/`payload_age_group`/`payload_member_level`/`payload_register_time`、`payload_price`、`cast(payload.price as decimal(18,2))` | 改写 v1 派生列表达式 ⇒ 红 |
| T8 | `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/contracts/EventContractTest.java:133-148` | `Pattern.compile("\"([a-z_]+)\"\\s*->\\s*\"(ods_[a-z_]+)\"")`；`assertThat(scalaMap)…hasSize(12)`；`…isEqualTo(EventContract.ODS_TABLE_BY_TYPE)`（Scala 文件缺失时 L135-138 硬失败） | `OdsLoadSql.scala:76-89` 的字面映射数量/取值变化 ⇒ 红；纯加列不受影响 |
| T9 | `analytics-server/platform-common/src/test/java/com/graduation/analytics/contracts/CanonicalEventSchemaParityTest.java:65-73` | `assertEquals(EventContract.SCHEMA_VERSION, root…path("schema_version").path("const").asText(), "schema_version 必须锁定为 EventContract.SCHEMA_VERSION")`；`assertEquals(EventContract.SOURCE_SYSTEM, root…path("source_system").path("const").asText(), "source_system 必须锁定为 EventContract.SOURCE_SYSTEM")`；常量见 `EventContract.java:17 SOURCE_SYSTEM = "mock-mall"` | **CT-1** 落地（去掉 `const "mock-mall"`）时必须同批改，否则红 |
| T10 | `analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNamespaceContractTest.java:165` | `assertThat(ns.table("ods", "ods_user_event")).isEqualTo("dw_ods.ods_user_event")` | 改动库名拼接规则 ⇒ 红（加列不影响） |
| T11 | `analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java:127,144,155-158` | SQL 文件 `hasSize(5)`；`.contains("${WAREHOUSE_PREFIX}_")`；`layers` `containsExactlyInAnyOrder("ods","dwd","dim","dws","ads")`；`variables` `containsExactly("WAREHOUSE_PREFIX")` | 新增 `warehouse/ddl/*.sql` 或引入第二个 `${…}` 变量 ⇒ 红 |
| T12 | 同上 `:93-105` | `scanned` `hasSizeGreaterThan(60)`；`offenders` `isEmpty()`，判定用 `BARE_LITERAL = Pattern.compile("dw_(?:ods\|dwd\|dim\|dws\|ads)\\b")`（L45）、`DYNAMIC_PREFIX = Pattern.compile("dw_\\$\|\"dw_\"\\s*\\+")`（L47）；`SCOPES = List.of("spark-jobs/src/main/", "warehouse/ddl/", "scripts/")`（L50） | 新代码里写裸库名 `dw_ods`/`dw_$layer` ⇒ 红 |
| T13 | `spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala:156-168` | `statements.size should be(37)`（L159）；`statements.map(_._1).toSet should be(allowed)`（L161） | **不变红**（4 表加列仍 37 条）——登记为刻意保持的边界 |
| T14 | `WarehouseNamespaceSpec.scala:224-227` + `:125-154` | 4 条 ODS producer（`userFromLanding(ns, 8L)` 等）入 `Producers.all`（`size >= 25`，L128），断言换前缀后无 `dw_*` 残留 | 模板签名变化 ⇒ **编译失败**（先于断言红） |
| T15 | `spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala:22` + `SqlTemplateSpec.scala:98` | DWD 侧 `FROM_UTC_TIMESTAMP(FROM_UNIXTIME(UNIX_TIMESTAMP(rn.event_time)), 'Asia/Shanghai')` 依赖 `event_time` 为字符串 | `event_time` 改类型 ⇒ 红（D-053 保持 `STRING`） |
| T16 | `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/spark/JobCommandBuilderTest.java:113-120,125-134` | `extraArgsArePassedThrough`（L113-120）；`assertThat(legacy).contains("--hiveDatabasePrefix=dw")`（L128）、`contains("--hiveDatabasePrefix=dw_b")`（L133）、`doesNotContain("--hiveDatabasePrefix=dw")`（L134） | 新增 `--sourceSystem=…` 走 extra ⇒ **不破坏**（`contains` 非 `containsExactly`）；改 `hiveDatabasePrefix` 注入点/键名 ⇒ 红 |

同批需同步（非断言红）：`EventContract.java:44-47`（4 个 `ODS_*_EVENT` 常量）与 `:53`（`ODS_TABLE_BY_TYPE`）；模板消费点 `EventOdsLoadJob.scala:62-65` 4 处。

---

## 5. 命令与本地能力（取证）

### 5.1 `spark-jobs` 的 E1/E2 真实命令 + 出处

| 层 | 命令原文 | 出处（文件:行号） |
|---|---|---|
| E1 打包跳过测试 | `mvn -o -f spark-jobs/pom.xml package -DskipTests` | `docs/acceptance/p1-04-namespace-20260911/README.md:65`（复现段 `:95`） |
| E1+E2 一条命令 | `mvn -f spark-jobs/pom.xml package` | `docs/deployment.md:127`（注释「Scala 作业（不启动 Spark）」）、`docs/acceptance-checklist.md:54`（`46/46`）、`docs/remediation-status.md:205,223,267` |
| E2 仅测试 | `mvn -o -f spark-jobs/pom.xml test-compile scalatest:test` | `docs/acceptance/p1-04-namespace-20260911/README.md:69`（`Tests 62 / 0 失败`）、同文件 `:112` |
| E2 离线 package | `mvn -o -f spark-jobs/pom.xml package` | `docs/开发过程事实与决策记录.md:252,589,641,666` |
| 子系统探针 | `mvn -o test -f spark-jobs\pom.xml` | `docs/acceptance/p2-01-ods-v2-spec-draft-20260912/draft/p2-01-spec-draft.md:403` |
| `scripts/` 专用脚本 | **不存在**（`scripts/*.ps1` grep `spark-jobs\|mvn` 仅命中：`accept-p1-baseline.ps1:235` 登记 jar 路径、`run-spark-chain.ps1:8,12` 检查 jar 存在并提示 `cd spark-jobs && mvn package`、`smoke-pipeline.ps1:5` 前置条件注释、`build-web-and-package.ps1:66,89` 针对 web/platform-app） | 同左 |

`spark-jobs/pom.xml` 决定「E2 能否只跑测试」的插件边界：`:59-60`（`sourceDirectory=src/main/scala`、`testSourceDirectory=src/test/scala`）、`:62-81`（`scala-maven-plugin` 4.8.1，goals compile+testCompile）、`:82-99`（`scalatest-maven-plugin` 2.2.0，`<goal>test</goal>` 绑定默认生命周期 ⇒ `package` 会跑测试）、`:100-111`（`maven-jar-plugin` 3.4.1，`mainClass=com.graduation.analytics.job.JobRunner`）；Scala 2.12.19（L15）、Spark 3.5.1 `provided`（L16/L29/L35）、Hadoop 3.3.4（L17）。

本机工具链（只读实测）：`(Get-Command mvn).Source` = `D:\apache-maven-3.9.14\bin\mvn.cmd`；`mvn -v` = `Apache Maven 3.9.14 (996c630dbc656c76214ce58821dcc58be960875b)` / `Maven home: D:\apache-maven-3.9.14` / `Java version: 17.0.12, runtime: D:\Develop\JAVA17`；`$env:SPARK_HOME` 为空。**本轮未执行任何 Maven 命令**，上表命令为文档登记口径、未复跑。

### 5.2 本机 Spark / Hive 事实（只读实测）

| 事实 | 值 |
|---|---|
| Spark 发行版 | `D:\Develop\spark-3.5.1-bin-hadoop3`（`RELEASE`：`Spark 3.5.1 (git revision fd86f85e181) built for Hadoop 3.3.4`，`Build flags: … -Pscala-2.12 -Phadoop-3 -Phive -Phive-thriftserver`）；`scripts/run-spark-chain.ps1:11` 默认同一路径 |
| Hive 形态 | 无 HiveServer2；embedded Derby metastore（`spark.sql.hive.metastore.jars=builtin` + JDO `jdbc:derby:…;create=true`，见 `SparkStageExecutorSmokeTest.java:74-82`）；`docs/acceptance/p1-04-namespace-20260911/README.md:79` 登记「本机无 HiveServer2」 |
| 平台默认数仓绝对路径 | `D:\Develop_code\GraduationProject\spark-warehouse`（`application.yml:38` `warehouse-dir: ${PLATFORM_SPARK_WAREHOUSE_DIR:./spark-warehouse}` + `PlatformBeans.java:72`，平台 CWD=仓根） |
| 库目录 | **6**：`dw_ads.db`、`dw_dim.db`、`dw_dwd.db`、`dw_dws.db`、`dw_ods.db`、`probe_r613.db` |
| 各库表目录数 | ads=16、dim=2、dwd=3、dws=7、ods=4、probe_r613=2 |
| `dw_ods.db` parquet 文件数 | `ods_behavior_event`=299、`ods_product_event`=241、`ods_trade_event`=245、`ods_user_event`=81 |
| 全仓 parquet 总数 | **972** |
| 隔离数仓（脚本用） | `D:\Develop\tmp\spark-warehouse` 存在（`dw_ads/dw_dim/dw_dwd/dw_dws/dw_ods` 5 个 `.db`）；`scripts/run-spark-chain.ps1:7` 默认 `-Warehouse 'D:\Develop\tmp\spark-warehouse'` |
| Derby 元数据库 | `D:\Develop_code\GraduationProject\derby-metastore` 存在（`metastore_db`、`seg0`、`db.lck`、`service.properties`）；`application.yml:39` `metastore-dir: ${PLATFORM_SPARK_METASTORE_DIR:./derby-metastore}` |
| 当前作业 jar | `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`，**234038 B**，`2026/9/11 18:19:03`（与 `p1-04-namespace-20260911/README.md:65` 登记的 234,038 B / 18:19:03 逐项一致；本轮零构建，未改变） |
| 黄金数据集 | `tests/golden-dataset/events/golden-20260901.jsonl`；消费点 `SparkStageExecutorSmokeTest.java:111`（`landingDir=file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events`），断言 `inputRecords()==55`（L120） |

---

## 6. 契约冲突面（`contract-specs/`）

### 6.1 `schemas/canonical-event.v1.schema.json`（867 行）

| 项 | 行号 | 要点 |
|---|---|---|
| `source_system` 常量 | 38-40 | `"source_system": { "const": "mock-mall", "description": "固定值：mock-mall。…与 EventContract.SOURCE_SYSTEM（EventContract.java:17）及 mall-simulator 侧一致，并由 CanonicalEventSchemaParityTest 锁定为 const。…" }` |
| `schema_version` 常量 | 42-44 | `"schema_version": { "const": "1.0", "description": "契约版本，当前唯一支持值 1.0。…未知版本 → 进入隔离区（quarantine_record），不进入 DWD。升版本规则：新增字段 → 1.1 起；破坏性变更 → 新主版本 + 转换器（event-contract.md L4）。" }` |
| `event_id` | 8-11 | `"event_id": { "type": "string", …}`，描述内自述 UUID 与真实夹具 `golden-evt-001` 冲突，故只约束 string |
| 根对象宽松（未获授权项） | 5 | 描述段含 `additionalProperties: true` 依据，声明「此项未获契约明确授权」；并自述用本 Schema 校验真实夹具 `landing/events/r9-m1-123006.jsonl` 的 55 行「26 行通过、29 行不通过…采集层实际接受的 51 行中约有 25 行按 event-contract.md §2 是脏数据」 |

人读权威：`docs/contracts/event-contract.md:16`（`"source_system": "mock-mall", // 固定值：mock-mall`）、`:17`（`"schema_version": "1.0"`）、`:12`（`"event_id": "UUID", // 全局唯一，ODS/DWD 按此去重`）、`:165`（重复投递由 DWD 按 `event_id` 去重）、`:4`（升版规则）、`:1`（标题 schema_version 1.0）。

### 6.2 `specs/warehouse-namespace.v1.json`（80 行，已冻结）

- L4 `"status": "FROZEN-2026-09-11"`；L34 `"observedDatabases": ["dw_ods","dw_dwd","dw_dim","dw_dws","dw_ads"]`。
- L30 `"sourceOfTruth": "运行期取值来自 runtime_profile.hive_database_prefix（NULL/空串 → defaultPrefix）；后续由 source_registry 的源级配置接管（P2）"`。
- L75-79：`"javaTest": "com.graduation.analytics.warehouse.WarehouseNamespaceContractTest"`、`"scalaTest": "com.graduation.analytics.WarehouseNamespaceSpec"`、`"requirement": "两侧测试都必须加载本文件并逐向量断言，同时断言本方常量与 rule 段逐字一致；任一实现漂移即失败。"`

### 6.3 `contract-specs/README.md`（187 行，sha256 `07D2F02E31CEFB28665B32C00B7D7E554621D4B4991EB64C617601B7455E33BB`）

| 项 | 行号 | 实况 |
|---|---|---|
| 目录级版本串 | 3 | 仍为 **`1.2.0`**（叙述 `1.1.0 → 1.2.0` 对应 ingestion-manifest 的 P1-05 扩展） |
| 制品表版本 | 56 | `| [`VERSION`](VERSION) | 总控 | — | `1.2.0` |` |
| 已升 1.3.0 的叙述 | 137 | 表头 `| # | 对象 | 同步前（`VERSION` 1.2.0） | 同步后（`VERSION` 1.3.0） | 依据 |` |
| `VERSION` 指纹登记 | 183 | 登记旧值 `C9E89F9DC5A13DD44A5F75BE0F69F7239723875F4685B11E93AAB09B6DDBC4A0`，内容 `contract-specs 1.2.0` |
| 自检段 | 187 | 「…`VERSION` `C9E89F9D…`（内容 `contract-specs 1.2.0`，21 B）✓…」 |
| DRAFT 声明 | 3、51 | L3「其余四个制品仍为 `DRAFT`（`canonical-event.v1` 受 B-06/Q6 未决阻塞）」；L51 `canonical-event.v1.schema.json` 状态列 `DRAFT` |
| 未冻结不登记指纹 | 180 | `| schemas/canonical-event.v1.schema.json | 未冻结，见 §7 | B-06/Q6 未决… |` |
| 权威顺序 | 21-27 | ①`docs/contracts/*.md` ②本目录为机器可读投影 ③跨程序字段变化须先由总控登记契约任务 ④来源指纹表 |

**实测冲突**：`RULINGS.md:148` 声明 CT-0/F-31「**已随本轮修复**（勘误级，不升版；补记 + 哈希重登记）」，但磁盘上 `README.md:3,56,183,187` 仍为 `1.2.0` / 旧指纹 `C9E89F9D…`（未修复）。复核命令与结果：`git status --porcelain -- contract-specs` → 无输出；`git log --oneline -3 -- contract-specs` → `fee9dbc` / `f79889e` / `e3d2626`（README 以当前内容被提交）。

### 6.4 `contract-specs/VERSION`（实测）

```
Get-Content contract-specs/VERSION   → contract-specs 1.3.0
(Get-Item …\VERSION).Length          → 21
[System.IO.File]::ReadAllBytes(…)    → 99,111,110,116,114,97,99,116,45,115,112,101,99,115,32,49,46,51,46,48,10
                                        （= "contract-specs 1.3.0\n"，LF 结尾，无 BOM，21 B）
(Get-FileHash …\VERSION -Algorithm SHA256).Hash
  → B6BAB8E0547C6BC0EB7005128E177B891E32534FA3522D54B079E7AEC384EC89
```
⇒ `VERSION` 已升 `1.3.0`，而 README 的版本串与指纹登记表未同步（§6.3）。

### 6.5 与本改动相关的契约任务（`RULINGS.md` 原文）

| 编号 | 行号 | 关系 |
|---|---|---|
| CT-1 | 144 | `canonical-event.v1` 去掉 `source_system` 的 `const "mock-mall"` 并同步平台侧结构对账测试 ⇒ **T9 必须同批改** |
| CT-2 | 145 | 去重语义文字化（namespace 内单键 + 混源守卫） |
| CT-3 | 146 | `items` 契约（数组）与真实（字符串）不一致的处置 |
| CT-4 | 147 | `canonical-event.v1` 冻结前置（B-06/Q6、Q12/Q16 未决） |
| CT-0/F-31 | 148 | README 版本串滞后；**磁盘实况未修复**（§6.3） |
| 批次规则 | 150-151 | CT-1/CT-2/CT-3 与字段级改动一并落 `VERSION 1.3.0 → 1.4.0`，带日期补记、逐条「命中且仅命中 1 次」断言、前后 SHA-256 对照 |

本轮实测的其他文件哈希：
```
warehouse/ddl/00-ods.sql                        36EDBF7929D9AA6441F898A6D5CF3346C99E7BB09E13F0F2914907BFA1161693
spark-jobs/.../sql/OdsLoadSql.scala             061A0CF65A85140FAB325F403B40A656E06C9B0E2B4996C51AA82C9D680C2DD8
contract-specs/README.md                        07D2F02E31CEFB28665B32C00B7D7E554621D4B4991EB64C617601B7455E33BB
contract-specs/VERSION                          B6BAB8E0547C6BC0EB7005128E177B891E32534FA3522D54B079E7AEC384EC89
```

---

## 7. 未取证清单

1. **`input_file_name()` 在本项目执行模式是否可用**——未核实。全 `spark-jobs` grep `input_file_name|_metadata|file_name` 零命中，无既有用例；`RULINGS.md:171` 亦自述未实测。
2. **`payload_json`/`payload_hash` 的字节保真（`to_json`/`get_json_object`/`from_json` 往返逐字节一致）**——未核实，需真实 Spark 会话（本轮禁跑）。
3. **`warehouse/ddl/00-ods.sql` 改动后的 Hive 侧行为（`beeline --hivevar` 替换、EXTERNAL 表加列）**——未核实，本机无 HiveServer2/beeline（`docs/acceptance/p1-04-namespace-20260911/README.md:79` 登记同一限制）。
4. **存量 `spark-warehouse/dw_ods.db` 四表的真实（Parquet/Derby 元数据）列清单**——未核实，需跑 Spark 或读 Parquet footer（工具链未授权）；已有运行期旁证仅覆盖 `ods_trade_event`（`.verify/r8-items-dwd-verify.log:39`），其余三表仓内无命中。
5. **`spark.sql.sources.partitionOverwriteMode` 实际取值与空分区规格 `INSERT OVERWRITE … PARTITION (dt, hour)` 的破坏性后果（F-14/R2）**——未核实，破坏性验证需实跑并改写 ODS 分区；`p2-01-spec-draft.md:457` 自述「仅代码级取证，破坏性未验证」。
6. **运行中平台（8091，profile id=1）的 `sparkJobJarUri` 与 `hive_database_prefix` 当前值**——未核实，需查 MySQL/HTTP；仅有 `p2-01-spec-draft.md:604` 的既有记录（profile id=1 指向 `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`），未独立复核。
7. **`source_registry.source_code` 的实际取值行与其表结构**——未核实，需查 MySQL；只核到测试字面量（`SourceRegistryMigrationMySqlIT.java:227-228` 的 `WHERE source_code = 'mock-mall'`）与期望列类型（同文件 `:92`：`varchar(64)`）。
8. **`synthetic-data-generator` 的 9 个改动文件是否影响本轮取证**——未核实（未逐个读）；`git status --porcelain` 显示 9 个 `M` 条目（`.verify/acceptance/p1-05-8091-swap-20260911/8091-stdout.log` + 8 个 generator 源/测试），与 ODS 写入/注入/契约三面无引用关系（定向 grep 未命中）。
9. **`docs/acceptance/p2-01-ods-v2-spec-draft-20260912/draft/p2-01-spec-draft.md`（605 行）逐段复核**——未核实，本泳道只按任务取实现级事实。

---

## 附：本轮取证命令（全部只读）

```powershell
git rev-parse --abbrev-ref HEAD ; git status --porcelain
git status --porcelain -- contract-specs ; git log --oneline -3 -- contract-specs
Get-FileHash <各文件> -Algorithm SHA256 ; [System.IO.File]::ReadAllBytes('contract-specs/VERSION') -join ','
Get-ChildItem D:\Develop -Directory ; Get-Content 'D:\Develop\spark-3.5.1-bin-hadoop3\RELEASE' -TotalCount 3
mvn -v ; java -version ; (Get-Command mvn).Source ; $env:SPARK_HOME
Get-ChildItem 'D:\Develop_code\GraduationProject\spark-warehouse' [-Recurse -File -Filter '*.parquet']
Get-ChildItem 'D:\Develop_code\GraduationProject\spark-jobs\target' -Filter '*.jar'
Get-ChildItem 'D:\Develop_code\GraduationProject\tests\golden-dataset\events'
# 内容检索一律用 grep 工具（ripgrep），未用 shell grep/rg
```
未执行（遵守约束）：`mvn`、`spark-submit`、`spark-shell`、8090/8091/8092 启停、任何数据库读写、任何 git 写操作、任何删除/移动/重命名。
