package com.graduation.analytics

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-15：测试夹具**手写** `INSERT … SELECT` 的「第二所有者」静态守卫。
 *
 * 关闭 `docs/PROJECT_STATUS.md` 里 `DDL 加列类变更存在「第二所有者」盲区` 条目**仍开放**的 (a) 一半：
 * `DwsSchemaOwnerSpec`（S3-11）/ `DwdDimSchemaOwnerSpec`（S3-13）/ `AdsSchemaOwnerSpec`（S3-14）
 * 只覆盖 **main 源码的生产写入投影**；测试夹具**自己手写**的 `INSERT OVERWRITE/INTO … SELECT`
 * 此前无人看守。
 *
 * 为什么这是真缺陷类（实测，非推测）：S3-03 给 DWS 加列后，两个既有套件手写的
 * `INSERT OVERWRITE … dws_user_trade_period` 与所有者列数不再对齐 ⇒ **全量档 5 条红**，
 * 而「定向跑本任务套件」完全看不见（新套件 6/6 绿）。Spark 按**位置**写 Parquet，
 * 夹具 SELECT 列表位置错了不会报错，只会**静默串列**。
 *
 * 每条手写写入都要与**唯一所有者**（`LocalSchemaInitJob.statements` 的全部 `CREATE TABLE`）对齐：
 *   1. 目标表可解析（`${ns.<层>}.<表>` / `${AdsSql.staging(ns, "x")}` / 同文件内的目标 val-def）；
 *   2. 投影**项数** == 所有者数据列数 ＋ 动态分区列数；
 *   3. 投影里**有名字的**项（裸列引用 / 带 `AS 别名`）必须与所有者**同位列名**一致；
 *   4. `PARTITION(…)` 的列名与所有者分区列**同名同序**，静态分区值必须是字符串字面量。
 *
 * 归因边界（不得越界表述）：本类只判**表形**（目标表 / 列数 / 列名 / 列序 / 分区子句），
 * **不判**口径、**不判**指标值、**不判**运行期物理落盘（本地链与真 Hive 不等价 —— 见
 * `P2TestSupport` 自陈的测试域）。「夹具与所有者同形」**推不出**「夹具数据正确」。
 *
 * 只认**大写** `INSERT OVERWRITE/INTO`（本仓库测试夹具的书写约定）：小写写法不会被扫到，
 * 实测小写仅出现在 `toLowerCase` 的断言期望串里（`OdsV2SqlContractSpec`），不是真写入。
 *
 * 静态不可判的写法必须**显式登记**（`RegisteredUnresolvedTargets`）并写清原因。
 */
class FixtureWriteShapeSpec extends AnyFlatSpec with Matchers {

  private val ns = WarehouseNamespace.defaultNamespace
  private val testRootRelative = "spark-jobs/src/test/scala"

  // ── 已登记例外与清单冻结（每一条都必须写清为什么）───────────────────────

  /**
   * 目标表**静态不可判**的写入：键 = `文件名 :: 目标 token`。
   *
   * 只有**真的**静态不可判才配登记：目标写在 Scala 变量里、且该变量的取值依赖运行期循环变量。
   */
  private val RegisteredUnresolvedTargets: Map[String, String] = Map(
    "DwsAdsChainExecSpec.scala :: $stg" ->
      "目标写在局部 val（`val stg = AdsSql.staging(ns, t)`，`t` 取自 `AdsSql.TABLES` 循环），静态不可判"
  )

