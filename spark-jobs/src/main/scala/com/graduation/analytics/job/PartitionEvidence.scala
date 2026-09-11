package com.graduation.analytics.job

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.TableIdentifier

/**
 * R6-12（V2.0 §15.3）：输出分区证据采集。
 *
 * 指导书要求 spark_job_run 必须能回答"这次作业究竟写了哪些表、哪些 dt 分区、多少行、落在哪个路径"，
 * 且**计数只能来自真实执行结果**（禁止用输入数或常量冒充输出数）。
 * 本对象在作业写出完成后向 Hive 元数据 + 文件系统求证：
 *   1. `SessionCatalog` 元数据判断分区列（区分非分区表）；
 *   2. `SHOW PARTITIONS` 枚举本次执行后存续的分区（dt 过滤由调用方给出业务日）；
 *   3. 每个分区 `SELECT COUNT(*)` 取真实行数（走 Spark 作业，不读文件大小估算）；
 *   4. `DESCRIBE FORMATTED ... PARTITION (...)` 取分区物理 Location。
 *
 * 采集失败（表不存在、元数据不可读）**直接抛出**：证据是作业契约的一部分，
 * 缺证据的"成功"等于假成功，必须让作业判 FAILED（§16.4 质量检查失败不得 catch 后继续）。
 */
object PartitionEvidence {

  /**
   * 采集一组目标表的分区证据。
   *
   * @param tables     库限定表名（形如 `ns.table("ads", "ads_operation_overview")`，库名由 WarehouseNamespace 派生）
   * @param snapshotId 本次快照号（§14.4 分区幂等协议的一部分）
   * @param dtEquals   只采集该 dt 的分区（None = 该表全部存续分区，用于数据驱动分区的作业）
   */
  def collect(spark: SparkSession, tables: Seq[String], snapshotId: Option[String],
              dtEquals: Option[String] = None): Seq[OutputPartition] =
    tables.flatMap(t => collectTable(spark, t, snapshotId, dtEquals)).sortBy(p => (p.table, p.dt))

  private def collectTable(spark: SparkSession, table: String, snapshotId: Option[String],
                           dtEquals: Option[String]): Seq[OutputPartition] = {
    val partitionCols = spark.sessionState.catalog
      .getTableMetadata(TableIdentifier(tblOf(table), Some(dbOf(table))))
      .partitionSchema.fieldNames.toSeq

    if (partitionCols.isEmpty) {
      // 非分区表：整表一条证据（dt 留空，表示无分区维度）
      return Seq(OutputPartition(table, "", snapshotId,
        countWhere(spark, table, None), location(spark, s"DESCRIBE FORMATTED $table")))
    }

    val specs = spark.sql(s"SHOW PARTITIONS $table").collect()
      .map(_.getString(0)).toSeq.sorted
      .filter(spec => dtEquals.forall(dt => specValue(spec, "dt").contains(dt)))

    specs.map { spec =>
      OutputPartition(
        table = table,
        dt = specValue(spec, "dt").getOrElse(""),
        // R6-13：分区列本身含 snapshot_id 时以**分区规格实测值**为准（不沿用入参，
        // 否则同一 dt 下的多个快照分区会被错误标成同一快照号）
        snapshotId = specValue(spec, "snapshot_id").orElse(snapshotId),
        rowCount = countWhere(spark, table, Some(toPredicate(spec))),
        // DESCRIBE FORMATTED ... PARTITION 用逗号分隔分区规格（多列分区不能用 AND）
        path = location(spark, s"DESCRIBE FORMATTED $table PARTITION (${toSpec(spec)})"))
    }
  }

  /** `dt=20260901/hour=09` → `dt = '20260901', hour = '09'`（DESCRIBE PARTITION 语法） */
  private def toSpec(spec: String): String =
    spec.split("/").filter(_.nonEmpty).map { kv =>
      val idx = kv.indexOf('=')
      s"${kv.substring(0, idx)} = '${kv.substring(idx + 1)}'"
    }.mkString(", ")

  /** `dt=20260901/hour=09` → `dt = '20260901' AND hour = '09'`（WHERE 谓词语法） */
  private def toPredicate(spec: String): String =
    spec.split("/").filter(_.nonEmpty).map { kv =>
      val idx = kv.indexOf('=')
      s"${kv.substring(0, idx)} = '${kv.substring(idx + 1)}'"
    }.mkString(" AND ")

  private def specValue(spec: String, col: String): Option[String] =
    spec.split("/").filter(_.nonEmpty).map { kv =>
      val idx = kv.indexOf('=')
      kv.substring(0, idx) -> kv.substring(idx + 1)
    }.toMap.get(col)

  private def countWhere(spark: SparkSession, table: String, predicate: Option[String]): Long = {
    val where = predicate.map(p => s" WHERE $p").getOrElse("")
    spark.sql(s"SELECT COUNT(*) FROM $table$where").collect()(0).getLong(0)
  }

  /** 从 DESCRIBE FORMATTED 结果取 Location（分区描述里第一处即为分区物理路径） */
  private def location(spark: SparkSession, describeSql: String): Option[String] =
    spark.sql(describeSql).collect()
      .find(r => Option(r.get(0)).exists(_.toString.trim == "Location"))
      .flatMap(r => Option(r.get(1)).map(_.toString.trim))
      .filter(_.nonEmpty)

  private def dbOf(table: String): String = {
    val i = table.indexOf('.')
    if (i < 0) throw new IllegalArgumentException(s"表名必须库限定（db.table）: $table")
    table.substring(0, i)
  }

  private def tblOf(table: String): String = {
    val i = table.indexOf('.')
    if (i < 0) throw new IllegalArgumentException(s"表名必须库限定（db.table）: $table")
    table.substring(i + 1)
  }
}
