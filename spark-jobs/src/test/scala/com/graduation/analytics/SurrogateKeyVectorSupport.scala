package com.graduation.analytics

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import com.graduation.analytics.sql.SurrogateKey

import scala.collection.JavaConverters._

/**
 * 契约向量夹具（P2-03）：**只从契约文件读**，绝不在测试里内嵌期望值。
 *
 * 语义唯一所有者 = `contract-specs/specs/surrogate-key.v1.json`（23 向量，`status = DRAFT-2026-09-12`）。
 * 为什么必须运行时读文件而不是把期望值抄进断言：抄进去之后「实现改了、期望值跟着改」不会被发现，
 * 对账就退化成自证。读取时**同时**校验文件 sha256（写入 `evidence` 与报告），
 * 保证「跑的是哪一版契约」可复算。
 *
 * 反例纪律（施工单 §3）：泳道草案 `docs/acceptance/p2-03-surrogate-key-20260912/raw/draft-vectors.tsv`
 * 的 `key_hex8` 列有 10 条与契约不一致（缺「清符号位」），**禁止**引用它当夹具（D-088）。
 */
object SurrogateKeyVectorSupport {

  /** 契约文件相对仓库根的路径（跨语言两侧共用同一路径，Java 侧同样按此定位） */
  val SpecRelative: String = "contract-specs/specs/surrogate-key.v1.json"

  /** 向上找仓库根（Maven 的 CWD 是模块目录） */
  lazy val repoRoot: Path = {
    val start = java.nio.file.Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
    var dir: Path = start
    while (dir != null && !Files.isRegularFile(dir.resolve(SpecRelative))) dir = dir.getParent
    if (dir == null) throw new IllegalStateException(s"找不到仓库根：从 $start 向上未发现 $SpecRelative")
    dir
  }

  lazy val specPath: Path = repoRoot.resolve(SpecRelative)

