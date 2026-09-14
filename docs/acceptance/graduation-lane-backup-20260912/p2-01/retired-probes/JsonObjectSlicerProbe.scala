package com.graduation.analytics.sql

/**
 * P2-01 探针用（临时）：从落地区 JSON 行中**原样切出** `payload` 值的文本。
 * 只做字符级扫描，不解析、不重排、不转义还原 —— 切出的子串与源文件逐字节相同。
 */
object JsonObjectSlicerProbe {

  val PayloadKey = "payload"

  def slicePayloadValue(rawLine: String): Either[String, String] = {
    if (rawLine == null || rawLine.isEmpty) return Left("EMPTY_LINE")
    val key = "\"" + PayloadKey + "\""
    val keyIdx = rawLine.indexOf(key)
    if (keyIdx < 0) return Left("PAYLOAD_KEY_MISSING")
    var i = keyIdx + key.length
    while (i < rawLine.length && Character.isWhitespace(rawLine.charAt(i))) i += 1
    if (i >= rawLine.length || rawLine.charAt(i) != ':') return Left("PAYLOAD_COLON_MISSING")
    i += 1
    while (i < rawLine.length && Character.isWhitespace(rawLine.charAt(i))) i += 1
    if (i >= rawLine.length) return Left("PAYLOAD_VALUE_MISSING")
    val open = rawLine.charAt(i)
    if (open == '{') {
      var depth = 0
      var inStr = false
      var escaped = false
      var j = i
      while (j < rawLine.length) {
        val c = rawLine.charAt(j)
        if (inStr) {
          if (escaped) escaped = false
          else if (c == '\\') escaped = true
          else if (c == '"') inStr = false
        } else if (c == '"') inStr = true
        else if (c == '{') depth += 1
        else if (c == '}') {
          depth -= 1
          if (depth == 0) return Right(rawLine.substring(i, j + 1))
        }
        j += 1
      }
      Left("UNBALANCED_OBJECT")
    } else if (open == '"') {
      var j = i + 1
      var escaped = false
      while (j < rawLine.length) {
        val c = rawLine.charAt(j)
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') return Right(rawLine.substring(i, j + 1))
        j += 1
      }
      Left("UNTERMINATED_STRING")
    } else Left("PAYLOAD_NOT_OBJECT")
  }
}
