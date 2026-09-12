package com.graduation.analytics.sql

import com.graduation.analytics.P2TestSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets

/**
 * P2-01 **A5 / A5b / A4b**：`payload` 原样切片的纯 JVM 断言（**不建 SparkSession**）。
 *
 * 为什么单独一个套件：`spark-jobs` 的 pom 从未声明 `spark-hive`，
 * 而本机 `D:\maven_repository` 里只有 `spark-hive_2.12:3.3.2`（本项目是 3.5.1）、
 * 且 `datanucleus-core` 整目录缺失 ⇒ `mvn -o` 下**无法**把 Hive 摆上测试 classpath。
 * 所以凡是**不需要** Spark 的断言一律放这里——它们本就只验证纯函数，不该被环境绑住。
 *
 * 独立 oracle（不依赖被测代码）：本机 PowerShell 对黄金夹具第 1 行 `payload` 原文算出的
 * `SHA-256(UTF-8) = 385dee5b723e23f0778d6726140ced55b5e9f5aa45f79d42869b1f753e260445`
 * （payload 122 UTF-8 字节）——作为常量钉死，证明哈希实现不是「自己算自己」。
 */
class JsonObjectSlicerSpec extends AnyFlatSpec with Matchers {

  /** 独立 oracle（PowerShell 独立算出，见类注释） */
  private val OracleSha256 = "385dee5b723e23f0778d6726140ced55b5e9f5aa45f79d42869b1f753e260445"
  private val OraclePayloadBytes = 122

  private lazy val goldenLines: Seq[String] = {
    P2TestSupport.requireNonEmpty(P2TestSupport.goldenPath)
    P2TestSupport.goldenLines
  }

  "JsonObjectSlicer" should "A5 正常行切出原始子串；缺失/嵌套/重复键/未配平/非对象 一律 Left" in {
    // 正对照：先证明扫描器真的会成 Right（否则下面全是「永远 Left」的假绿）
    JsonObjectSlicer.slice("""{"a":1,"payload":{"x": 1, "s":"}"},"b":2}""") should
      be(Right("""{"x": 1, "s":"}"}"""))

    JsonObjectSlicer.slice("""{"a":1}""") should be(Left(JsonObjectSlicer.ErrKeyMissing))
    JsonObjectSlicer.slice("""{"a":{"payload":{}}}""") should be(Left(JsonObjectSlicer.ErrKeyNested))
    JsonObjectSlicer.slice("""{"payload":{},"payload":{}}""") should be(Left(JsonObjectSlicer.ErrDuplicateKey))
    JsonObjectSlicer.slice("""{"payload":{"a":1}""") should be(Left(JsonObjectSlicer.ErrUnbalanced))
    JsonObjectSlicer.slice("""{"payload":123}""") should be(Left(JsonObjectSlicer.ErrNotObject))
    JsonObjectSlicer.slice("") should be(Left(JsonObjectSlicer.ErrEmptyLine))
    JsonObjectSlicer.slice(null) should be(Left(JsonObjectSlicer.ErrEmptyLine))
    // `"payload"` 出现在**字符串值**里：只有嵌套形态才会被扫到 ⇒ 期望 Nested（而不是误切）
    JsonObjectSlicer.slice("""{"s":"\"payload\":{}}""") should be(Left(JsonObjectSlicer.ErrKeyNested))
  }

  it should "A5b 无空白/多空白/转义 三种形态都原样返回（不补空格、不还原转义）" in {
    JsonObjectSlicer.slice("""{"payload":{"a":1}}""") should be(Right("""{"a":1}"""))
    JsonObjectSlicer.slice("""{"payload"  :  { "a" : 1 }  }""") should be(Right("""{ "a" : 1 }"""))
    JsonObjectSlicer.slice("""{"payload":{"q":"a\"b"}}""") should be(Right("""{"q":"a\"b"}"""))
    // 嵌套大括号 + 字符串里的 `}` 必须靠配平计数收尾，不能靠「第一个 }」
    JsonObjectSlicer.slice("""{"payload":{"n":{"a":1},"b":"}"}}""") should
      be(Right("""{"n":{"a":1},"b":"}"}"""))
  }

  it should "A5c 大括号/转义在深层嵌套下仍能正确找到顶层键（键名出现在嵌套对象里不算顶层）" in {
    // 顶层键之外还有一个**同名的嵌套键** → 期望 Nested（顶层键本身缺失）
    JsonObjectSlicer.slice("""{"outer":{"payload":{"a":1}}}""") should
      be(Left(JsonObjectSlicer.ErrKeyNested))
    // 顶层键存在，同时嵌套里也有同名键 → 必须切顶层那个
    JsonObjectSlicer.slice("""{"payload":{"a":1},"o":{"payload":{"b":2}}}""") should
      be(Right("""{"a":1}"""))
  }

  it should "A5d 缺冒号/缺值/未终止字符串 一律 Left（不抛异常）" in {
    JsonObjectSlicer.slice("""{"payload" {"a":1}}""") should be(Left(JsonObjectSlicer.ErrColonMissing))
    JsonObjectSlicer.slice("""{"payload":}""") should be(Left(JsonObjectSlicer.ErrValueMissing))
    JsonObjectSlicer.slice("""{"payload":{"a":"unterminated}""") should
      be(Left(JsonObjectSlicer.ErrUnterminated))
  }

  it should "A4b 黄金夹具第 1 行 payload 的 SHA-256 与独立 oracle 逐字节一致（122 字节）" in {
    val line1 = goldenLines.head
    val payload = JsonObjectSlicer.slice(line1).right.get
    payload.getBytes(StandardCharsets.UTF_8).length should be(OraclePayloadBytes)
    JsonObjectSlicer.sha256Hex(payload) should be(OracleSha256)

    // 负对照①：语义等价、键序不同的文本 → 哈希必须不同（证明哈希真的在看字节，不是在看语义）
    val reordered = """{"register_time":"2026-09-01T09:00:00+08:00","member_level":"gold","city_level":"tier1","age_group":"25-34","user_id":"1"}"""
    JsonObjectSlicer.sha256Hex(reordered) should not be OracleSha256

    // 负对照②：语义等价、多一个空格的文本 → 哈希必须不同
    val spaced = """{"user_id":"1", "age_group":"25-34","city_level":"tier1","member_level":"gold","register_time":"2026-09-01T09:00:00+08:00"}"""
    JsonObjectSlicer.sha256Hex(spaced) should not be OracleSha256

    // 负对照③：`"1.50"` → `"1.5"`（数值语义等价）→ 哈希必须不同（README A2 的指定负例）
    JsonObjectSlicer.sha256Hex("""{"v":1.50}""") should not be JsonObjectSlicer.sha256Hex("""{"v":1.5}""")
  }

  it should "A4c 黄金夹具逐行：切片结果必须是源行的原始子串（不是重序列化产物）" in {
    goldenLines.size should be(55) // 与 SparkStageExecutorSmokeTest 注释的 55 行一致
    var sliced = 0
    goldenLines.foreach { line =>
      JsonObjectSlicer.slice(line) match {
        case Right(p) =>
          sliced += 1
          withClue(s"payload 必须是源行的连续子串: ") { line should include(p) }
          // 哈希形态：64 位小写十六进制
          JsonObjectSlicer.sha256Hex(p) should fullyMatch regex "[0-9a-f]{64}"
        case Left(_) => // 缺 payload 的行允许存在；A12c 另行钉住「不丢行」
      }
    }
    sliced should be >= 1 // 正对照
  }
}
