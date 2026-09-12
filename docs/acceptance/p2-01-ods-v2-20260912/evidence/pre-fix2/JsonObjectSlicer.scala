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
 *    出现 `"payload"` 字样不会被误判；顶层重复键按 DUPLICATE 判失败，避免口径分歧。
 *  - **失败即 null 语义**：任何异常形态（缺键、仅嵌套键、值非对象/非字符串、括号不配平、
 *    字符串未闭合）都返回 `Left(reason)`，由调用侧落成 NULL，绝不抛异常中断整批。
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
      case KeyScan.Found(i) => i
      case KeyScan.Nested => return Left(ErrKeyNested)
      case KeyScan.Duplicate => return Left(ErrDuplicateKey)
      case KeyScan.Absent => return Left(ErrKeyMissing)
    }

    var i = keyStart + PayloadKey.length + 2 // 含键两侧引号
    while (i < rawLine.length && isJsonSpace(rawLine.charAt(i))) i += 1
    if (i >= rawLine.length || rawLine.charAt(i) != ':') return Left(ErrColonMissing)
    i += 1
    while (i < rawLine.length && isJsonSpace(rawLine.charAt(i))) i += 1
    if (i >= rawLine.length) return Left(ErrValueMissing)

    rawLine.charAt(i) match {
      case '{' =>
        sliceBalanced(rawLine, i) match {
          case Some(text) => Right(text)
          case None => Left(ErrUnbalanced)
        }
      case '"' =>
        sliceString(rawLine, i) match {
          case Some(text) => Right(text)
          case None => Left(ErrUnterminated)
        }
      case _ => Left(ErrNotObject)
    }
  }

  // ── 顶层键定位 ───────────────────────────────────────────────────────────

  private sealed trait KeyScan
  private object KeyScan {
    case class Found(keyStart: Int) extends KeyScan
    case object Nested extends KeyScan
    case object Duplicate extends KeyScan
    case object Absent extends KeyScan
  }

  /**
   * 单趟扫描：只在「顶层对象直下」这一层上认 `"payload"` 键。
   * 顶层对象自身的键，其起始位置之前的 `{`/`[` 净深度恰为 1。
   * 命中第二个顶层同名键 → Duplicate（口径分歧，判失败而不是猜）。
   */
  private def findTopLevelKey(rawLine: String): KeyScan = {
    var depth = 0
    var inStr = false
    var escaped = false
    var strStart = -1
    var found = -1
    var i = 0
    while (i < rawLine.length) {
      val c = rawLine.charAt(i)
      if (inStr) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') {
          inStr = false
          if (depth == 1 && strStart >= 0 && rawLine.substring(strStart + 1, i) == PayloadKey &&
              isFollowedByColon(rawLine, i + 1)) {
            if (found >= 0) return KeyScan.Duplicate
            found = strStart
          }
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
    else if (rawLine.contains("\"" + PayloadKey + "\"")) KeyScan.Nested
    else KeyScan.Absent
  }

  private def isFollowedByColon(rawLine: String, from: Int): Boolean = {
    var j = from
    while (j < rawLine.length && isJsonSpace(rawLine.charAt(j))) j += 1
    j < rawLine.length && rawLine.charAt(j) == ':'
  }

  // ── 值切片 ───────────────────────────────────────────────────────────────

  /** 从 `open` 处的 `{` 起，深度归零处即对象结尾（引号/转义安全） */
  private def sliceBalanced(rawLine: String, open: Int): Option[String] = {
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
        if (depth == 0) return Some(rawLine.substring(open, i + 1))
      }
      i += 1
    }
    None
  }

  private def sliceString(rawLine: String, open: Int): Option[String] = {
    var i = open + 1
    var escaped = false
    while (i < rawLine.length) {
      val c = rawLine.charAt(i)
      if (escaped) escaped = false
      else if (c == '\\') escaped = true
      else if (c == '"') return Some(rawLine.substring(open, i + 1))
      i += 1
    }
    None
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
