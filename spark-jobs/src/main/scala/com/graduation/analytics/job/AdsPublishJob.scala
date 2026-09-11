package com.graduation.analytics.job

import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession

import scala.collection.mutable.ListBuffer

/**
 * R6-13 正式分区发布（V2.0 §14.4 分区幂等协议，code=pub）：
 * 质量门（dqc）通过后，把 8 张 ADS 的**正式分区**用 **Hive 元数据指针**指向本次快照的暂存路径：
 *   ALTER TABLE {ns.ads}.ads_X ADD IF NOT EXISTS PARTITION (dt='D') LOCATION '<staging>';
 *   ALTER TABLE {ns.ads}.ads_X PARTITION (dt='D') SET LOCATION '<staging>';
 *
 * 选型说明（在 docs/remediation-status.md 登记）：指导书首选项为"元数据分区交换"，并明确远程对象存储
 * 用"版本路径 + 元数据指针"。本地 file:// 实验环境实测（.verify/r6-13-probe*.log）：
 * ①指针切换后正式分区读取的是暂存数据，遗留旧目录不会被合并（COUNT 不翻倍）；
 * ②重复 SET LOCATION 幂等，同 snapshotId 重试不产生第二份数据；
 * ③切换前的正式分区元数据保持不变 —— 质量未过/发布失败时旧正式分区照旧可读。
 * 相较"受控 rename"没有"移走旧目录→移入新目录"之间的不可读窗口，故采用指针方案。
 *
 * 发布后立即用真实 COUNT(*) 校验正式分区行数 = 暂存分区行数；不一致即 FAILED（不冒充发布成功）。
 * 清理：发布成功后删除**既非本次快照、又未被任何正式分区指针引用**的历史暂存分区（含物理目录），
 * 保留期策略 = 只保留被引用的最新快照（§14.4 第 6 步"临时分区按保留期清理"）。
 */
class AdsPublishJob extends WarehouseJob {
  override val code: String = "pub"
  override val description: String = "ADS 正式分区发布（元数据指针 + 行数校验 + 暂存清理）"

  override def validate(args: JobArgs): Either[String, Unit] =
    args.outputSnapshotId.filter(_.nonEmpty)
      .toRight("pub 需要 --outputSnapshotId（只发布本次通过质量门的快照）")
      .map(_ => ())

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val ns = WarehouseNamespace.fromArgs(args)
    val sid = args.outputSnapshotId.get
    val prune = args.extra.get("pruneStaging").forall(_ != "false")
    val checks = ListBuffer.empty[QualityCheck]

    val tables = AdsSql.TABLES
    val stagingTables = tables.map(AdsSql.staging(ns, _))
    val formalTables = tables.map(AdsSql.formal(ns, _))
    val stagingParts = PartitionEvidence.collect(spark, stagingTables, Some(sid), Some(dt))
      .filter(_.snapshotId.contains(sid))
    val stgOf = stagingParts.map(p => p.table -> p).toMap

    // 规则 1（发布前预检）：暂存分区必须全部就绪且已知物理路径，否则不切换任何分区
    val notReady = tables.filter(t => stgOf.get(AdsSql.staging(ns, t)).forall(p => p.rowCount <= 0 || p.path.isEmpty))
    checks += QualityCheck("PUB_STAGING_READY", "PUBLISH", stagingTables.mkString(","),
      tables.size, notReady.size, "8 张暂存分区就绪", "BLOCKING", notReady.isEmpty,
      if (notReady.isEmpty) s"8 张暂存分区就绪，合计 ${stagingParts.map(_.rowCount).sum} 行"
      else s"暂存分区缺失/为空/无路径: ${notReady.mkString(",")}")
    if (notReady.nonEmpty) {
      return JobResult.failed(code, args.attemptNo,
        s"发布前预检失败（未切换任何正式分区）: ${notReady.mkString(",")}",
        System.currentTimeMillis() - start, stagingParts, checks.toList)
    }

    // ── 发布：逐表元数据指针切换（幂等：已指向同一路径则仅重放） ──
    val switched = ListBuffer.empty[String]
    val replayed = ListBuffer.empty[String]
    tables.foreach { t =>
      val formal = AdsSql.formal(ns, t)
      val ev = stgOf(AdsSql.staging(ns, t))
      val loc = ev.path.get
      val before = PartitionEvidence.collect(spark, Seq(formal), None, Some(dt)).headOption
      val same = before.flatMap(_.path).exists(_.stripSuffix("/") == loc.stripSuffix("/"))
      if (same) replayed += t else switched += t
      spark.sql(s"ALTER TABLE $formal ADD IF NOT EXISTS PARTITION (dt = '$dt') LOCATION '$loc'")
      spark.sql(s"ALTER TABLE $formal PARTITION (dt = '$dt') SET LOCATION '$loc'")
    }

