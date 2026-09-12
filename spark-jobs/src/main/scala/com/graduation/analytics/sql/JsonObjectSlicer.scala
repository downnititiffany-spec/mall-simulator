package com.graduation.analytics.sql

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * landing JSON 行的 `payload` 值「原样切片器」（P2-01 / D-054）。
 *
 * 语义（不得弱化）：
 *  - **字节保真**：返回的是源行里的原始子串，不做 JSON 解析、不重排键、不补空格、不转义还原；
 *    因此 `payload_json` 的 UTF-8 字节与 landing 文件里那一段逐字节相同。
 *  - **只认顶层键**：先定位**顶层**（最外层对象直下）的 `"payload"` 键——行内嵌字符串值里
 *    出现 `"payload"` 字样（含 `\"payload\"` 转义形态）只算嵌套、不算顶层键；顶层重复键按
 *    DUPLICATE 判失败，避免口径分歧；顶层同名键后面不是 `:` → COLON_MISSING。
 *  - **只收对象**：`payload_json` 是 payload **对象**的原始文本（计划书「payload_json 保存原始
 *    对象字符串」/ D-054「payload_json 为 payload 唯一事实所有者」）⇒ 值是字符串/数字/布尔/
 *    null/数组一律 `PAYLOAD_NOT_OBJECT`（落 NULL），不拿非对象文本冒充 payload_json。
 *  - **失败即 null 语义**：任何异常形态（缺键、仅嵌套键、冒号/值缺失、值非对象、值括号不配平、
 *    字符串未闭合、**整行**括号不配平或行尾仍在字符串里）都返回 `Left(reason)`，
 *    由调用侧落成 NULL，绝不抛异常中断整批。
 *  - **整行也要配平**（20260912 归属裁定，实测驱动）：值本身配平但**行被截断**（例
 *    `{"payload":{"a":1}`）→ 判 `PAYLOAD_UNBALANCED_OBJECT`。残缺落盘的行落 NULL 比「猜半个
 *    对象」更符合失败即 null；黄金夹具 55 行已逐行验过整行配平，故该收紧不影响正常路径
 *    （E2 `OdsV2ByteFidelitySpec` A4/A4c/A12c 为证）。
 *
 * 为什么必须切片而不是 `to_json(payload)`：`to_json` 走 Spark 自己的序列化，键序/空白
 * 都可能与源行不同——红检实测「语义等价但键序不同」的文本哈希与源行不同，故不能作为
 * 字节保真的实现。
 */
object JsonObjectSlicer {

  val PayloadKey = "payload"

  // ── 失败原因（稳定字符串，测试与报告直接引用）────────────────────────────
  val ErrEmptyLine = "EMPTY_LINE"
  val ErrKeyMissing = "PAYLOAD_KEY_MISSING"
  val ErrKeyNested = "PAYLOAD_KEY_NESTED"
  val ErrDuplicateKey = "PAYLOAD_KEY_DUPLICATE"
  val ErrColonMissing = "PAYLOAD_COLON_MISSING"
  val ErrValueMissing = "PAYLOAD_VALUE_MISSING"
  val ErrNotObject = "PAYLOAD_NOT_OBJECT"
  val ErrUnbalanced = "PAYLOAD_UNBALANCED_OBJECT"
  val ErrUnterminated = "PAYLOAD_UNTERMINATED_STRING"

  /** 兼容探针/早期草稿使用的入口名 */
  def slicePayloadValue(rawLine: String): Either[String, String] = slice(rawLine)

  /**
   * 从一行 landing JSON 文本切出 `payload` 的原始文本（含外层大括号/引号）。
   *
   * @return `Right(原始子串)`；`Left(失败原因)`。
   */
  def slice(rawLine: String): Either[String, String] = {
    if (rawLine == null || rawLine.isEmpty) return Left(ErrEmptyLine)

    val keyStart = findTopLevelKey(rawLine) match {
      case KeyScan.Found(i)     => i
      case KeyScan.Nested       => return Left(ErrKeyNested)
      case KeyScan.Duplicate    => return Left(ErrDuplicateKey)
      case KeyScan.ColonMissing => return Left(ErrColonMissing)
      case KeyScan.Absent       => return Left(ErrKeyMissing)
    }

    var i = keyStart + PayloadKey.length + 2 // 含键两侧引号
    while (i < rawLine.length && isJsonSpace(rawLine.charAt(i))) i += 1
    if (i >= rawLine.length || rawLine.charAt(i) != ':') return Left(ErrColonMissing)
    i += 1
    while (i < rawLine.length && isJsonSpace(rawLine.charAt(i))) i += 1

    if (i >= rawLine.length) return Left(ErrValueMissing)

    rawLine.charAt(i) match {
      case '}' | ']' | ',' => Left(ErrValueMissing) // 冒号后直接是收尾/分隔符 ⇒ 没有值
      case '{' =>
        sliceBalanced(rawLine, i) match {
          case Left(reason) => Left(reason)
          case Right(text) =>
            // 值自身配平还不够：整行的括号必须收在 0 且行尾不在字符串里（行被截断 ⇒ 判失败）
            if (envelopeBalanced(rawLine)) Right(text) else Left(ErrUnbalanced)
        }
      // 只收对象：字符串/数字/布尔/null/数组一律判失败 → 调用侧落 NULL（见类注释「只收对象」）
      case _ => Left(ErrNotObject)
    }
  }

  // ── 顶层键定位 ───────────────────────────────────────────────────────────

