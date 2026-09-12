# P2-07 E2 附证：`SparkStageExecutorSmokeTest` 失败根因定位（跨泳道，非本泳道改动）

- 采集时间：2026-09-12（本轮）
- HEAD：`287b82c`（分支 `remediation/r1-boundary`）
- 结论：**失败根因在 `spark-jobs/**` 的另一条泳道当日未提交改动，不在 `analytics-server/**` 本泳道改动内。**
  本泳道按铁律不得改 `spark-jobs/**`，也不得重建在产 jar，故该用例本轮**无法**转绿。

## 1) 失败原文（逐字摘自 `raw/e2-mvn-test-20260912.log`）

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 17.78 s <<< FAILURE! -- in com.graduation.analytics.pipeline.spark.SparkStageExecutorSmokeTest
[ERROR] com.graduation.analytics.pipeline.spark.SparkStageExecutorSmokeTest.realSparkOdlLoadsGoldenDataset -- Time elapsed: 17.78 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
[sci 应 SUCCESS:
[PARSE_SYNTAX_ERROR] Syntax error at or near 'USING'.(line 22, pos 8)

== SQL ==

        CREATE TABLE IF NOT EXISTS dw_ods.ods_user_event (
          event_id STRING,
          ...
          payload_hash STRING) COMMENT '用户事件原始表'
        USING parquet PARTITIONED BY (dt STRING, hour STRING)
--------^^^
]
Expecting value to be true but was false
	at com.graduation.analytics.pipeline.spark.SparkStageExecutorSmokeTest.realSparkOdlLoadsGoldenDataset(SparkStageExecutorSmokeTest.java:101)
```

失败位置：`sci` 阶段（建表）。**注意库名前缀已是 `dw_ods`** —— 即本泳道注入的
`--hiveDatabasePrefix=dw` 已正确生效，错误发生在 DDL 子句顺序，与前缀/`--sourceSystem` 无关。

## 2) 生成该 DDL 的代码属另一泳道当日未提交改动

`git status --porcelain -- spark-jobs`（本泳道零改动 `spark-jobs/**`）：

```
 M spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala
?? spark-jobs/src/main/scala/com/graduation/analytics/sql/JsonObjectSlicer.scala
?? spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2ByteFidelitySpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2EdgeCaseSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SqlContractSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/sql/
```

`git diff -U1` 关键 hunk（`+` = 工作树新增、HEAD 无此代码）：

```
@@ -248,2 +213,19 @@ object LocalSchemaInitJob {
+  def odsCreateTable(ns: WarehouseNamespace, table: String): String = {
+    // 注意：`COMMENT` 必须紧跟在列体的 `)` 之后、`USING` 之前，中间不能有空行
+    // （实测：`)` 与 `COMMENT` 之间出现空行会 PARSE_SYNTAX_ERROR at or near 'USING'）
+    val comment = OdsV2Columns.TableComments.get(table).map(c => s" COMMENT '$c'").getOrElse("")
+          ${OdsV2Columns.columnsClause(table)})$comment
+        USING parquet PARTITIONED BY ($partitions)"""
```

HEAD（未改动）里对应的 4 条 ODS 建表语句是内联字面量，且**没有** `$comment` 插在 `)` 与 `USING` 之间：

```
@@ -42,42 +43,6 @@ object LocalSchemaInitJob {
-        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),
-        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),
-        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),
-        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),
+    (ns.ods, odsCreateTable(ns, "ods_user_event")),
+    (ns.ods, odsCreateTable(ns, "ods_product_event")),
+    (ns.ods, odsCreateTable(ns, "ods_behavior_event")),
+    (ns.ods, odsCreateTable(ns, "ods_trade_event")),
```

即：`COMMENT '表注释'` 被插到 `)` 之后、`USING parquet` **之前**，正是失败 SQL 的形态。
工作树第 222-223 行的注释把病因写成「`)` 与 `COMMENT` 之间有空行」，但实测失败文本里
`)` 与 `COMMENT` **紧邻且无空行**——真正被 Spark 解析器拒绝的是 `COMMENT` 出现在 `USING` 之前。

## 3) 共享 jar 指纹（smoke test 实际 spark-submit 的目标）

```
path   = D:\Develop_code\GraduationProject\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar
length = 283971
builtAt= 2026-09-12 13:34:35
sha256 = 463AF1D36882F911012FEF467A121E5B8F104FA83C90DF9BBECBE23DD7AB5E88
```

（P1-04/P1-05 轮次记录的 jar 为 234038 字节；今日 13:34 被另一泳道重建 ⇒ 上表的
`odsCreateTable` 改动已进入该 jar，故 `sci` 阶段在建表处即失败。）

## 4) 对总控的反馈（本泳道不修）

1. `LocalSchemaInitJob.odsCreateTable`（未提交）生成的 DDL 在 Spark 3.5 上必然失败；
   该泳道的 `SqlTemplateSpec`/`OdsV2*Spec` 是字符串级断言，未覆盖"这段 SQL 真能被 Spark 解析"。
2. 由此 **P2-07 的真实链隔离探针（D-075 出口证据②）中 `sci` 建表必然失败**，
   与 P2-07 改动无关；本泳道已在探针里把 `sci` 结果登记为"观测"而非断言，并按
   "未取证"口径报告（见 `IMPL-REPORT.md`）。
3. 该泳道把 `COMMENT` 放在 `USING` 之前属**当日新增回归**，修好并重建 jar 后，
   `SparkStageExecutorSmokeTest` 与本泳道的真实链探针才具备复跑条件。