  /**
   * 手写写入点**清单**（文件名 → 该文件的手写写入条数）：**有意冻结**。
   *
   * 它同时管住「静默新增」和「静默删除」：新增一个夹具写入点、或某处写入被删掉，都必须在这里
   * 显式落地 —— 逼一次「这条写入确实该存在／该消失」的判断（同 S3-11/S3-13/S3-14 `Frozen` 的思路）。
   */
  private val FrozenWriteCounts: Map[String, Int] = Map(
    "AdsCartRateSpec.scala" -> 2,
    "AdsFavCartCountSpec.scala" -> 2,
    "AdsFunnelRateReconcileSpec.scala" -> 2,
    // S3-22：规则 8「ADS 大盘同归属口径不变量」的行为 spec。三条写入点 =
    // `dwd_user_behavior_detail`（大盘 pv/uv/dau 的来源）+ `dwd_order_detail`（GMV/净销售来源）
    // + `ads_operation_overview__staging`（制造违反形态的目标行，逐列命名的静态投影）。
    "AdsGmvNetSaleInvariantSpec.scala" -> 3,
    // S3-23：规则 9「ADS 大盘同过滤条件不变量 UV≤PV」的行为 spec。同样三条写入点 =
    // `dwd_user_behavior_detail`（pv/uv 来源，浏览序列构造判别力）+ `dwd_order_detail`
    // （大盘 `t`/`r`/`u` 三个 DWS 子查询非空，否则大盘一行都不产出）+ 
    // `ads_operation_overview__staging`（制造违反/边界形态的目标行，逐列命名的静态投影）。
    "AdsUvPvInvariantSpec.scala" -> 3,
    // S3-25：DWS 站点「逐商品同过滤条件不变量 UV≤PV」的行为 spec。同样三条写入点 =
    // `dwd_user_behavior_detail`（pv/uv 来源，`DwsSql.productBehaviorDay` 的判据列）+
    // `dwd_order_detail`（该 SQL 的 `buy` 来源，LEFT JOIN 的右表非空才算真跑链路）+
    // `dws_product_behavior_day`（制造违反/边界/NULL 形态的目标行，逐列命名的静态投影）。
    "DwsUvPvInvariantSpec.scala" -> 3,
    "AdsHotProductHeatRuleVersionSpec.scala" -> 1,
    "AdsQualityRuleVersionSpec.scala" -> 3,
    "AdsRepeatRateSpec.scala" -> 2,
    "AdsRfmRawValueSpec.scala" -> 2,
    "AdsSaleTrendNetSaleSpec.scala" -> 3,
    "AdsStableOrderSpec.scala" -> 2,
    "DwsAdsChainExecSpec.scala" -> 1,
    "DwsRegionNetSaleSpec.scala" -> 1,
    "SurrogateKeySpec.scala" -> 1
  )

  /**
   * 排除在扫描外的**白盒自检**文件：守卫族自己的负例 SQL 就写在字符串字面量里，
   * 它们**本来就是错的**（把 `dt` 挪位、把列序对调），扫进来必然红。
   *
   * 清单写死，且 A5 用例双向核对：每个文件必须存在、必须**确实含有** `INSERT` 字面量
   * （证明排除「有货」，不是拿来掩盖真夹具）、文件名必须属于守卫族命名
   * （防止有人拿这个清单把普通套件排除掉）。
   */
  private val SelfCheckFiles: Set[String] = Set(
    "AdsSchemaOwnerSpec.scala",
    "DwdDimSchemaOwnerSpec.scala",
    "FixtureWriteShapeSpec.scala")

  // ── 夹具与解析工具 ──────────────────────────────────────────────────────

  /** 一条测试夹具手写的写入语句（静态形态）；`table` 在不可解时就等于原始 token */
  private case class FixtureWrite(file: String, line: Int, target: String, table: String,
                                  resolved: Boolean, partitions: Seq[(String, String)],
                                  projection: Seq[String])

  private def scalaFilesUnder(relative: String): Seq[Path] = {
    val stream = Files.walk(P2TestSupport.repoRoot.resolve(relative))
    try {
      stream.toArray.map(_.asInstanceOf[Path])
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
        .toSeq
        .sortBy(_.toString)
    } finally stream.close()
  }

  /**
   * 把块注释与行注释**原位抹成空格**（换行保留），于是扫描得到的偏移／行号与真实文件**一一对应**
   * —— 失败信息里的 `文件:行` 必须是维护者能直接跳过去的行（RED 首轮实测：删除式剥离让行号整体漂移）。
   *
   * 局限（如实声明）：不识别字符串字面量里的 `//`（本仓库测试树没有这种写法）；若将来出现，
   * 本守卫**偏严**（把后半行当注释抹掉 ⇒ 可能漏报该条，但绝不会把注释当语句报错）。
   * 反向证据见 A6：`DwsSchemaOwnerSpec` 的 `INSERT` 只出现在注释里，必须扫出 **0** 条。
   */
  private def stripComments(text: String): String = {
    val chars = text.toCharArray
    var i = 0
    while (i < chars.length) {
      if (i + 1 < chars.length && chars(i) == '/' && chars(i + 1) == '*') {
        val at = text.indexOf("*/", i + 2)
        val stop = if (at < 0) chars.length else at + 2
        var k = i
        while (k < stop) {
          if (chars(k) != '\n') chars(k) = ' '
          k += 1
        }
        i = stop
      } else if (i + 1 < chars.length && chars(i) == '/' && chars(i + 1) == '/') {
        var k = i
        while (k < chars.length && chars(k) != '\n') {
          chars(k) = ' '
          k += 1
        }
        i = k
      } else {
        i += 1
      }
    }
    new String(chars)
  }