    // ── 发布后校验：正式分区行数 = 暂存分区行数（真实 COUNT(*)，证据随 JobResult 落库） ──
    val formalParts = PartitionEvidence.collect(spark, formalTables, Some(sid), Some(dt))
    val formalOf = formalParts.map(p => p.table -> p.rowCount).toMap
    val mismatch = tables.filter(t => formalOf.getOrElse(AdsSql.formal(ns, t), -1L) != stgOf(AdsSql.staging(ns, t)).rowCount)
    checks += QualityCheck("PUB_FORMAL_PARTITION_MATCH", "PUBLISH", formalTables.mkString(","),
      tables.size, mismatch.size, "正式=暂存行数", "BLOCKING", mismatch.isEmpty,
      if (mismatch.isEmpty) s"8 张正式分区行数与暂存一致（合计 ${formalParts.map(_.rowCount).sum} 行）"
      else s"行数不一致: ${mismatch.map(t => s"$t stg=${stgOf(AdsSql.staging(ns, t)).rowCount} formal=${formalOf.getOrElse(AdsSql.formal(ns, t), -1L)}").mkString(",")}")
    checks += QualityCheck("PUB_POINTER_SWITCH", "PUBLISH", formalTables.mkString(","),
      tables.size, switched.size, "本次切换表数", "INFO", true,
      s"本次切换 ${switched.size} 张（${switched.mkString(",")}）；同快照重放 ${replayed.size} 张" +
        s"（同 snapshotId 不产生第二份数据，§14.4 发布幂等）")

    // ── 暂存清理：只清理**本次业务日期**的历史暂存快照（§14.4 第 6 步） ──
    // R9 修正：原实现不带 dt 限定，发布任一日期会连带删除**其他业务日期**尚未发布的暂存分区，
    // 使交错/并发运行在 QUALITY_CHECK 误报 ADS_STAGING_PRESENT（8 张暂存表全空）而失败，
    // 并使失败运行的 resume/retry-from-stage 因暂存已被清空而永久失败（实测 run 26）。
    val removed = ListBuffer.empty[String]
    if (prune) {
      val referenced = formalTables.flatMap(t =>
        PartitionEvidence.collect(spark, Seq(t), None, None).flatMap(_.path)).map(norm).toSet
      stagingTables.foreach { stg =>
        PartitionEvidence.collect(spark, Seq(stg), None, None)
          .filter(p => p.dt == dt)
          .filterNot(_.snapshotId.contains(sid))
          .filterNot(p => p.path.map(norm).exists(referenced.contains))
          .foreach { p =>
            val pSid = p.snapshotId.getOrElse("")
            spark.sql(s"ALTER TABLE $stg DROP IF EXISTS PARTITION (snapshot_id = '$pSid', dt = '${p.dt}')")
            p.path.foreach(loc => deletePath(spark, loc))
            removed += s"$stg/$pSid/dt=${p.dt}(${p.rowCount}行)"
          }
      }
    }
    checks += QualityCheck("PUB_STAGING_PRUNE", "PUBLISH", stagingTables.mkString(","),
      removed.size, 0L, s"只清理 dt=$dt 的历史暂存快照（其他业务日期的暂存分区不动）", "INFO", true,
      if (!prune) "本次未启用清理（--pruneStaging=false）"
      else if (removed.isEmpty) s"dt=$dt 无待清理历史暂存分区（保留 $sid 及被正式分区引用的路径）"
      else s"已清理 ${removed.size} 个 dt=$dt 历史暂存分区: ${removed.mkString(",")}")

    val all = checks.toList
    val blockingFailed = all.filter(c => c.severity == "BLOCKING" && !c.passed)
    val elapsed = System.currentTimeMillis() - start
    if (blockingFailed.nonEmpty) {
      JobResult.failed(code, args.attemptNo,
        s"发布校验失败: ${blockingFailed.map(_.ruleCode).mkString(",")}", elapsed, formalParts, all)
    } else {
      JobResult.success(code, stagingParts.map(_.rowCount).sum, formalParts.map(_.rowCount).sum,
        0L, Some(sid), args.attemptNo, elapsed, formalParts, all)
    }
  }

  private def norm(p: String): String = p.stripSuffix("/")

  private def deletePath(spark: SparkSession, loc: String): Unit = {
    val path = new Path(loc)
    val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
    fs.delete(path, true)
  }
}

object AdsPublishJob {
  val instance: AdsPublishJob = new AdsPublishJob()
}
