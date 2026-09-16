package com.graduation.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import com.graduation.analytics.job.{
  JobArgs, JobRegistry, JobResult, LocalSchemaInitJob, PartitionEvidence, WarehouseJob
}
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.zip.CRC32
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * S2-06：**DWS/ADS 主链真跑**（`usw` → `fna` → `dqc` → `pub` → `mxp`）在真实 Spark 上的端到端执行证据。
 *
 * 为什么要有这个套件：本轮之前 `UserProductDwsJob` / `FunnelAdsJob` / `AdsQualityJob` /
 * `AdsPublishJob` / `MetricExportJob` 这 5 个作业（连同 `DwsSql` 的 7 条 `INSERT OVERWRITE`、
 * `AdsSql` 的 8 条 staging 写入、发布侧的元数据指针切换、导出侧的 JSONL+manifest）
 * **从未在任何测试里被执行过**（`spark-jobs/src/test` 下无任何引用），只有 SQL 模板文本断言。
 * 本套件把它们按 `JobRegistry.dependencies` 的顺序真跑一遍。
 *
 * 复用仓库既有夹具与**真实入口**，不自造 SparkSession、不抄 DDL 副本、不改任何生产代码：
 *  - `P2TestSupport.spark(SuiteName)`（隔离 warehouse；in-memory catalog —— 该文件自陈的
 *    证明边界：「测试通过」≠「在产 Hive metastore 通过」，本套件的结论同样只在此边界内成立）；
 *  - `LocalSchemaInitJob.statements(ns)` 建 5 库 + DWD/DIM/DWS/ADS(+staging) 表；
 *  - 各作业实例经 `JobRegistry.lookup(code)` 取，`run` 真跑；
 *  - `PartitionEvidence.collect` 取「正式分区行数 / Location」的作业侧证据。
 *
 * 断言对着**行级真实结果**与设计口径（§9.2 L313-319、§9.3 L328-335、§9.5 L353、
 * §10.1 L361-373、§10.2、§12.3、§12.5 L527-538），不是「非空即绿」。
 *
 * ── DWS 数字的独立 oracle ────────────────────────────────────────────────
 * 7 张 DWS 的期望值**不是**再跑一遍 `DwsSql` 的聚合 SQL，而是：把 `dwd_user_behavior_detail` /
 * `dwd_order_detail` 的**行原样取回本进程**（只做投影，不做聚合），在 Scala 里用 `groupBy`/`sum`
 * 自算。这样「作业 SQL 写错口径」与「oracle 写错口径」不会同时错成一样。
 *
 * ── 编排顺序 ────────────────────────────────────────────────────────────
 * `sci → odl → dim → bdw → tdw → usw → fna(S1) → dqc(S1) → pub(S1) → mxp(S1)
 *  → pub(S1 重放，验幂等) → fna(S2) → dqc(S2) → pub(S2，看旧快照是否被保留)`
 */
class DwsAdsChainExecSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import DwsAdsChainExecSpec._

  private var spark: SparkSession = _
  private var cap: Capture = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark(SuiteName)
    cap = try collectAll(spark) catch {
      case t: Throwable =>
        println(s"[s206] 采集整体失败：${t.getClass.getName}: ${t.getMessage}")
        t.getStackTrace.take(15).foreach(e => println(s"[s206]   at $e"))
        Capture.crashed(s"${t.getClass.getName}: ${t.getMessage}")
    }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ══════════════════════════════════════════════════════════════════════
  // 1. 链执行
  // ══════════════════════════════════════════════════════════════════════

  "S2-06 链执行（sci → odl → dim → bdw → tdw → usw → fna → dqc → pub → mxp）" should {

    "九个作业按依赖顺序真跑，每一步都留下痕迹" in {
      withClue(s"链执行记录（含异常）：${cap.steps.map(_.desc).mkString(" ;; ")}；采集注释：${cap.notes.mkString(" ;; ")}") {
        cap.crash should be(None)
        cap.steps.map(_.code).distinct.toSet should be(
          Set("odl", "dim", "bdw", "tdw", "usw", "fna", "dqc", "pub", "mxp"))
        cap.steps.head.code should be("odl")
      }
    }

    "odl/dim/bdw/tdw/usw/fna/dqc 七步状态均为 SUCCESS（设计 §10.2 八阶段在 Spark 侧的映射）" in {
      val bad = Seq("odl", "dim", "bdw", "tdw", "usw", "fna", "dqc").map(c => c -> cap.step(c))
        .filterNot { case (_, s) => s.ok }
      withClue(s"未成功的步骤：${bad.map { case (c, s) => s"$c=${s.desc}" }.mkString(" | ")}：") {
        bad should be(empty)
      }
    }

    "ODS/DWD 真实行数与黄金夹具（16 行为事件 / 14 DWD 行为 / 7 订单明细）一致" in {
      withClue(s"ODS 各主题行数=${cap.odsCounts}：") {
        cap.odsCounts.getOrElse("ods_behavior_event", -1L) should be(16L)
        cap.odsCounts.getOrElse("ods_user_event", -1L) should be(4L)
        cap.odsCounts.getOrElse("ods_product_event", -1L) should be(14L)
      }
      withClue("DWD 行为明细（§9.2 L310：去重 + 枚举白名单）：") {
        cap.dwdBehaviorRows should be(14L)
      }
      withClue("DWD 订单明细（§9.2 L311：订单项行，O1001 两行）：") {
        cap.dwdOrderRows should be(7L)
      }
      withClue(s"作业自报计数（不得用输入数冒充输出数）；usw=${cap.step("usw").desc} tdw=${cap.step("tdw").desc}：") {
        cap.step("usw").status should be("SUCCESS")
        cap.step("tdw").status should be("SUCCESS")
        cap.step("usw").result.inputRecords should be(14L)
        cap.step("tdw").result.outputRecords should be(7L)
      }
    }

    "作业注册表与设计 §10.1 L361-373 的链式前置一致（含实测差异记录）" in {
      val deps = JobRegistry.dependencies
      withClue("§10.1 表：usw←bdw,tdw / fna←usw / dqc←fna / pub←dqc / mxp←pub：") {
        deps("usw").sorted should be(List("bdw", "tdw"))
        deps("fna") should be(List("usw"))
        deps("dqc") should be(List("fna"))
        deps("pub") should be(List("dqc"))
        deps("mxp") should be(List("pub"))
      }
      withClue("§10.1 表：tdw←odl,dim；dim←odl：") {
        deps("tdw").sorted should be(List("dim", "odl"))
        deps("dim") should be(List("odl"))
      }
      // 实测差异（报告「差异」一节记录，非断言失败）：设计 §10.1 L365 写 bdw 前置 = odl，
      // 代码为 List("odl","dim")（bdw 的 SQL LEFT JOIN dim_user/dim_product）。
      // 此处只钉住**实测值**，供报告对比设计表。
      println(s"[s206] 实测 JobRegistry.dependencies('bdw') = ${deps("bdw")}（设计 §10.1 L365 写 odl）")
      println(s"[s206] §10.1 L375 真实环检测：topologicalOrder=${JobRegistry.topologicalOrder().map(_.mkString(">"))} hasCycle=${JobRegistry.hasCycle}")
      deps("bdw") should be(List("odl", "dim"))
    }

    "dqc 的质量规则、pub 的发布检查留在 JobResult.checks；fna 的完成证据留在 JobResult.outputPartitions" in {
      withClue(s"dqc(S1) checks=${cap.checks("dqc")}：") {
        // S3-10：规则 7（率列跨层对账）加入后实测 7 条；同时钉住新码**确实被产出**
        // （只写函数不接线是本项目发生过的一类缺陷：断言存在不等于链路上被调用）
        cap.checks("dqc").size should be >= 7
        cap.checks("dqc").map(_.split('|').head) should contain("ADS_DWS_FUNNEL_RATE_RECONCILE")
      }
      // 我最初的期望是 fna 也要有 checks —— 实测 List()（r3 L120）。核对 §10.2 L386：BUILD_ADS 的
      // 「完成证据」是「staging表/快照、各表行数」，由 PartitionEvidence 承载；§10.2 L387 明写
      // QUALITY_CHECK（dqc）才产出「规则版本与失败详情」。故 fna 无 checks 是符合设计的，我改断言。
      withClue(s"fna outputPartitions=${cap.step("fna").result.outputPartitions}；checks=${cap.checks("fna")}：") {
        cap.step("fna").result.outputPartitions.size should be(8)
        cap.step("fna").result.outputPartitions.map(_.rowCount).forall(_ > 0L) should be(true)
        cap.step("fna").result.outputPartitions.flatMap(_.snapshotId).distinct should be(Seq(Snap1))
      }
      withClue(s"pub(S1) checks=${cap.checks("pub")}：") {
        cap.checks("pub").map(_.split('|').head) should contain allOf(
          "PUB_STAGING_READY", "PUB_FORMAL_PARTITION_MATCH", "PUB_POINTER_SWITCH", "PUB_STAGING_PRUNE")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 2. DWS 存在性与行数
  // ══════════════════════════════════════════════════════════════════════

  "DWS 层（§9.2 L313-319：7 张逻辑表）" should {

    "7 张 DWS 表全部由 usw 真建出来，且 dt=20260901 分区每张都有行" in {
      withClue(s"表存在性=${cap.dwsExists}；分区行数=${cap.dwsRows}：") {
        cap.dwsExists.values.forall(identity) should be(true)
        cap.dwsExists.keySet should be(DwsTables.toSet)
      }
      withClue(s"空/缺失分区的 DWS 表（§9.2 要求 usw 产出 7 张主题聚合）：${cap.dwsRows.filter(_._2 <= 0).keys.mkString(",")}：") {
        cap.dwsRows.filter(_._2 <= 0) should be(empty)
      }
      println(s"[s206] DWS dt=$BusinessDate 行数：${cap.dwsRows.toSeq.sortBy(_._1).map { case (t, n) => s"$t=$n" }.mkString(", ")}")
    }

    "7 张 DWS 表的数据目录在文件系统上真实存在且非空（不只是 catalog 里有名字）" in {
      // 逐表断言（不是对空 Map 做 forall —— r2 里这一条曾因空集合平凡通过，已修）
      withClue(s"fs 目录存在性=${cap.dwsDirExists}：") {
        DwsTables.foreach(t => withClue(s"$t 目录：") { cap.dwsDirExists.getOrElse(t, false) should be(true) })
      }
      withClue(s"fs 目录内 parquet 文件数=${cap.dwsDirFiles}：") {
        DwsTables.foreach(t => withClue(s"$t parquet 文件数：") { cap.dwsDirFiles.getOrElse(t, -1L) should be > 0L })
      }
      // 主控要求把绝对路径与 `fs.listStatus` 的**原始条目名**一起打出来（r3 那次 7 张全 0 的探针错，
      // 根因是只看表根目录、没看到 `dt=<D>/` 子目录；这里把证据钉死）。
      DwsTables.foreach { t =>
        withClue(s"$t 目录条目：") {
          cap.dwsDirEntries.keySet should contain(t)
          cap.dwsDirEntries.getOrElse(t, Seq.empty) should contain(s"dt=$BusinessDate")
        }
      }
      DwsTables.foreach { t =>
        println(s"[s206] DWS 目录证据 $t：path=${cap.dwsDirPaths.getOrElse(t, "<无>")} " +
          s"entries=[${cap.dwsDirEntries.getOrElse(t, Seq.empty).mkString(", ")}] " +
          s"parquet(递归)=${cap.dwsDirFiles.getOrElse(t, -1L)}")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 3. DWS 独立 oracle（本进程自算，不重跑作业 SQL）
  // ══════════════════════════════════════════════════════════════════════

  "DWS 独立 oracle（从 DWD 行原样取回后在本进程 groupBy 自算）" should {

    "dws_trade_day：支付订单数/买家数/GMV/退款/净销售/客单价（§9.2 L317）" in {
      cancelIfNoUsW()
      withClue(s"oracle=${cap.oracle.trade} 实测=${cap.measured.trade}（DWD 支付行=${cap.dwdOrderPaidRows}）：") {
        cap.measured.trade should be(cap.oracle.trade)
      }
      println(s"[s206] dws_trade_day oracle=${cap.oracle.trade} measured=${cap.measured.trade}")
    }

    "dws_user_behavior_day：source+user+dt 的 PV/收藏/加购/搜索/活跃小时/支付件数（§9.2 L313）" in {
      cancelIfNoUsW()
      withClue(s"oracle=${cap.oracle.behavior} 实测=${cap.measured.behavior}：") {
        cap.measured.behavior should be(cap.oracle.behavior)
      }
    }

    "dws_product_behavior_day：product+dt 的 pv/uv/fav/cart/buy（§9.2 L315）" in {
      cancelIfNoUsW()
      withClue(s"oracle=${cap.oracle.productBehavior} 实测=${cap.measured.productBehavior}：") {
        cap.measured.productBehavior should be(cap.oracle.productBehavior)
      }
    }

    "dws_product_sale_day：product+dt 的支付件数/金额/去重买家（§9.2 L316）" in {
      cancelIfNoUsW()
      withClue(s"oracle=${cap.oracle.productSale} 实测=${cap.measured.productSale}：") {
        cap.measured.productSale should be(cap.oracle.productSale)
      }
    }

    "dws_user_trade_period：观察窗口内的支付订单数/金额/最近支付日（§9.2 L318）" in {
      cancelIfNoUsW()
      withClue(s"oracle=${cap.oracle.userPeriod} 实测=${cap.measured.userPeriod}：") {
        cap.measured.userPeriod should be(cap.oracle.userPeriod)
      }
    }

    "dws_region_sale_day：region=城市等级的 buyer/order/sale（§9.2 L319）" in {
      cancelIfNoUsW()
      withClue(s"oracle=${cap.oracle.region} 实测=${cap.measured.region}：") {
        cap.measured.region should be(cap.oracle.region)
      }
    }

    "dws_behavior_funnel_day：view/intent/order/pay 去重用户与转化率（§9.2 L314、§12.2 宽松口径）" in {
      cancelIfNoUsW()
      withClue(s"oracle=${cap.oracle.funnel} 实测=${cap.measured.funnel}：") {
        cap.measured.funnel should be(cap.oracle.funnel)
      }
      println(s"[s206] dws_behavior_funnel_day oracle=${cap.oracle.funnel} measured=${cap.measured.funnel}")
    }

    "跨层口径：dws_trade_day 的支付订单数与 dws_behavior_funnel_day 的支付用户数各自独立成立（§16.4 对账思路）" in {
      cancelIfNoUsW()
      withClue("支付订单数=5 条订单明细订单；支付用户=3 人；两者是不同维度，此处只钉住实测值：") {
        cap.measured.trade.map(_.orderCount).getOrElse(-1L) should be(5L)
        cap.measured.funnel.map(_.payUsers).getOrElse(-1L) should be(3L)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 4. fna 暂存与发布隔离
  // ══════════════════════════════════════════════════════════════════════

  "fna 暂存写入与发布隔离（§9.5 L353、§10.2 BUILD_ADS）" should {

    "8 张 ADS 暂存表在 snapshot_id=S1 / dt=20260901 分区上逐表 >0 行" in {
      val empty = cap.stagingRowsS1.filter(_._2 <= 0)
      withClue(s"暂存行数=${cap.stagingRowsS1}：") {
        cap.stagingRowsS1.keySet should be(AdsSql.TABLES.toSet)
        empty should be(empty)
      }
      println(s"[s206] fna staging(S1) 行数=${cap.stagingRowsS1.toSeq.sortBy(_._1).map { case (t, n) => s"$t=$n" }.mkString(", ")}（合计 ${cap.stagingRowsS1.values.sum}）")
    }

    "8 张暂存表的行内容可读且与行数自洽（行级证据，非只数个数）" in {
      withClue(s"逐表行数=${cap.adsStagingRendered.map { case (t, r) => s"$t=${r.size}" }}：") {
        cap.adsStagingRendered.size should be(8)
        cap.adsStagingRendered.foreach { case (t, rows) =>
          withClue(s"$t 暂存行：$rows：") {
            rows.size.toLong should be(cap.stagingRowsS1.getOrElse(t, -1L))
            rows should not be empty
          }
        }
      }
      cap.adsStagingRendered.toSeq.sortBy(_._1).foreach { case (t, rows) =>
        println(s"[s206] ${t}__staging: ${rows.mkString(" ;; ")}")
      }
    }

    "pub 之前：8 张正式 ADS 表在 dt=20260901 上既无分区、也无任何行（发布隔离）" in {
      withClue(s"pub 前正式分区证据=${cap.formalBeforePub}：") {
        cap.formalBeforePub.size should be(8)
        cap.formalBeforePub.filterNot { case (_, p) => p.partitions.isEmpty } should be(empty)
        cap.formalBeforePub.filterNot { case (_, p) => p.tableRowCount == 0L } should be(empty)
        cap.formalBeforePub.filterNot { case (_, p) => p.location.isEmpty } should be(empty)
        cap.formalBeforePub.filterNot { case (_, p) => p.parquetRowCount.isEmpty } should be(empty)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 5. pub 发布
  // ══════════════════════════════════════════════════════════════════════

  "pub 发布（§9.5 L353「验证后发布」、§10.2 PUBLISH_METRIC）" should {

    "逐表元数据指针切换：正式分区 Location 必须指向本次快照 snapshot_id=S1 的暂存路径" in {
      val wrong = cap.formalAfterPubS1.filterNot { case (_, p) =>
        p.location.exists(l => l.contains(s"snapshot_id=$Snap1") && l.contains(s"dt=$BusinessDate"))
      }
      withClue(s"pub 后正式分区 Location=${cap.formalAfterPubS1.map { case (t, p) => s"$t -> ${p.location}" }}：") {
        cap.formalAfterPubS1.keySet should be(AdsSql.TABLES.toSet)
        wrong should be(empty)
      }
    }

    "正式分区的真实数据行数 = 暂存行数，且 dt=20260901 每表只有一个分区（无第二份数据）" in {
      val mismatched = AdsSql.TABLES.filter { t =>
        val p = cap.formalAfterPubS1.get(t)
        p.flatMap(_.parquetRowCount).getOrElse(-1L) != cap.stagingRowsS1.getOrElse(t, -2L)
      }
      withClue(s"正式分区真实 parquet 行数=${cap.formalAfterPubS1.map { case (t, p) => s"$t -> ${p.parquetRowCount}" }}；暂存=${cap.stagingRowsS1}：") {
        mismatched should be(empty)
      }
      withClue(s"每表 dt=$BusinessDate 分区列表=${cap.formalAfterPubS1.map { case (t, p) => s"$t -> ${p.partitions}" }}：") {
        cap.formalAfterPubS1.filterNot { case (_, p) => p.partitions.size == 1 } should be(empty)
      }
      println(s"[s206] pub 后 PartitionEvidence(作业侧 catalog 计数)=${cap.formalAfterPubS1.map { case (t, p) => s"$t -> ${p.catalogRowCount}" }.toSeq.sorted.mkString(", ")}")
      println(s"[s206] pub 后 SELECT COUNT(*) FROM 正式表 WHERE dt=$BusinessDate=${cap.formalAfterPubS1.map { case (t, p) => s"$t -> ${p.tableRowCount}" }.toSeq.sorted.mkString(", ")}")
      println(s"[s206] pub 后按 Location 直读 parquet 行数=${cap.formalAfterPubS1.map { case (t, p) => s"$t -> ${p.parquetRowCount.getOrElse(-1L)}" }.toSeq.sorted.mkString(", ")}")
    }

    "pub 作业状态必须为 SUCCESS（设计 §10.2：完成证据 = Hive 制品行数一致）" in {
      withClue(s"pub 结果=${cap.step("pub").desc}；checks=${cap.checks("pub")}：") {
        cap.step("pub").status should be("SUCCESS")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 6. mxp 导出
  // ══════════════════════════════════════════════════════════════════════

  "mxp 导出制品（§9.3 L328-335 的 8 张镜像、§12.5 L528 manifest）" should {

    "8 个 JSONL 与 _export.json 清单落盘、非空，且逐表文件行数 = 暂存行数" in {
      withClue(s"exportDir=${cap.exportDir}；文件存在=${cap.exportFileExists}：") {
        cap.exportFileExists.values.forall(identity) should be(true)
        cap.exportFileExists.keySet should be(MetricAdsSpec.TABLES.map(_.mysqlTable).toSet)
      }
      withClue(s"文件行数=${cap.exportFileLines}；暂存行数=${cap.stagingRowsS1}：") {
        MetricAdsSpec.TABLES.foreach { spec =>
          withClue(s"${spec.mysqlTable}.jsonl 行数：") {
            cap.exportFileLines.getOrElse(spec.mysqlTable, -1L) should be(
              cap.stagingRowsS1.getOrElse(spec.table, -2L))
          }
        }
      }
      withClue(s"manifest=${cap.manifestRaw}：") {
        cap.manifestExists should be(true)
        cap.manifestRaw.trim should not be empty
      }
    }

    "manifest 与 Hive 实测一致：snapshotId=S1、dt=20260901、totalRows=逐表之和、路径指向本次快照暂存目录" in {
      val expectedRows = MetricAdsSpec.TABLES.map(spec =>
        spec.mysqlTable -> cap.stagingRowsS1.getOrElse(spec.table, -2L)).toMap
      withClue(s"manifest 快照/日期/合计=${cap.manifestSnapshotId}/${cap.manifestDt}/${cap.manifestTotalRows}：") {
        cap.manifestSnapshotId should be(Some(Snap1))
        cap.manifestDt should be(Some(BusinessDate))
        cap.manifestTotalRows should be(Some(cap.stagingRowsS1.values.sum))
      }
      withClue(s"manifest 逐表行数=${cap.manifestRowCounts}；期望=${expectedRows}：") {
        cap.manifestRowCounts should be(expectedRows)
      }
      withClue(s"manifest hivePath=${cap.manifestHivePaths}：") {
        cap.manifestHivePaths.size should be(8)
        cap.manifestHivePaths.values.foreach { p =>
          p should include(s"snapshot_id=$Snap1")
        }
      }
      withClue(s"manifest 里的 exportFile 必须指向真实存在的文件：${cap.manifestExportFiles}：") {
        cap.manifestExportFiles.size should be(8)
        cap.manifestExportFiles.values.foreach { f =>
          Files.isRegularFile(nioPath(f)) should be(true)
        }
      }
    }

    "逐表 checksum 必须等于该导出文件真实字节的 CRC32（§12.5 L528 manifest/checksum、L529 内容验证）" in {
      withClue(s"manifest checksum=${cap.manifestChecksums}：") {
        cap.manifestChecksums.keySet should be(MetricAdsSpec.TABLES.map(_.mysqlTable).toSet)
        MetricAdsSpec.TABLES.foreach { spec =>
          val file = nioPath(cap.manifestExportFiles.getOrElse(spec.mysqlTable,
            throw new IllegalStateException(s"清单缺 ${spec.mysqlTable} 的 exportFile")))
          // 独立重算：直接读该文件全部原始字节（不复用导出作业的 Hadoop 流式实现）
          val crc = new CRC32()
          crc.update(Files.readAllBytes(file))
          val expected = java.lang.Long.toHexString(crc.getValue)
          withClue(s"${spec.mysqlTable}.jsonl checksum：") {
            cap.manifestChecksums(spec.mysqlTable) should be(expected)
            cap.manifestChecksums(spec.mysqlTable) should fullyMatch regex "^[0-9a-f]{1,8}$"
          }
        }
      }
    }

    "mxp 作业状态必须为 SUCCESS（设计 §10.2：导出完整才算发布制品就绪）" in {
      withClue(s"mxp 结果=${cap.step("mxp").desc}；checks=${cap.checks("mxp")}：") {
        cap.step("mxp").status should be("SUCCESS")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 7. 重跑幂等
  // ══════════════════════════════════════════════════════════════════════

  "重跑幂等（同一 snapshotId 再发布一次）" should {

    "第二次 pub(S1) 不产生第二份数据：8 张正式分区 Location 与行数逐一不变，暂存 S1 仍在" in {
      withClue("两次发布都必须覆盖全部 8 张表（防空集合导致的平凡通过）：") {
        cap.formalAfterPubS1.keySet should be(AdsSql.TABLES.toSet)
        cap.formalAfterPubAgain.keySet should be(AdsSql.TABLES.toSet)
      }
      withClue(s"第一次=${cap.formalAfterPubS1.map { case (t, p) => s"$t -> ${p.location}" }}：") {
        withClue(s"第二次=${cap.formalAfterPubAgain.map { case (t, p) => s"$t -> ${p.location}" }}：") {
          cap.formalAfterPubAgain.map { case (t, p) => t -> p.location } should be(
            cap.formalAfterPubS1.map { case (t, p) => t -> p.location })
        }
      }
      withClue("重放后每表 dt 分区数仍为 1（未新增第二份）：") {
        cap.formalAfterPubAgain.filterNot { case (_, p) => p.partitions.size == 1 } should be(empty)
      }
      withClue(s"重放后暂存 S1 行数=${cap.stagingRowsS1AfterPubAgain}：") {
        cap.stagingRowsS1AfterPubAgain should be(cap.stagingRowsS1)
      }
      withClue(s"pub 重放的作业自报（§14.4 幂等）：${cap.pubAgainPointerSwitch}：") {
        cap.pubAgainPointerSwitch should include("同快照重放 8 张")
      }
      println(s"[s206] pub(S1) 重放：${cap.pubAgainPointerSwitch}")
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 8. 暂存清理的 dt 限定（D-R9-1）+ 正式分区指针
  // ══════════════════════════════════════════════════════════════════════

  "发布后的暂存清理与正式分区指针（D-R9-1 已验收口径）" should {

    "暂存清理按 dt 限定（同 dt 历史快照回收、异业务日期不被波及）" should {

      // 断言方向修正（spec 侧笔误，非产品缺陷）：本用例最初写成「发布 S2 后同 dt 的旧快照 S1 暂存仍应保留」，
      // 与 R9 已验收的既定行为相反。逐字核对原文如下（本会话已实测读取，非转述）：
      //  · docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/26-prune-fix-verification.tsv L2/L5/L6：
      //    P0「真实元数据库注入，模拟"另一业务日期在飞/未发布"的暂存分区」；
      //    P2「异日期暂存分区存活（修复点）… 修复前该分区会被本次发布删除（AdsPublishJob 原实现无 dt 限定）」= PASS；
    //    P3「同日期历史暂存仍按设计清理」期望 `snapshot_id=S20260901_29/dt=20260901 已被清理` = PASS
    //    （备注原文：「dt 限定后同 dt 的历史快照仍被回收（正式分区已指向新快照）」）；
    //    P4 自述「PUB_STAGING_PRUNE 说明含 dt=20260901」。
    //  · 同目录 30-final-acceptance.md L55（C4 / D-R9-1）：「修复后实测：异日期分区 S20260907_TEST/dt=20260907 存活，
    //    同日期历史 S20260901_29/dt=20260901 仍被回收（P2/P3/P4）」。
    //  · 实现原文 spark-jobs/…/job/AdsPublishJob.scala L90-115：`.filter(p => p.dt == dt)`；L112 阈值
    //    「只清理 dt=$dt 的历史暂存快照（其他业务日期的暂存分区不动）」。
    //  · docs/acceptance/f88-dq-severity-20260912/INVENTORY.md：`PUB_STAGING_PRUNE` 是 INFO 发布操作审计项（非质量规则）。
    // 因此「同 dt 历史快照的暂存不可重放」是**已验收的设计边界/已知取舍**，不登记为缺陷。

    "同 dt 历史快照 S1 的暂存分区被回收（正式分区已指向 S2），且自述阈值限定在 dt 内" in {
      withClue(s"S1 暂存行数=${cap.stagingRowsS1AfterPubS2}；S1 目录存在性=${cap.stagingS1PathExistsAfterPubS2}：") {
        cap.stagingRowsS1AfterPubS2.keySet should be(AdsSql.TABLES.toSet)
        cap.stagingRowsS1AfterPubS2.values.sum should be(0L)
        cap.stagingS1PathExistsAfterPubS2.values.count(identity) should be(0)
      }
      withClue(s"pub(S2) PUB_STAGING_PRUNE 阈值原文=${cap.pub2PruneThreshold}：") {
        cap.pub2PruneThreshold should include(s"只清理 dt=$BusinessDate")
        cap.pub2PruneThreshold should include("其他业务日期的暂存分区不动")
      }
      withClue(s"pub(S2) 的清理明细应逐表列出被清理的 S1 分区：${cap.pub2PruneDetail}：") {
        cap.pub2PruneDetail should not be empty
        cap.pub2PruneDetail should include(s"dt=$BusinessDate")
      }
      println(s"[s206] 已验收口径（同 dt 回收）：S1 暂存行数=${cap.stagingRowsS1AfterPubS2}；S1 目录存在性=${cap.stagingS1PathExistsAfterPubS2}")
      println(s"[s206] pub(S2) PUB_STAGING_PRUNE 阈值=${cap.pub2PruneThreshold}")
      println(s"[s206] pub(S2) PUB_STAGING_PRUNE 明细=${cap.pub2PruneDetail}")
    }

    "D-R9-1 判别探针：异业务日期（snapshot_id=$ForeignSnap/dt=$ForeignDate）的暂存分区在 pub(S1) 与 pub(S2) 之后都必须存活" in {
      withClue(s"埋点方式=${cap.foreignSeedRoute}：") {
        cap.foreignSeedRoute should not include "THREW"
        cap.foreignRowsAfterSeed.keySet should be(AdsSql.TABLES.toSet)
        withClue(s"埋点后异日期暂存行数=${cap.foreignRowsAfterSeed}：") {
          cap.foreignRowsAfterSeed.values.sum should be(8L)
        }
      }
      withClue(s"pub(S1)（dt=$BusinessDate）之后异日期暂存行数=${cap.foreignRowsAfterPubS1}：") {
        cap.foreignRowsAfterPubS1.keySet should be(AdsSql.TABLES.toSet)
        cap.foreignRowsAfterPubS1.values.sum should be(8L)
      }
      withClue(s"pub(S2) 之后异日期暂存行数=${cap.foreignRowsAfterPubS2}；目录存在性=${cap.foreignDirExistsAfterPubS2}：") {
        cap.foreignRowsAfterPubS2.keySet should be(AdsSql.TABLES.toSet)
        cap.foreignRowsAfterPubS2.values.sum should be(8L)
        cap.foreignDirExistsAfterPubS2.keySet should be(AdsSql.TABLES.toSet)
        cap.foreignDirExistsAfterPubS2.values.count(identity) should be(8)
      }
      println(s"[s206] D-R9-1 判别探针：埋点后=${cap.foreignRowsAfterSeed.values.sum} 行；" +
        s"pub(S1) 后=${cap.foreignRowsAfterPubS1.values.sum} 行；pub(S2) 后=${cap.foreignRowsAfterPubS2.values.sum} 行；" +
        s"物理目录存活=${cap.foreignDirExistsAfterPubS2.values.count(identity)}/8")
    }
  }

    "发布 S2 后正式分区指向 S2（本次发布生效），且 dt=20260901 仍只有 1 个分区" in {
      val wrong = cap.formalAfterPubS2.filterNot { case (_, p) =>
        p.location.exists(_.contains(s"snapshot_id=$Snap2"))
      }
      withClue(s"S2 正式分区 Location=${cap.formalAfterPubS2.map { case (t, p) => s"$t -> ${p.location}" }}：") {
        cap.formalAfterPubS2.keySet should be(AdsSql.TABLES.toSet)
        wrong should be(empty)
      }
      withClue(s"每表 dt 分区数=${cap.formalAfterPubS2.map { case (t, p) => s"$t -> ${p.partitions}" }}：") {
        cap.formalAfterPubS2.filterNot { case (_, p) => p.partitions.size == 1 } should be(empty)
      }
      withClue(s"S2 暂存行数=${cap.stagingRowsS2}：") {
        cap.stagingRowsS2.filter(_._2 <= 0) should be(empty)
      }
      println(s"[s206] pub(S2) 后正式分区=${cap.formalAfterPubS2.map { case (t, p) => s"$t -> ${p.location.map(_.split('/').takeRight(2).mkString("/")).getOrElse("<无>")}" }.toSeq.sorted.mkString(", ")}")
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 9. 测试域边界与未测项
  // ══════════════════════════════════════════════════════════════════════

  "测试域边界（in-memory catalog ≠ 在产 Hive metastore）" should {

    "钉住本轮实测到的域差异：catalog 分区计数与物理路径直读计数的对照值" in {
      val diff = AdsSql.TABLES.map { t =>
        val p = cap.formalAfterPubS1.get(t)
        t -> (p.map(_.catalogRowCount).getOrElse(-1L), p.flatMap(_.parquetRowCount).getOrElse(-1L),
          p.map(_.tableRowCount).getOrElse(-1L))
      }
      println(s"[s206] 域差异（表 -> (PartitionEvidence.catalog计数, Location直读parquet计数, SQL表计数)）：" +
        diff.map { case (t, (a, b, c)) => s"$t -> ($a,$b,$c)" }.mkString("; "))
      withClue(s"Location 直读计数必须等于暂存行数（发布的数据本体正确）：$diff：") {
        diff.filterNot { case (t, (_, pq, _)) => pq == cap.stagingRowsS1.getOrElse(t, -2L) } should be(empty)
      }
    }

    "跨业务日**端到端**链路（另一 dt 走完 fna→dqc→pub）本轮未测——第二个业务日无法产出非空 ADS 暂存" in {
      withClue(s"ods_trade_event 分区分布=${cap.odsTradePartitions}（迟到退款落在 $BusinessDate2）：") {
        cap.odsTradePartitions.size should be >= 1
      }
      // 事实：dt=20260902 只有退款事件（无行为/下单），DWS 漏斗与 ADS 暂存行数为 0 ⇒
      // dqc 的 ADS_STAGING_PRESENT 与 pub 的 PUB_STAGING_READY 必然阻断（BLOCKING），
      // 因此「另一个业务日**真的走完发布**」这一条在黄金夹具上不可测量。
      // 已替代测量：上节 D-R9-1 判别探针**手工埋入** dt=$ForeignDate 的暂存分区，覆盖清理谓词的 dt 边界；
      // 未覆盖的是「异 dt 正式分区（已发布）在本次发布后保留」这条更长的链路。
      println(s"[s206] 未测：跨业务日端到端发布（dt=$BusinessDate2 无可发布 ADS 数据；证据 odsTradePartitions=${cap.odsTradePartitions}）")
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  private def cancelIfNoUsW(): Unit =
    if (!cap.step("usw").ok) cancel(s"usw 未成功（${cap.step("usw").desc}）⇒ DWS 结果未产生，本项未测")
}

/**
 * 全部实测采集逻辑 + oracle（放在伴生对象里，测试体只做断言，便于报告逐条引用）。
 */
object DwsAdsChainExecSpec {

  // ── 夹具与命名空间（一次运行一个 namespace，绝不复用）──────────────────
  private val BusinessDate = "20260901"
  private val BusinessDate2 = "20260902"
  /** R9 D-R9-1 判别探针用：**另一个业务日期**的在飞/未发布暂存分区（不得被本次发布清理）。 */
  private val ForeignSnap = "s206-snap-foreign"
  private val ForeignDate = "20260907"
  private val BatchId = 20260901L
  private val SourceSystem = "mock-mall"
  private val Snap1 = "s206-snap-01"
  private val Snap2 = "s206-snap-02"
  private val SuiteName = "s206-dws-ads-chain"
  // 前缀不得以层后缀结尾（`WarehouseNamespace.validate` → WAREHOUSE_PREFIX_LAYER_SUFFIX），
  // 故不能叫 dw_s206_ads；r1 实测即栽在这里（见 raw/r1-RED-first-run.log L73-75）。
  private val Ns = WarehouseNamespace.of("dw_s206_chain")

  private val DwsTables = Seq(
    "dws_user_behavior_day", "dws_behavior_funnel_day", "dws_product_behavior_day",
    "dws_product_sale_day", "dws_trade_day", "dws_user_trade_period", "dws_region_sale_day")

  // ── 数据类 ────────────────────────────────────────────────────────────
  final case class Trade(orderCount: Long, buyerCount: Long, saleAmount: BigDecimal,
                         refundAmount: BigDecimal, netSaleAmount: BigDecimal,
                         avgOrderValue: Option[BigDecimal])
  final case class Behavior(userId: Long, pv: Long, fav: Long, cart: Long, search: Long,
                            activeHours: Long, buy: Long)
  final case class ProductBehavior(productId: Long, categoryId: Long, pv: Long, uv: Long, fav: Long,
                                   cart: Long, buy: Long)
  final case class ProductSale(productId: Long, categoryId: Long, saleCount: Long,
                               saleAmount: BigDecimal, buyerCount: Long)
  final case class UserPeriod(userId: Long, lastBuyDate: String, orderCount: Long,
                              saleAmount: BigDecimal, periodStart: String, periodEnd: String)
  final case class RegionSale(region: String, buyerCount: Long, orderCount: Long, saleAmount: BigDecimal)
  final case class Funnel(categoryId: Long, channel: String, viewUsers: Long, intentUsers: Long,
                          orderUsers: Long, payUsers: Long, intentRate: Option[BigDecimal],
                          orderRate: Option[BigDecimal], payRate: Option[BigDecimal],
                          overallBuyRate: Option[BigDecimal])

  final case class Dws(trade: Option[Trade], behavior: Seq[Behavior], productBehavior: Seq[ProductBehavior],
                       productSale: Seq[ProductSale], userPeriod: Seq[UserPeriod], region: Seq[RegionSale],
                       funnel: Option[Funnel])

  final case class FormalPart(table: String, catalogRowCount: Long, location: Option[String],
                              parquetRowCount: Option[Long], partitions: Seq[String], tableRowCount: Long)

  final case class Step(code: String, result: JobResult, thrown: Option[String]) {
    def status: String = if (thrown.isDefined) "THREW" else if (result == null) "NULL" else result.status
    def ok: Boolean = thrown.isEmpty && result != null && result.status == "SUCCESS"
    def desc: String = thrown match {
      case Some(t) => s"$code THREW: $t"
      case None => if (result == null) s"$code NULL" else s"$code ${result.status}: ${result.message}"
    }
  }

  final case class Capture(
    crash: Option[String],
    steps: Seq[Step],
    notes: Seq[String],
    checkLines: Seq[(String, Seq[String])],
    odsCounts: Map[String, Long],
    odsTradePartitions: Seq[(String, Long)],
    dwdBehaviorRows: Long,
    dwdOrderRows: Long,
    dwdOrderPaidRows: Long,
    dwsExists: Map[String, Boolean],
    dwsRows: Map[String, Long],
    dwsDirExists: Map[String, Boolean],
    dwsDirFiles: Map[String, Long],
    dwsDirPaths: Map[String, String],
    dwsDirEntries: Map[String, Seq[String]],
    oracle: Dws,
    measured: Dws,
    stagingRowsS1: Map[String, Long],
    adsStagingRendered: Map[String, Seq[String]],
    formalBeforePub: Map[String, FormalPart],
    formalAfterPubS1: Map[String, FormalPart],
    formalAfterPubAgain: Map[String, FormalPart],
    stagingRowsS1AfterPubAgain: Map[String, Long],
    pubAgainPointerSwitch: String,
    stagingRowsS2: Map[String, Long],
    formalAfterPubS2: Map[String, FormalPart],
    stagingRowsS1AfterPubS2: Map[String, Long],
    stagingS1PathExistsAfterPubS2: Map[String, Boolean],
    pub2PruneDetail: String,
    pub2PruneThreshold: String,
    foreignSeedRoute: String,
    foreignRowsAfterSeed: Map[String, Long],
    foreignRowsAfterPubS1: Map[String, Long],
    foreignRowsAfterPubS2: Map[String, Long],
    foreignDirExistsAfterPubS2: Map[String, Boolean],
    exportDir: String,
    exportFileExists: Map[String, Boolean],
    exportFileLines: Map[String, Long],
    manifestExists: Boolean,
    manifestRaw: String,
    manifestSnapshotId: Option[String],
    manifestDt: Option[String],
    manifestTotalRows: Option[Long],
    manifestRowCounts: Map[String, Long],
    manifestHivePaths: Map[String, String],
    manifestExportFiles: Map[String, String],
    manifestChecksums: Map[String, String]) {

    /** 第 1 次执行的该码步骤（主链那一次） */
    def step(code: String): Step =
      steps.find(_.code == code).getOrElse(Step(code, null, Some("作业未执行")))

    /** 该码第 n 次（1-based）执行 */
    def stepNth(code: String, n: Int): Step =
      steps.filter(_.code == code).lift(n - 1).getOrElse(Step(code, null, Some(s"第 $n 次未执行")))

    /** 第 1 次执行的该码作业的检查行 */
    def checks(code: String): Seq[String] =
      checkLines.find(_._1 == code).map(_._2).getOrElse(Seq.empty)

    /** 该码第 n 次执行的检查行 */
    def checksNth(code: String, n: Int): Seq[String] =
      checkLines.filter(_._1 == code).lift(n - 1).map(_._2).getOrElse(Seq.empty)
  }

  object Capture {
    def crashed(msg: String): Capture = Capture(
      crash = Some(msg), steps = Seq.empty, notes = Seq(s"采集崩溃：$msg"), checkLines = Seq.empty,
      odsCounts = Map.empty, odsTradePartitions = Seq.empty, dwdBehaviorRows = -1L, dwdOrderRows = -1L,
      dwdOrderPaidRows = -1L, dwsExists = Map.empty, dwsRows = Map.empty, dwsDirExists = Map.empty,
      dwsDirFiles = Map.empty, dwsDirPaths = Map.empty, dwsDirEntries = Map.empty,
      oracle = Dws(None, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, None),
      measured = Dws(None, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, None),
      stagingRowsS1 = Map.empty, adsStagingRendered = Map.empty, formalBeforePub = Map.empty,
      formalAfterPubS1 = Map.empty, formalAfterPubAgain = Map.empty, stagingRowsS1AfterPubAgain = Map.empty,
      pubAgainPointerSwitch = "", stagingRowsS2 = Map.empty, formalAfterPubS2 = Map.empty,
      stagingRowsS1AfterPubS2 = Map.empty, stagingS1PathExistsAfterPubS2 = Map.empty,
      pub2PruneDetail = "", pub2PruneThreshold = "", foreignSeedRoute = "",
      foreignRowsAfterSeed = Map.empty, foreignRowsAfterPubS1 = Map.empty,
      foreignRowsAfterPubS2 = Map.empty, foreignDirExistsAfterPubS2 = Map.empty,
      exportDir = "", exportFileExists = Map.empty, exportFileLines = Map.empty,
      manifestExists = false, manifestRaw = "", manifestSnapshotId = None, manifestDt = None,
      manifestTotalRows = None, manifestRowCounts = Map.empty, manifestHivePaths = Map.empty,
      manifestExportFiles = Map.empty, manifestChecksums = Map.empty)
  }

  // ── 采集主流程 ────────────────────────────────────────────────────────
  private def collectAll(spark: SparkSession): Capture = {
    val notes = ListBuffer.empty[String]
    def note(s: String): Unit = { notes += s; println(s"[s206][note] $s") }

    val warehouseDir = spark.conf.get("spark.sql.warehouse.dir")
    // 注意：conf 读回来的是 URI（`file:/D:/…`），不能直接喂 `java.nio.file.Paths.get(String)`
    // ——r2 实测 `InvalidPathException: Illegal char <:> at index 4`。
    // 作业侧统一用 Hadoop Path（认 URI），本进程的文件检查用 nio Path（认盘符），两者在此显式分开。
    val exportDir = new Path(new Path(warehouseDir), "s206-export").toString
    val exportDirNio = nioPath(exportDir)
    note(s"warehouse.dir=$warehouseDir")
    note(s"exportDir(job)=$exportDir")
    note(s"exportDir(nio)=$exportDirNio")
    note(s"namespace=${Ns.prefix} suite=$SuiteName")

    // ── sci：与既有 spec 逐字相同的建表路径（不自造 DDL 副本）──
    val ddls = LocalSchemaInitJob.statements(Ns)
    ddls.foreach { case (_, ddl) => spark.sql(ddl) }
    note(s"sci 执行 DDL 条数=${ddls.size}")

    val steps = ListBuffer.empty[Step]
    def step(code: String, snapshot: Option[String], extra: (String, String)*): Step = {
      val s = try {
        val jd = jobArgs(code, snapshot, extra: _*)
        val job: WarehouseJob = JobRegistry.lookup(code).getOrElse(
          throw new IllegalStateException(s"JobRegistry 未注册 $code"))
        val r = job.run(spark, jd)
        Step(code, r, None)
      } catch {
        case t: Throwable => Step(code, null, Some(s"${t.getClass.getName}: ${t.getMessage}"))
      }
      steps += s
      println(s"[s206] 步骤 ${s.desc}")
      if (s.result != null) {
        s.result.checks.foreach(c => println(
          s"[s206][check:$code] ${c.ruleCode}|${c.severity}|passed=${c.passed}|check=${c.checkCount}|err=${c.errorCount}|${c.detail}"))
      }
      s
    }

    // ── LOAD_ODS → BUILD_DWD → BUILD_DWS ──
    step("odl", None, "landingDir" -> P2TestSupport.goldenUri, "batchId" -> BatchId.toString)
    step("dim", None)
    step("bdw", None)
    step("tdw", None)
    step("usw", None)

    // ── BUILD_ADS：只写 S1 暂存分区 ──
    step("fna", Some(Snap1))
    val stagingRowsS1 = stagingRows(spark, Ns, Snap1, BusinessDate)
    val adsStagingRendered = AdsSql.TABLES.map { t =>
      t -> readStrings(spark,
        s"SELECT * FROM ${AdsSql.staging(Ns, t)} WHERE snapshot_id='$Snap1' AND dt='$BusinessDate'")
        .map(_.mkString("|"))
    }.toMap

    // ── 发布前：正式分区必须为空（发布隔离）──
    val formalBeforePub = formalParts(spark, Ns, BusinessDate, note)

    // ── D-R9-1 判别探针：埋一个**异业务日期**的暂存分区（模拟"另一业务日期在飞/未发布"）──
    val (foreignSeedRoute, foreignRowsAfterSeed) =
      seedForeignStaging(spark, Ns, warehouseDir, Snap1, BusinessDate, ForeignSnap, ForeignDate)
    note(s"D-R9-1 埋点（$ForeignSnap/dt=$ForeignDate）方式=$foreignSeedRoute 行数=$foreignRowsAfterSeed")

    // ── QUALITY_CHECK + PUBLISH ──
    step("dqc", Some(Snap1))
    step("pub", Some(Snap1))
    val formalAfterPubS1 = formalParts(spark, Ns, BusinessDate, note)
    val foreignRowsAfterPubS1 = foreignRows(spark, Ns, ForeignSnap, ForeignDate)

    // ── mxp 导出 ──
    step("mxp", Some(Snap1), "exportDir" -> exportDir)

    // ── 幂等：同一快照再发布一次 ──
    step("pub", Some(Snap1))
    val formalAfterPubAgain = formalParts(spark, Ns, BusinessDate, note)
    val stagingRowsS1AfterPubAgain = stagingRows(spark, Ns, Snap1, BusinessDate)

    // ── 第二个快照：看旧快照是否保留 ──
    step("fna", Some(Snap2))
    val stagingRowsS2 = stagingRows(spark, Ns, Snap2, BusinessDate)
    step("dqc", Some(Snap2))
    step("pub", Some(Snap2))
    val formalAfterPubS2 = formalParts(spark, Ns, BusinessDate, note)
    val stagingRowsS1AfterPubS2 = stagingRows(spark, Ns, Snap1, BusinessDate)
    val stagingS1PathExistsAfterPubS2 = formalAfterPubS1.flatMap {
      case (t, p) => p.location.map(l => t -> fsExists(spark, l))
    }
    val foreignRowsAfterPubS2 = foreignRows(spark, Ns, ForeignSnap, ForeignDate)
    val foreignDirExistsAfterPubS2 = AdsSql.TABLES.map { t =>
      t -> fsExists(spark, foreignStagingDir(warehouseDir, Ns, t))
    }.toMap

    // ── DWS 存在性 / 行数 / 目录 ──
    val dwsExists = DwsTables.map { t =>
      t -> Try(spark.sql(s"DESCRIBE ${Ns.dws}.$t").collect().nonEmpty).getOrElse(false)
    }.toMap
    val dwsRows = DwsTables.map { t =>
      t -> Try(one(spark, s"SELECT COUNT(*) FROM ${Ns.dws}.$t WHERE dt='$BusinessDate'")).getOrElse(-1L)
    }.toMap
    def tableDir(t: String): Path = new Path(s"$warehouseDir/${Ns.dws}.db/$t")
    val dwsDirExists = DwsTables.map { t =>
      val p = tableDir(t)
      t -> Try(p.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(p)).getOrElse(false)
    }.toMap
    // 递归计数：DWS 是 dt 分区表，parquet 文件在 `dt=<D>/` 子目录里；只看表根目录会恒得 0
    // （r3 实测 7 张全 0 → 我自己写错，非生产缺陷，见 raw/r3-real-chain-run.log L124）。
    val dwsDirFiles = DwsTables.map { t =>
      val p = tableDir(t)
      val fs = p.getFileSystem(spark.sparkContext.hadoopConfiguration)
      val it = Try(fs.listFiles(p, true)).toOption
      t -> it.map { files =>
        var n = 0L
        while (files.hasNext) if (files.next().getPath.getName.endsWith(".parquet")) n += 1L
        n
      }.getOrElse(-1L)
    }.toMap
    // 表根目录的真实条目名（主控要求：不只报计数，要把 `fs.listStatus` 的原始条目名打出来）
    val dwsDirPaths = DwsTables.map(t => t -> tableDir(t).toString).toMap
    val dwsDirEntries = DwsTables.map { t =>
      val p = tableDir(t)
      val fs = p.getFileSystem(spark.sparkContext.hadoopConfiguration)
      t -> Try(fs.listStatus(p).map(_.getPath.getName).sorted.toSeq).getOrElse(Seq.empty)
    }.toMap
    // ── DWD 行原样取回（只做投影），oracle 在本进程算 ──
    val behaviorRaw = readStrings(spark,
      s"""SELECT CAST(user_id AS STRING), behavior_type, CAST(event_hour AS STRING),
         |       CAST(product_id AS STRING), CAST(category_id AS STRING)
         |FROM ${Ns.dwd}.dwd_user_behavior_detail WHERE dt='$BusinessDate'""".stripMargin)
      .map(r => (r(0).toLong, r(1), r(2).toInt, r(3).toLong, r(4).toLong))
    val orderRaw = readStrings(spark,
      s"""SELECT CAST(order_id AS STRING), CAST(user_id AS STRING), CAST(product_id AS STRING),
         |       CAST(category_id AS STRING), CAST(quantity AS STRING), CAST(amount AS STRING),
         |       CAST(refund_amount AS STRING), CAST(final_paid_flag AS STRING),
         |       CAST(city_level AS STRING), CAST(order_date AS STRING)
         |FROM ${Ns.dwd}.dwd_order_detail WHERE dt='$BusinessDate'""".stripMargin)
      .map(r => OrderRow(r(0).toLong, r(1).toLong, r(2).toLong, r(3).toLong, r(4).toLong,
        BigDecimal(r(5)), BigDecimal(r(6)), r(7).toInt, r(8), r(9)))
    note(s"DWD 行为行=${behaviorRaw.size} 订单明细行=${orderRaw.size} 支付行=${orderRaw.count(_.paid == 1)}")

    val oracle = buildOracle(behaviorRaw, orderRaw)
    val measured = readDws(spark)

    // ── ODS 计数与分区 ──
    val odsCounts = Seq("ods_user_event", "ods_product_event", "ods_behavior_event", "ods_trade_event")
      .map(t => t -> Try(one(spark, s"SELECT COUNT(*) FROM ${Ns.ods}.$t")).getOrElse(-1L)).toMap
    val odsTradePartitions = Try(readStrings(spark,
      s"SELECT dt, CAST(COUNT(*) AS STRING) FROM ${Ns.ods}.ods_trade_event GROUP BY dt ORDER BY dt"))
      .getOrElse(Seq.empty).map(r => r(0) -> r(1).toLong)

    // ── 导出制品 ──
    val exportFiles = MetricAdsSpec.TABLES.map { spec =>
      val f = exportDirNio.resolve(s"${spec.mysqlTable}.jsonl")
      val lines = if (Files.isRegularFile(f)) {
        P2TestSupport.requireNonEmpty(f)
        countLines(f)
      } else -1L
      spec.mysqlTable -> (Files.isRegularFile(f), lines)
    }
    val exportFileExists = exportFiles.map { case (t, (e, _)) => t -> e }.toMap
    val exportFileLines = exportFiles.map { case (t, (_, n)) => t -> n }.toMap
    val manifestPath = exportDirNio.resolve(MetricAdsSpec.EXPORT_MANIFEST)
    val manifestExists = Files.isRegularFile(manifestPath)
    val manifestRaw =
      if (manifestExists) new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8) else ""
    val manifest = parseManifest(manifestRaw)

    val allCheckLines: Seq[(String, Seq[String])] = steps.toSeq.map { s =>
      val lines = if (s.result == null) Seq.empty else s.result.checks.map(c =>
        s"${c.ruleCode}|${c.severity}|passed=${c.passed}|threshold=${c.threshold}" +
          s"|check=${c.checkCount}|err=${c.errorCount}|${c.detail}")
      s.code -> lines
    }

    val pubSteps = steps.filter(_.code == "pub").toSeq
    def checkOf(s: Option[Step], rule: String): String =
      s.flatMap(x => Option(x.result)).flatMap(_.checks.find(_.ruleCode == rule)).map(_.detail).getOrElse("")
    val pubAgainPointerSwitch = checkOf(pubSteps.lift(1), "PUB_POINTER_SWITCH")
    val pub2PruneDetail = checkOf(pubSteps.lift(2), "PUB_STAGING_PRUNE")
    val pub2PruneThreshold = pubSteps.lift(2).flatMap(x => Option(x.result))
      .flatMap(_.checks.find(_.ruleCode == "PUB_STAGING_PRUNE")).map(_.threshold).getOrElse("")

    Capture(
      crash = None,
      steps = steps.toSeq,
      notes = notes.toList,
      checkLines = allCheckLines,
      odsCounts = odsCounts,
      odsTradePartitions = odsTradePartitions,
      dwdBehaviorRows = behaviorRaw.size.toLong,
      dwdOrderRows = orderRaw.size.toLong,
      dwdOrderPaidRows = orderRaw.count(_.paid == 1).toLong,
      dwsExists = dwsExists,
      dwsRows = dwsRows,
      dwsDirExists = dwsDirExists,
      dwsDirFiles = dwsDirFiles,
      dwsDirPaths = dwsDirPaths,
      dwsDirEntries = dwsDirEntries,
      oracle = oracle,
      measured = measured,
      stagingRowsS1 = stagingRowsS1,
      adsStagingRendered = adsStagingRendered,
      formalBeforePub = formalBeforePub,
      formalAfterPubS1 = formalAfterPubS1,
      formalAfterPubAgain = formalAfterPubAgain,
      stagingRowsS1AfterPubAgain = stagingRowsS1AfterPubAgain,
      pubAgainPointerSwitch = pubAgainPointerSwitch,
      stagingRowsS2 = stagingRowsS2,
      formalAfterPubS2 = formalAfterPubS2,
      stagingRowsS1AfterPubS2 = stagingRowsS1AfterPubS2,
      stagingS1PathExistsAfterPubS2 = stagingS1PathExistsAfterPubS2,
      pub2PruneDetail = pub2PruneDetail,
      pub2PruneThreshold = pub2PruneThreshold,
      foreignSeedRoute = foreignSeedRoute,
      foreignRowsAfterSeed = foreignRowsAfterSeed,
      foreignRowsAfterPubS1 = foreignRowsAfterPubS1,
      foreignRowsAfterPubS2 = foreignRowsAfterPubS2,
      foreignDirExistsAfterPubS2 = foreignDirExistsAfterPubS2,
      exportDir = exportDir,
      exportFileExists = exportFileExists,
      exportFileLines = exportFileLines,
      manifestExists = manifestExists,
      manifestRaw = manifestRaw,
      manifestSnapshotId = manifest.snapshotId,
      manifestDt = manifest.dt,
      manifestTotalRows = manifest.totalRows,
      manifestRowCounts = manifest.rowCounts,
      manifestHivePaths = manifest.hivePaths,
      manifestExportFiles = manifest.exportFiles,
      manifestChecksums = manifest.checksums)
  }

  private final case class Manifest(snapshotId: Option[String], dt: Option[String], totalRows: Option[Long],
                                    rowCounts: Map[String, Long], hivePaths: Map[String, String],
                                    exportFiles: Map[String, String], checksums: Map[String, String])

  private def parseManifest(raw: String): Manifest = {
    if (raw.trim.isEmpty) return Manifest(None, None, None, Map.empty, Map.empty, Map.empty, Map.empty)
    Try(new ObjectMapper().readTree(raw)).toOption match {
      case None => Manifest(None, None, None, Map.empty, Map.empty, Map.empty, Map.empty)
      case Some(node) =>
        def text(f: String): Option[String] = Option(node.get(f)).map(_.asText())
        val rows = ListBuffer.empty[(String, Long, String, String, String)]
        val tables = node.get("tables")
        if (tables != null && tables.isArray) {
          val it = tables.elements()
          while (it.hasNext) {
            val t = it.next()
            rows += ((Option(t.get("mysqlTable")).map(_.asText()).getOrElse(""),
              Option(t.get("rowCount")).map(_.asLong()).getOrElse(-1L),
              Option(t.get("hivePath")).map(_.asText()).getOrElse(""),
              Option(t.get("exportFile")).map(_.asText()).getOrElse(""),
              Option(t.get("checksum")).map(_.asText()).getOrElse("")))
          }
        }
        Manifest(text("snapshotId"), text("dt").orElse(text("businessDate")),
          Option(node.get("totalRows")).map(_.asLong()),
          rows.map(r => r._1 -> r._2).toMap, rows.map(r => r._1 -> r._3).toMap,
          rows.map(r => r._1 -> r._4).toMap, rows.map(r => r._1 -> r._5).toMap)
    }
  }

  private final case class OrderRow(orderId: Long, userId: Long, productId: Long, categoryId: Long,
                                    quantity: Long, amount: BigDecimal, refundAmount: BigDecimal,
                                    paid: Int, cityLevel: String, orderDate: String)

  /** 独立 oracle：只用本进程的集合运算，不复用 `DwsSql` 的聚合口径。 */
  private def buildOracle(behavior: Seq[(Long, String, Int, Long, Long)],
                          orders: Seq[OrderRow]): Dws = {
    val paid = orders.filter(_.paid == 1)

    val trade = Trade(
      orderCount = paid.map(_.orderId).distinct.size.toLong,
      buyerCount = paid.map(_.userId).distinct.size.toLong,
      saleAmount = paid.map(_.amount).sum,
      refundAmount = paid.map(_.refundAmount).sum,
      netSaleAmount = paid.map(_.amount).sum - paid.map(_.refundAmount).sum,
      avgOrderValue = if (paid.isEmpty) None
      else Some((paid.map(_.amount).sum / BigDecimal(paid.map(_.orderId).distinct.size))
        .setScale(2, BigDecimal.RoundingMode.HALF_UP)))

    val buyByUser = paid.groupBy(_.userId).map { case (u, rs) => u -> rs.map(_.quantity).sum }
    val behaviorRows = behavior.groupBy(_._1).toSeq.sortBy(_._1).map { case (u, rs) =>
      Behavior(u, rs.count(_._2 == "view"), rs.count(_._2 == "favorite"), rs.count(_._2 == "cart_add"),
        rs.count(_._2 == "search"), rs.map(_._3).distinct.size.toLong, buyByUser.getOrElse(u, 0L))
    }

    val buyByProduct = paid.groupBy(_.productId).map { case (p, rs) => p -> rs.map(_.quantity).sum }
    val productBehavior = behavior.groupBy(r => (r._4, r._5)).toSeq.sortBy(_._1._1).map { case ((p, c), rs) =>
      ProductBehavior(p, c, rs.count(_._2 == "view"),
        rs.filter(_._2 == "view").map(_._1).distinct.size.toLong,
        rs.count(_._2 == "favorite"), rs.count(_._2 == "cart_add"), buyByProduct.getOrElse(p, 0L))
    }

    val productSale = paid.groupBy(r => (r.productId, r.categoryId)).toSeq.sortBy(_._1._1).map {
      case ((p, c), rs) =>
        ProductSale(p, c, rs.map(_.quantity).sum, rs.map(_.amount).sum, rs.map(_.userId).distinct.size.toLong)
    }

    val userPeriod = paid.groupBy(_.userId).toSeq.sortBy(_._1).map { case (u, rs) =>
      UserPeriod(u, rs.map(_.orderDate).max, rs.map(_.orderId).distinct.size.toLong,
        rs.map(_.amount).sum, BusinessDate, BusinessDate)
    }

    val region = paid.groupBy(_.cityLevel).toSeq.sortBy(_._1).map { case (r, rs) =>
      RegionSale(r, rs.map(_.userId).distinct.size.toLong, rs.map(_.orderId).distinct.size.toLong,
        rs.map(_.amount).sum)
    }

    def rate(num: Long, den: Long): Option[BigDecimal] =
      if (den == 0L) None
      else Some((BigDecimal(num) / BigDecimal(den)).setScale(4, BigDecimal.RoundingMode.HALF_UP))

    val viewUsers = behavior.filter(_._2 == "view").map(_._1).distinct.size.toLong
    val intentUsers = behavior.filter(r => r._2 == "favorite" || r._2 == "cart_add").map(_._1).distinct.size.toLong
    val orderUsers = orders.map(_.userId).distinct.size.toLong
    val payUsers = paid.map(_.userId).distinct.size.toLong
    val funnel = Funnel(-1L, "all", viewUsers, intentUsers, orderUsers, payUsers,
      rate(intentUsers, viewUsers), rate(orderUsers, intentUsers), rate(payUsers, orderUsers),
      rate(payUsers, viewUsers))

    Dws(Some(trade), behaviorRows, productBehavior, productSale, userPeriod, region, Some(funnel))
  }

  /** DWS 读回（显式列序，避免 `SELECT *` 依赖 DDL 顺序）。 */
  private def readDws(spark: SparkSession): Dws = {
    val dt = BusinessDate
    val trade = readStrings(spark,
      s"""SELECT CAST(order_count AS STRING), CAST(buyer_count AS STRING), CAST(sale_amount AS STRING),
         |CAST(refund_amount AS STRING), CAST(net_sale_amount AS STRING), CAST(avg_order_value AS STRING)
         |FROM ${Ns.dws}.dws_trade_day WHERE dt='$dt'""".stripMargin)
      .headOption.map(r => Trade(r(0).toLong, r(1).toLong, BigDecimal(r(2)), BigDecimal(r(3)),
        BigDecimal(r(4)), optDec(r(5))))

    val behavior = readStrings(spark,
      s"""SELECT CAST(user_id AS STRING), CAST(pv AS STRING), CAST(fav AS STRING), CAST(cart AS STRING),
         |CAST(search AS STRING), CAST(active_hours AS STRING), CAST(buy AS STRING)
         |FROM ${Ns.dws}.dws_user_behavior_day WHERE dt='$dt' ORDER BY user_id""".stripMargin)
      .map(r => Behavior(r(0).toLong, r(1).toLong, r(2).toLong, r(3).toLong, r(4).toLong,
        r(5).toLong, r(6).toLong))

    val productBehavior = readStrings(spark,
      s"""SELECT CAST(product_id AS STRING), CAST(category_id AS STRING), CAST(pv AS STRING),
         |CAST(uv AS STRING), CAST(fav AS STRING), CAST(cart AS STRING), CAST(buy AS STRING)
         |FROM ${Ns.dws}.dws_product_behavior_day WHERE dt='$dt' ORDER BY product_id""".stripMargin)
      .map(r => ProductBehavior(r(0).toLong, r(1).toLong, r(2).toLong, r(3).toLong, r(4).toLong,
        r(5).toLong, r(6).toLong))

    val productSale = readStrings(spark,
      s"""SELECT CAST(product_id AS STRING), CAST(category_id AS STRING), CAST(sale_count AS STRING),
         |CAST(sale_amount AS STRING), CAST(buyer_count AS STRING)
         |FROM ${Ns.dws}.dws_product_sale_day WHERE dt='$dt' ORDER BY product_id""".stripMargin)
      .map(r => ProductSale(r(0).toLong, r(1).toLong, r(2).toLong, BigDecimal(r(3)), r(4).toLong))

    val userPeriod = readStrings(spark,
      s"""SELECT CAST(user_id AS STRING), last_buy_date, CAST(order_count AS STRING),
         |CAST(sale_amount AS STRING), period_start, period_end
         |FROM ${Ns.dws}.dws_user_trade_period WHERE dt='$dt' ORDER BY user_id""".stripMargin)
      .map(r => UserPeriod(r(0).toLong, r(1), r(2).toLong, BigDecimal(r(3)), r(4), r(5)))

    val region = readStrings(spark,
      s"""SELECT region, CAST(buyer_count AS STRING), CAST(order_count AS STRING), CAST(sale_amount AS STRING)
         |FROM ${Ns.dws}.dws_region_sale_day WHERE dt='$dt' ORDER BY region""".stripMargin)
      .map(r => RegionSale(r(0), r(1).toLong, r(2).toLong, BigDecimal(r(3))))

    val funnel = readStrings(spark,
      s"""SELECT CAST(category_id AS STRING), channel, CAST(view_users AS STRING),
         |CAST(intent_users AS STRING), CAST(order_users AS STRING), CAST(pay_users AS STRING),
         |CAST(intent_rate AS STRING), CAST(order_rate AS STRING), CAST(pay_rate AS STRING),
         |CAST(overall_buy_rate AS STRING)
         |FROM ${Ns.dws}.dws_behavior_funnel_day WHERE dt='$dt'""".stripMargin)
      .headOption.map(r => Funnel(r(0).toLong, r(1), r(2).toLong, r(3).toLong, r(4).toLong, r(5).toLong,
        optDec(r(6)), optDec(r(7)), optDec(r(8)), optDec(r(9))))

    Dws(trade, behavior, productBehavior, productSale, userPeriod, region, funnel)
  }

  private def jobArgs(code: String, snapshot: Option[String], extra: (String, String)*): JobArgs = {
    val base = Seq("--runtimeProfileId=1", s"--jobCode=$code", s"--businessDate=$BusinessDate",
      "--attemptNo=1", s"--hiveDatabasePrefix=${Ns.prefix}", s"--sourceSystem=$SourceSystem",
      s"--landingDir=${P2TestSupport.goldenUri}", s"--batchId=$BatchId")
    val all = base ++ snapshot.map(s => s"--outputSnapshotId=$s") ++ extra.map { case (k, v) => s"--$k=$v" }
    JobArgs.parse(all.toArray) match {
      case Right(a) => a
      case Left(e) => throw new IllegalArgumentException(s"$code 参数构造失败: $e（${all.mkString(" ")}）")
    }
  }

  private def stagingRows(spark: SparkSession, ns: WarehouseNamespace, sid: String, dt: String): Map[String, Long] =
    AdsSql.TABLES.map { t =>
      t -> Try(one(spark, s"SELECT COUNT(*) FROM ${AdsSql.staging(ns, t)} WHERE snapshot_id='$sid' AND dt='$dt'"))
        .getOrElse(-1L)
    }.toMap

  /** 异业务日期暂存分区在仓库里的物理目录（`<warehouse>/<ads库>.db/<table>__staging/snapshot_id=…/dt=…`） */
  private def foreignStagingDir(warehouseDir: String, ns: WarehouseNamespace, table: String): String = {
    val stg = AdsSql.staging(ns, table)
    val i = stg.indexOf('.')
    s"$warehouseDir/${stg.substring(0, i)}.db/${stg.substring(i + 1)}" +
      s"/snapshot_id=$ForeignSnap/dt=$ForeignDate"
  }

  private def foreignRows(spark: SparkSession, ns: WarehouseNamespace, sid: String, dt: String): Map[String, Long] =
    stagingRows(spark, ns, sid, dt)

  /**
   * D-R9-1 判别探针埋点：把本快照的 1 行**原样复制**成一个异业务日期（`dt=ForeignDate`）的暂存分区，
   * 模拟"另一业务日期在飞/未发布"。列清单取 `MetricAdsSpec.columns`（与 ADS 暂存 DDL 的非分区列
   * 逐列同序，已核对 LocalSchemaInitJob L174-215 八张表的列数与顺序）。
   *
   * 两条写法：优先 `INSERT INTO … PARTITION(…)`（不能对"正在被读的同一张表"做 `INSERT OVERWRITE`，
   * r5 实测 `UNSUPPORTED_OVERWRITE.TABLE` —— 我的埋点写法问题，非产品问题）；回退为
   * 「物理写目录 + `ADD PARTITION … LOCATION`」——与 pub 切换正式分区指针用的是同一套 Hive 元数据协议。
   *
   * @return (逐表执行方式与异常, 逐表埋点后行数)
   */
  private def seedForeignStaging(spark: SparkSession, ns: WarehouseNamespace, warehouseDir: String,
                                 srcSid: String, srcDt: String,
                                 foreignSid: String, foreignDt: String): (String, Map[String, Long]) = {
    val byName = MetricAdsSpec.TABLES.map(t => t.table -> t).toMap
    val routes = AdsSql.TABLES.map { t =>
      val stg = AdsSql.staging(ns, t)
      val cols = byName.get(t).map(_.columns).getOrElse(Seq("*"))
      val sql =
        s"""INSERT INTO TABLE $stg PARTITION(snapshot_id = '$foreignSid', dt = '$foreignDt')
           |SELECT ${cols.mkString(", ")} FROM $stg
           |WHERE snapshot_id='$srcSid' AND dt='$srcDt' LIMIT 1""".stripMargin
      Try(spark.sql(sql)).map(_ => s"$t=INSERT_INTO_PARTITION").recoverWith { case e1 =>
        val loc = foreignStagingDir(warehouseDir, ns, t)
        val df = spark.read.table(stg)
          .where(s"snapshot_id='$srcSid' AND dt='$srcDt'")
          .select(cols.head, cols.tail: _*)
          .limit(1)
        Try {
          df.write.mode("overwrite").parquet(loc)
          spark.sql(s"ALTER TABLE $stg ADD IF NOT EXISTS " +
            s"PARTITION (snapshot_id = '$foreignSid', dt = '$foreignDt') LOCATION '$loc'")
        }.map(_ => s"$t=PARQUET_WRITE+ADD_PARTITION_LOCATION(${e1.getClass.getSimpleName})")
          .recover { case e2: Throwable => s"$t=THREW(${e2.getClass.getSimpleName}: ${e2.getMessage})" }
      }.get
    }
    (routes.mkString("; "), foreignRows(spark, ns, foreignSid, foreignDt))
  }

  private def formalParts(spark: SparkSession, ns: WarehouseNamespace, dt: String,
                          note: String => Unit): Map[String, FormalPart] =
    AdsSql.TABLES.map { t =>
      val f = AdsSql.formal(ns, t)
      val specs = Try(spark.sql(s"SHOW PARTITIONS $f").collect().map(_.getString(0)).toSeq.sorted)
        .getOrElse(Seq.empty)
      val ev = Try(PartitionEvidence.collect(spark, Seq(f), None, Some(dt)).headOption).recover {
        case t2: Throwable =>
          note(s"PartitionEvidence.collect($f, dt=$dt) 抛出：${t2.getClass.getName}: ${t2.getMessage}")
          None
      }.get
      val loc = ev.flatMap(_.path)
      val tableRows = Try(one(spark, s"SELECT COUNT(*) FROM $f WHERE dt='$dt'")).getOrElse(-1L)
      val pq = loc.map { p =>
        Try(spark.read.parquet(p).count()).recover {
          case t2: Throwable =>
            note(s"直读 parquet $p 失败：${t2.getClass.getName}: ${t2.getMessage}")
            -1L
        }.get
      }
      t -> FormalPart(f, ev.map(_.rowCount).getOrElse(-1L), loc, pq,
        specs.filter(_.contains(s"dt=$dt")), tableRows)
    }.toMap

  private def one(spark: SparkSession, sql: String): Long = spark.sql(sql).collect()(0).getLong(0)

  private def readStrings(spark: SparkSession, sql: String): Seq[Seq[String]] =
    spark.sql(sql).collect().toSeq.map(_.toSeq.map(v => if (v == null) "<null>" else v.toString))

  private def optDec(s: String): Option[BigDecimal] = if (s == "<null>") None else Some(BigDecimal(s))

  /** 清单里的路径可能是 `file:` URI（本进程给作业的就是 URI）→ nio 需要先转；已是盘符正斜杠形式则直接用。 */
  private def nioPath(s: String): java.nio.file.Path =
    if (s.startsWith("file:")) Paths.get(java.net.URI.create(s)) else Paths.get(s)

  private def fsExists(spark: SparkSession, path: String): Boolean = Try {
    val p = new Path(path)
    p.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(p)
  }.getOrElse(false)

  private def countLines(p: java.nio.file.Path): Long = {
    val r = Files.newBufferedReader(p, StandardCharsets.UTF_8)
    try {
      var n = 0L
      while (r.readLine() != null) n += 1L
      n
    } finally r.close()
  }
}