  /** `)` 配对（识别引号字面量与嵌套括号） */
  private def matchingParen(text: String, open: Int): Int = {
    var depth = 0
    var i = open
    var quote: Char = 0
    while (i < text.length) {
      val c = text.charAt(i)
      if (quote != 0) {
        if (c == quote) quote = 0
      } else if (c == '\'' || c == '"') {
        quote = c
      } else if (c == '(') {
        depth += 1
      } else if (c == ')') {
        depth -= 1
        if (depth == 0) return i
      }
      i += 1
    }
    -1
  }

  /** 顶层逗号切分（识别引号字面量与括号深度） */
  private def splitTopLevel(text: String): Seq[String] = {
    val items = scala.collection.mutable.ArrayBuffer.empty[String]
    val cur = new StringBuilder
    var depth = 0
    var quote: Char = 0
    text.foreach { c =>
      if (quote != 0) {
        cur.append(c)
        if (c == quote) quote = 0
      } else if (c == '\'' || c == '"') {
        quote = c
        cur.append(c)
      } else if (c == '(') {
        depth += 1
        cur.append(c)
      } else if (c == ')') {
        depth -= 1
        cur.append(c)
      } else if (c == ',' && depth == 0) {
        items += cur.toString
        cur.clear()
      } else {
        cur.append(c)
      }
    }
    items += cur.toString
    items.map(normalizeItem).filter(_.nonEmpty).toSeq
  }

  /**
   * 夹具 SQL 写在 `s"""…""".stripMargin` 里：剥掉每行前导空白与边距符 `|`，再把跨行项拼成一行。
   * 不做这一步，多行 SELECT 列表的第 2..n 项会带上 `|` 前缀 ⇒ 被误判成"表达式"而漏掉列名核对。
   */
  private def normalizeItem(item: String): String =
    item.linesIterator.map(_.trim.stripPrefix("|").trim).filter(_.nonEmpty).mkString(" ")

  /** 从 `from` 起找顶层关键字（词边界、非引号内、括号深度 0） */
  private def topLevelKeyword(text: String, from: Int, keyword: String): Int = {
    var depth = 0
    var i = from
    var quote: Char = 0
    while (i < text.length) {
      val c = text.charAt(i)
      if (quote != 0) {
        if (c == quote) quote = 0
        i += 1
      } else if (c == '\'' || c == '"') {
        quote = c
        i += 1
      } else if (c == '(') {
        depth += 1
        i += 1
      } else if (c == ')') {
        depth -= 1
        i += 1
      } else if (depth == 0 && text.regionMatches(true, i, keyword, 0, keyword.length) &&
        (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) &&
        (i + keyword.length >= text.length ||
          !Character.isLetterOrDigit(text.charAt(i + keyword.length)))) {
        return i
      } else {
        i += 1
      }
    }
    -1
  }