  /** 契约文件原始字节的 sha256（小写十六进制）——报告里必须落盘这个读数 */
  lazy val specSha256: String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(Files.readAllBytes(specPath)).map(b => f"${b & 0xFF}%02x").mkString
  }

  lazy val specText: String = new String(Files.readAllBytes(specPath), StandardCharsets.UTF_8)

  /**
   * 契约里的 `status` 字段（当前为 `DRAFT-2026-09-12`）。
   * **不得**据此声称「契约已冻结」——冻结门槛见 `contract-specs/README.md` §3。
   */
  lazy val specStatus: String =
    """"status"\s*:\s*"([^"]*)"""".r.findFirstMatchIn(specText).map(_.group(1)).getOrElse("")

  /**
   * 契约 `rule.entityEnum` 的字面量（封闭枚举；`order` 不在其中，见 D-093）。
   *
   * **实测缺陷（第一轮 E2，两处）**：
   * ① 早先写成 `""""""" + k + """"\s*:…` ——四引号的 raw 拼接实际产出 `\"key\"`
   *    （字面反斜杠+引号），匹配不到任何键 ⇒ 静默返回空表，断言退化成「空列表 vs 契约列表」；
   * ② 改成正确模式后仍失败：契约是**美化 JSON**，`"entityEnum": [` 的 `[` 在**行尾**，
   *    `\s*` 不跨行 ⇒ 捕获组只有 `"["` 本身，数组元素在下一行。
   * 现在改为「先定位键，再用 {@link matchBracket} 从 `[` 配平取整段」，
   * 且解析结果为空时**直接报错**，不允许「解析失败」伪装成「契约里没有」。
   */
  lazy val entityEnum: Seq[String] = {
    val m = keyPattern("entityEnum").r.findFirstMatchIn(specText)
    require(m.isDefined, "契约缺 rule.entityEnum 段（解析模式未命中）")
    val openIdx = specText.indexOf('[', m.get.start)
    require(openIdx > 0, "entityEnum 后未见 `[`")
    val block = specText.substring(openIdx, matchBracket(specText, openIdx) + 1)
    val items = "\"([^\"]*)\"".r.findAllMatchIn(block).map(_.group(1)).toSeq
    require(items.nonEmpty, s"entityEnum 解析为空：$block")
    items
  }

  /** `"key"\s*:\s*<任意字面量>` 的捕获组 1 = 该字面量起始处到行尾的整段（供再解析） */
  private def keyPattern(k: String): String = "\"" + k + "\"\\s*:\\s*(.*)"

  /** `"key"\s*:\s*"<字符串>"` 的捕获组 1 = 字符串值 */
  private def keyStringPattern(k: String): String =
    "\"" + k + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""

  /** `"key"\s*:\s*<整数>` 的捕获组 1 = 整数值 */
  private def keyLongPattern(k: String): String = "\"" + k + "\"\\s*:\\s*(-?\\d+)"

  /**
   * 把**测试夹具里的原值**渲染成 Spark SQL 字符串字面量（`'` 双写转义，`null` → `CAST(NULL AS …)`）。
   *
   * 为什么测试侧不用 `SurrogateKey.literalSource`：那条路会 `require(EntityEnum.contains(entity))`，
   * 而契约 V06/V20/V21 用的正是**枚举外**实体（`order`/`payment`/`refund`，契约 `openItems` 已登记）。
   * 「枚举外实体能否落列」与「算法在给定三元组上的取值」是两件事：前者必须被 `keyExpr` 拒绝
   * （由 `契约枚举外的实体一律拒绝` 用例钉死），后者的 SQL 复算必须与契约同值。
   * 本方法只服务后者，是测试夹具的注入工具，**不是算法入口**。
   */
  def sqlLiteral(v: String): String =
    if (v == null) "CAST(NULL AS STRING)" else "'" + v.replace("'", "''") + "'"

  /**
   * **测试夹具**：契约语义下的完整键表达式 = 生产公式 + D-087 判空包装。
   *
   * 为什么需要它：23 条向量里
   *  ① V06(`order`) / V20(`payment`) / V21(`refund`) 用**枚举外实体**——
   *     `SurrogateKey.keyExpr` 对它们**必须抛错**（D-083 生产护栏），
   *     但契约给这三条写了 `keyBigint`，要逐向量对账就得能照算它们的键；
   *  ② V13/V14/V15 的 `expect = NULL_KEY`——键列必须为 **SQL NULL**。
   *
   * 关键设计：本夹具**不再复制哈希公式**。公式只有一处（`SurrogateKey.keyFormula`，生产代码），
   * 夹具只做两件生产入口不做的事：**跳过枚举护栏** + **施加判空包装**。
   * 第一轮 E2 我曾在测试侧整段复制公式，结果是「改一处忘一处」的漂移风险，
   * 且为了测契约向量差点被诱导去放松生产护栏——两个入口已拆开：
   * `keyFormula`(纯公式，公开) / `keyExpr`(公式+护栏) / `toBIGINT`(公式+护栏+判空) /
   * `literalSource`(常量源) / 本夹具(公式+判空，无护栏)。
   *
   * 判空与取值**必须是同一个表达式**，否则 V13(空串)/V14(仅空白) 会算出一个真实键
   * ——实测首轮正是如此（SQL 侧得 `1`，契约要 NULL）。
   */
  def keyExprTestFixture(sourceExpr: String, entity: String, rawIdExpr: String): String =
    s"""CASE WHEN trim(cast($rawIdExpr AS STRING)) IS NULL
       |          OR trim(cast($rawIdExpr AS STRING)) = ''
       |     THEN NULL ELSE ${SurrogateKey.keyFormula(sourceExpr, entity, rawIdExpr)} END""".stripMargin

  /**
   * 一条契约向量。
   *
   * `expect = "KEY"` 时 `keyBigint`/`keyHex16` 有值；`expect = "NULL_KEY"` 时二者为 `None`
   * （契约用机器可读字段取代了泳道草案的散文记号 `(空)`/`(null)`，判据缺陷 B 的处置）。
   */
  final case class Vector(
      id: String,
      source: String,
      entity: String,
      rawInput: String,
      rawInputNotation: String,
      normalized: String,
      material: String,
      sha256: String,
      digestPrefixHex16: String,
      keyHex16: Option[String],
      keyBigint: Option[Long],
      kind: String,
      expect: String,
      why: String)

  /** 23 条契约向量（顺序 = 文件顺序） */
  lazy val vectors: Seq[Vector] = {
    val arrIdx = specText.indexOf("\"vectors\"")
    require(arrIdx >= 0, "契约缺 vectors 段")
    val startIdx = specText.indexOf('[', arrIdx)
    require(startIdx > 0, "契约 vectors 段的 `[` 无法定位")
    // **实测缺陷（第一轮 E2）**：早先用 `lastIndexOf(']')` 收尾，而契约 `vectors` 段之后还有
    // `rule.entityEnum` 等数组 ⇒ 切出的 body 跨了段的边界，多出 1 个对象（24 ≠ 23），
    // 且把 `entityEnum` 的字符串当成了向量字段。这里改为**括号配平**找本段的 `]`。
    val endIdx = matchBracket(specText, startIdx)
    val body = specText.substring(startIdx + 1, endIdx)

    // 极简对象切分：只认「`{ ... }` 且内部无嵌套对象」这一种形态（契约向量的实际形态）。
    // 不用通用 JSON 解析器是刻意的：测试模块不引入新依赖（离线仓不可靠）。
    val objects = splitTopLevelObjects(body)
    objects.map { obj =>
      def str(k: String): String =
        keyStringPattern(k).r.findFirstMatchIn(obj).map(_.group(1)).getOrElse("")
      def optLong(k: String): Option[Long] =
        keyLongPattern(k).r.findFirstMatchIn(obj).map(_.group(1).toLong)
      // 空值记号：契约的 NULL 向量里这些字段是 `null`（而非字符串），
      // 故用 `"..."|null` 二选一；`null` 时捕获组为 None ⇒ 与「字段缺失」同码，
      // 二者由 `rawInputNotation` 区分（契约载体缺陷，D-088 已登记）。
      def optStr(k: String): Option[String] = {
        val m = ("\"" + k + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|null)").r
          .findFirstMatchIn(obj)
        m.flatMap(_.subgroups.headOption)
      }
      Vector(
        id = str("id"),
        source = str("source"),
        entity = str("entity"),
        rawInput = optStr("rawInput").getOrElse(""),
        rawInputNotation = optStr("rawInputNotation").getOrElse(""),
        normalized = optStr("normalized").getOrElse(""),
        material = optStr("material").getOrElse(""),
        sha256 = str("sha256"),
        digestPrefixHex16 = optStr("digestPrefixHex16").getOrElse(""),
        keyHex16 = optStr("keyHex16"),
        keyBigint = optLong("keyBigint"),
        kind = str("kind"),
        expect = str("expect"),
        why = str("why"))
    }
  }

  /** 从 `[` 的下标出发做括号配平，返回配对的 `]` 下标（忽略字符串字面量与转义） */
  private def matchBracket(text: String, openIdx: Int): Int = {
    var depth = 0
    var inStr = false
    var esc = false
    var i = openIdx
    while (i < text.length) {
      val c = text.charAt(i)
      if (inStr) {
        if (esc) esc = false
        else if (c == '\\') esc = true
        else if (c == '"') inStr = false
      } else c match {
        case '"' => inStr = true
        case '[' => depth += 1
        case ']' =>
          depth -= 1
          if (depth == 0) return i
        case _ => ()
      }
      i += 1
    }
    throw new IllegalStateException(s"下标 $openIdx 的 `[` 没有配对的 `]`")
  }

  /** 顶层对象切分（深度计数；忽略字符串字面量内的 `{}`） */
  private def splitTopLevelObjects(body: String): Seq[String] = {
    val out = scala.collection.mutable.ListBuffer[String]()
    var depth = 0
    var inStr = false
    var esc = false
    var start = -1
    var i = 0
    while (i < body.length) {
      val c = body.charAt(i)
      if (inStr) {
        if (esc) esc = false
        else if (c == '\\') esc = true
        else if (c == '"') inStr = false
      } else c match {
        case '"' => inStr = true
        case '{' => if (depth == 0) start = i; depth += 1
        case '}' =>
          depth -= 1
          if (depth == 0 && start >= 0) { out += body.substring(start, i + 1); start = -1 }
        case _ => ()
      }
      i += 1
    }
    out.toSeq
  }

  /** 断言文件存在且非空（读任何文件前先断言） */  def requireNonEmpty(path: Path): Unit = {
    if (!Files.isRegularFile(path)) throw new IllegalStateException(s"文件不存在：$path")
    if (Files.size(path) <= 0) throw new IllegalStateException(s"文件为空：$path")
  }

  /** Java 集合 → Scala 序列 */
  def seqOf[T](it: java.util.Iterator[T]): Seq[T] = it.asScala.toSeq
}