  private sealed trait KeyScan
  private object KeyScan {
    case class Found(keyStart: Int) extends KeyScan
    case object Nested extends KeyScan
    case object Duplicate extends KeyScan
    case object ColonMissing extends KeyScan
    case object Absent extends KeyScan
  }

  /**
   * 单趟扫描：只在「顶层对象直下」这一层上认 `"payload"` 键。
   * 顶层对象自身的键，其起始位置之前的 `{`/`[` 净深度恰为 1。
   * 命中第二个顶层同名键 → Duplicate（口径分歧，判失败而不是猜）；
   * 顶层同名键后面不是 `:` → ColonMissing；只出现在更深层或字符串值里 → Nested。
   */
  private def findTopLevelKey(rawLine: String): KeyScan = {
    var depth = 0
    var inStr = false
    var escaped = false
    var strStart = -1
    var found = -1
    var topLevelTokenWithoutColon = false
    var nested = false
    var i = 0
    while (i < rawLine.length) {
      val c = rawLine.charAt(i)
      if (inStr) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') {
          inStr = false
          val content = rawLine.substring(strStart + 1, i)
          if (content == PayloadKey) {
            if (depth == 1) {
              if (isFollowedByColon(rawLine, i + 1)) {
                if (found >= 0) return KeyScan.Duplicate
                found = strStart
              } else topLevelTokenWithoutColon = true
            } else nested = true
          } else if (mentionsPayloadKey(content)) nested = true
        }
      } else c match {
        case '"' =>
          strStart = i
          inStr = true
        case '{' | '[' => depth += 1
        case '}' | ']' => depth -= 1
        case _ => ()
      }
      i += 1
    }
    if (found >= 0) KeyScan.Found(found)
    else if (topLevelTokenWithoutColon) KeyScan.ColonMissing
    else if (nested || mentionsPayloadKey(rawLine)) KeyScan.Nested
    else KeyScan.Absent
  }

  /**
   * 文本里是否**提到** `"payload"` 键字样（含 `\"payload\"` 这种转义写法）。
   *
   * 两层用途：① 判定某个字符串 token 的内容里出现了键字样 ⇒ 只算嵌套、不算顶层键；
   * ② 兜底——顶层键没找到、但行里确实写着 `payload`（转义包起来 / 引号不配对）时判 `KEY_NESTED`
   * 而不是 `KEY_MISSING`：两种失败都切不出 payload，但「以为是键、其实落在字符串里」更贴近事实
   * （`JsonObjectSlicerSpec` A5 的 `{"s":"\"payload\":{}}` 即此形态）。
   */
  private def mentionsPayloadKey(text: String): Boolean =
    text.contains("\"" + PayloadKey + "\"") ||
      text.replace("\\\"", "\"").contains("\"" + PayloadKey + "\"")

  private def isFollowedByColon(rawLine: String, from: Int): Boolean = {
    var j = from
    while (j < rawLine.length && isJsonSpace(rawLine.charAt(j))) j += 1
    j < rawLine.length && rawLine.charAt(j) == ':'
  }

  // ── 值切片 ───────────────────────────────────────────────────────────────

  /**
   * 从 `open` 处的 `{` 起，深度归零处即对象结尾（引号/转义安全）。
   *
   * 扫到行尾还没收尾时区分两种失败：行尾仍在字符串里 → `UNTERMINATED_STRING`；
   * 否则 → 值括号不配平。
   */
  private def sliceBalanced(rawLine: String, open: Int): Either[String, String] = {
    var depth = 0
    var inStr = false
    var escaped = false
    var i = open
    while (i < rawLine.length) {
      val c = rawLine.charAt(i)
      if (inStr) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') inStr = false
      } else if (c == '"') inStr = true
      else if (c == '{') depth += 1
      else if (c == '}') {
        depth -= 1
        if (depth == 0) return Right(rawLine.substring(open, i + 1))
      }
      i += 1
    }
    if (inStr) Left(ErrUnterminated) else Left(ErrUnbalanced)
  }

  /**
   * 整行括号配平检查（引号/转义安全）：净深度必须收在 0、中途不得为负、行尾不得停在字符串里。
   * 用于拒绝「payload 值本身配平、但整行被截断」的残缺落盘行（见类注释）。
   */
  private def envelopeBalanced(rawLine: String): Boolean = {
    var depth = 0
    var inStr = false
    var escaped = false
    var i = 0
    while (i < rawLine.length) {
      val c = rawLine.charAt(i)
      if (inStr) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') inStr = false
      } else if (c == '"') inStr = true
      else if (c == '{' || c == '[') depth += 1
      else if (c == '}' || c == ']') {
        depth -= 1
        if (depth < 0) return false
      }
      i += 1
    }
    depth == 0 && !inStr
  }

  /** JSON 允许的空白：空格/制表/CR/LF（刻意不用 isWhitespace，避免把非 ASCII 空白当分隔） */
  private def isJsonSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

  // ── 哈希 ─────────────────────────────────────────────────────────────────

  /**
   * `payload_hash` = SHA-256(UTF-8(payload 原文))，小写十六进制 64 位。
   * **仅诊断用**，不是去重键、不参与任何 join/主键（D-054）。
   */
  def sha256Hex(text: String): String = {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
    val sb = new StringBuilder(bytes.length * 2)
    bytes.foreach(b => sb.append(f"${b & 0xff}%02x"))
    sb.toString
  }

  /** 切片 + 哈希一步到位；切片失败或 payload 缺失时哈希为 null（不抛异常） */
  def sliceAndHash(rawLine: String): (String, String) = slice(rawLine) match {
    case Right(text) => (text, sha256Hex(text))
    case Left(_) => (null, null)
  }
}