  /**
   * 跳过空白与 `stripMargin` 的边距符 `|`。
   *
   * 夹具 SQL 写在 `s"""…""".stripMargin` 里，关键字之间会隔着「换行 ＋ 边距符」（如 `\n|SELECT`）；
   * 不跳边距符，扫描器一条都认不出来（RED 实测的首轮**夹具缺陷**：`手写写入点=0`）。
   */
  private def skipSeparators(text: String, from: Int): Int = {
    var i = from
    while (i < text.length && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == '|')) i += 1
    i
  }

  private val insertHeadRe = """INSERT\s+(?:OVERWRITE|INTO)\s+(?:TABLE\s+)?""".r

  /** 同一文件里 `val/def <名> = AdsSql.staging(formal)(ns, "<表>")` —— 静态可解的目标变量 */
  private val targetAliasRe =
    """(?:val|def)\s+([A-Za-z_]\w*)\s*=\s*AdsSql\.(staging|formal)\(ns,\s*"([a-z0-9_]+)"\)""".r

  private val nsTargetRe = """^\$\{ns\.[a-z]+\}\.([a-z0-9_]+)$""".r
  private val adsTargetRe = """^\$\{AdsSql\.(staging|formal)\(ns,\s*"([a-z0-9_]+)"\)\}$""".r
  private val varTargetRe = """^\$\{?([A-Za-z_]\w*)\}?$""".r

  private def resolveTarget(code: String, target: String): Option[String] = {
    val locals = targetAliasRe.findAllMatchIn(code).map { m =>
      val suffix = if (m.group(2) == "staging") "__staging" else ""
      m.group(1) -> (m.group(3) + suffix)
    }.toMap
    target.trim match {
      case nsTargetRe(name)        => Some(name)
      case adsTargetRe(kind, name) => Some(if (kind == "staging") name + "__staging" else name)
      case varTargetRe(name)       => locals.get(name)
      case _                       => None
    }
  }

  /** 目标 token → (token, token 结束位置)：`${AdsSql.…(…)}` 含空格，必须按括号配对读 */
  private def readTargetToken(code: String, from: Int): Option[(String, Int)] = {
    val i = skipSeparators(code, from)
    if (i >= code.length) None
    else if (code.startsWith("${AdsSql.", i)) {
      val open = code.indexOf('(', i)
      val close = if (open < 0) -1 else matchingParen(code, open)
      if (close < 0) None
      else {
        // 目标形态是 `${AdsSql.staging(ns, "x")}`：括号配平只到 `)`，尾部的 `}` 必须一并吃掉，
        // 否则后面读 PARTITION/SELECT 时会先撞上 `}` 而整条丢弃（RED 实测的第二轮夹具缺陷）。
        val end = if (close + 1 < code.length && code.charAt(close + 1) == '}') close + 2 else close + 1
        Some((code.substring(i, end).trim, end))
      }
    } else {
      val end = (i until code.length).find(k => Character.isWhitespace(code.charAt(k))).getOrElse(code.length)
      Some((code.substring(i, end).trim, end))
    }
  }

  /** 可选 `PARTITION(…)` → (列 → 值文本, 新位置)；无分区子句时返回 (空, 原位置) */
  private def readPartitionClause(code: String, from: Int): Option[(Seq[(String, String)], Int)] = {
    val j = skipSeparators(code, from)
    if (!code.regionMatches(true, j, "PARTITION", 0, 9)) Some(Seq.empty[(String, String)] -> j)
    else {
      val open = code.indexOf('(', j)
      val close = if (open < 0) -1 else matchingParen(code, open)
      if (close < 0) None
      else {
        val items = splitTopLevel(code.substring(open + 1, close)).map { item =>
          val eq = item.indexOf('=')
          if (eq < 0) item.trim -> "" else item.substring(0, eq).trim -> item.substring(eq + 1).trim
        }
        Some(items -> (close + 1))
      }
    }
  }

  /**
   * `SELECT <投影>`：到顶层 `FROM` 或到 SQL 字面量结束（`INSERT … SELECT <字面量>` 没有 FROM）。
   * 必须以 `"""` 封口为硬边界，否则没有 FROM 的语句会把**后面整个文件**当成投影。
   */
  private def readProjection(code: String, from: Int): Option[Seq[String]] = {
    val j = skipSeparators(code, from)
    if (!code.regionMatches(true, j, "SELECT", 0, 6)) None
    else {
      val literalEnd = code.indexOf("\"\"\"", j)
      val hardEnd = if (literalEnd < 0) code.length else literalEnd
      val fromAt = topLevelKeyword(code, j + 6, "FROM")
      val end = if (fromAt < 0 || fromAt > hardEnd) hardEnd else fromAt
      Some(splitTopLevel(code.substring(j + 6, end)))
    }
  }

  private def scanFile(relative: String, fileText: String): Seq[FixtureWrite] = {
    val code = stripComments(fileText)
    val file = relative.replace('\\', '/').split('/').last
    insertHeadRe.findAllMatchIn(code).flatMap { m =>
      for {
        targetAndPos <- readTargetToken(code, m.end)
        partAndPos <- readPartitionClause(code, targetAndPos._2)
        projection <- readProjection(code, partAndPos._2)
      } yield {
        val target = targetAndPos._1
        val resolved = resolveTarget(code, target)
        FixtureWrite(file, code.substring(0, m.start).count(_ == '\n') + 1, target,
          resolved.getOrElse(target), resolved.isDefined, partAndPos._1, projection)
      }
    }.toSeq
  }

  private lazy val scannedFiles: Seq[(String, String)] =
    scalaFilesUnder(testRootRelative).map { p =>
      val full = p.toString.replace('\\', '/')
      val relative = full.substring(full.indexOf(testRootRelative))
      relative -> new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    }

  private lazy val writes: Seq[FixtureWrite] = scannedFiles.flatMap { case (rel, text) =>
    if (SelfCheckFiles.contains(rel.split('/').last)) Seq.empty else scanFile(rel, text)
  }

  private def writeKey(w: FixtureWrite): String = s"${w.file} :: ${w.target}"

  // ── 唯一所有者（全层）───────────────────────────────────────────────────

  /**
   * `LocalSchemaInitJob` 声明的**全部**所有者建表语句（ODS/DWD/DIM/DWS/ADS 一起）。
   *
   * 必须按 `CREATE TABLE` 过滤：同名空间下还有 `CREATE DATABASE …`（S3-11 RED 首轮的夹具缺陷）。
   */
  private lazy val ownerStatements: Seq[String] = {
    val all = LocalSchemaInitJob.statements(ns).map(_._2).filter(_.contains("CREATE TABLE"))
    all.size should be > 0
    all
  }

  private lazy val owner: StaticOdsDdl.Parsed = StaticOdsDdl.parse(ownerStatements.mkString("\n;\n"))

  /** 写入投影 ↔ 所有者：列数、同位列名、分区子句三对齐（A3/A4/A7 共用的唯一判定点） */
  private def checkProjection(w: FixtureWrite): Unit = {
    val clue = s"${w.file}:${w.line} → ${w.table}（目标 ${w.target}）: "
    val cols = withClue(clue) {
      owner.columns.getOrElse(w.table,
        throw new IllegalArgumentException(
          s"${w.table} 不在唯一所有者（LocalSchemaInitJob）名下 —— 夹具写了没人建的表"))
    }.map(_._1)
    val ownerParts = owner.partitions(w.table).map(_._1)
    withClue(clue) {
      w.partitions.map(_._1) should be(ownerParts)
    }
    w.partitions.foreach { case (name, value) =>
      if (value.nonEmpty) {
        withClue(s"$clue 静态分区 $name 的值应为字符串字面量: ") {
          value should fullyMatch regex """'[^']*'"""
        }
      }
    }
    val dynamic = w.partitions.filter(_._2.isEmpty).map(_._1)
    withClue(clue) {
      w.projection.size should be(cols.size + dynamic.size)
    }
    val expected = cols ++ dynamic
    w.projection.zipWithIndex.foreach { case (item, idx) =>
      nameOfItem(item).foreach { name =>
        withClue(s"$clue 第 ${idx + 1} 项 `$item` 与所有者同位列名不一致: ") {
          name should be(expected(idx))
        }
      }
    }
  }

  /** 投影项的名字（裸列引用 / 带 `AS 别名`）；表达式（`CAST(…)`、字面量、`${…}`）返回 None */
  private val namedItemRe = """^(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)(?:\s+AS\s+([A-Za-z_]\w*))?$""".r

  private def nameOfItem(item: String): Option[String] = item.trim match {
    case namedItemRe(col, alias) => Some(Option(alias).getOrElse(col))
    case _                       => None
  }

  // ── 1. 扫描面与清单 ─────────────────────────────────────────────────────

  it should "A1 扫描面非空，且手写写入点清单与冻结值逐文件一致（新增/删除写入点都必须显式落地）" in {
    println(s"[S3-15] 扫描文件数=${scannedFiles.size}（排除自检文件 ${SelfCheckFiles.size} 个），手写写入点=${writes.size}")
    writes.foreach { w =>
      println(s"[S3-15]   ${w.file}:${w.line} → ${w.target}（解析=${w.table}，" +
        s"分区=${w.partitions.map(_._1).mkString("+")}）投影=${w.projection.size} 项")
    }
    scannedFiles.size should be > 0
    writes.size should be > 0
    val byFile = writes.groupBy(_.file).map { case (f, ws) => f -> ws.size }
    byFile should be(FrozenWriteCounts)
    // 清单与例外表都以裸文件名为键 ⇒ 扫描面里不允许出现同名文件
    writes.map(_.file).distinct.size should be(byFile.size)
  }

  // ── 2. 目标表可解 ↔ 已登记例外（双向）──────────────────────────────────

  it should "A2 目标表静态不可判的写入必须恰好在 RegisteredUnresolvedTargets 里，且登记条目不得失效" in {
    val unresolved = writes.filterNot(_.resolved)
    val keys = unresolved.map(writeKey).distinct.toSet
    withClue(s"实际不可判=$keys；已登记=${RegisteredUnresolvedTargets.keySet}；") {
      keys should be(RegisteredUnresolvedTargets.keySet)
    }
    RegisteredUnresolvedTargets.values.foreach { reason =>
      reason.trim should not be empty
    }
  }

  // ── 3. 投影 ↔ 所有者 ────────────────────────────────────────────────────

  it should "A3 每条可判写入的投影项数与所有者列数一致，且命名项与所有者同位列名一致" in {
    val checkable = writes.filter(_.resolved)
    checkable.size should be(writes.size - RegisteredUnresolvedTargets.size)
    checkable.foreach(checkProjection)
    // 反「装饰性守卫」：只有当投影里的**命名项**占足够比例，上面的同位列名核对才不是空转。
    // 表达式项（`CAST(…)`、字面量、`${…}`）只参与项数核对 —— 这一比值就是「参与列名核对的覆盖面」。
    val total = checkable.map(_.projection.size).sum
    val named = checkable.map(_.projection.count(nameOfItem(_).isDefined)).sum
    println(s"[S3-15] 投影项合计=$total，其中命名项=$named（参与同位列名核对）")
    withClue(s"命名项占比过低（$named/$total），列名核对形同虚设: ") {
      named.toDouble / total should be > 0.7
    }
  }

  // ── 4. 分区子句 ─────────────────────────────────────────────────────────

  it should "A4 每条可判写入的 PARTITION 列名与所有者同名同序，静态值必须是字符串字面量" in {
    writes.filter(_.resolved).foreach { w =>
      w.partitions should not be empty
      checkProjection(w)
    }
  }

  // ── 5. 自检：排除清单有效 ───────────────────────────────────────────────

  it should "A5 自检：排除清单里的文件都存在、确实含 INSERT 字面量，且名字属于守卫族" in {
    val familyRe = """.*(?:SchemaOwnerSpec|FixtureWriteShapeSpec)\.scala""".r
    SelfCheckFiles.foreach { name =>
      withClue(s"$name: ") {
        name should fullyMatch regex familyRe
        val hits = scannedFiles.filter(_._1.endsWith("/" + name)).map(_._2)
        hits.size should be(1)
        hits.head.contains("INSERT") should be(true)
      }
    }
    SelfCheckFiles.size should be(3)
  }

  // ── 6. 自检：注释剥离 ───────────────────────────────────────────────────

  it should "A6 自检：注释里的 INSERT 不算写入点（DwsSchemaOwnerSpec 的 INSERT 只在注释中 ⇒ 扫出 0 条）" in {
    val name = "DwsSchemaOwnerSpec.scala"
    val hits = scannedFiles.filter(_._1.endsWith("/" + name)).map(_._2)
    hits.size should be(1)
    hits.head should include("INSERT OVERWRITE")
    scanFile("x/" + name, hits.head) should be(empty)
  }

  // ── 7. 自检：负例必须红 ─────────────────────────────────────────────────

  it should "A7 自检：把真实夹具的投影对调两位、或删掉一项，同一判定必须红（守卫不是装饰）" in {
    val real = writes.filter(w => w.table == "dwd_user_behavior_detail" && w.projection.size > 3).head
    noException should be thrownBy checkProjection(real)
    val swapped = real.copy(
      projection = real.projection.updated(0, real.projection(1)).updated(1, real.projection(0)))
    an[TestFailedException] should be thrownBy checkProjection(swapped)
    val dropped = real.copy(projection = real.projection.dropRight(1))
    an[TestFailedException] should be thrownBy checkProjection(dropped)
  }

  // ── 8. 所有者自检 ───────────────────────────────────────────────────────

  it should "A8 所有者裸表名唯一（无重名），且覆盖各层（表数 大于 24）" in {
    val names = owner.columns.keySet
    println(s"[S3-15] 所有者建表语句=${ownerStatements.size}，裸表名=${names.size}")
    names.size should be > 24
    ownerStatements.size should be(names.size)
  }

  // ── 9. 覆盖面 ───────────────────────────────────────────────────────────

  it should "A9 覆盖面：可判写入条数 = 总条数 − 已登记不可判条数（不存在被悄悄跳过的写入）" in {
    val checked = writes.filter(_.resolved)
    checked.size + RegisteredUnresolvedTargets.size should be(writes.size)
    RegisteredUnresolvedTargets.size should be(1)
  }
}
