package com.graduation.analytics

import com.graduation.analytics.job.MetricExportJob
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.CRC32

/**
 * S3-06：`mxp` 导出制品的内容摘要（checksum）必须与制品**真实字节**一致。
 *
 * <p>设计 §12.5 L528 的发布顺序是「Spark ADS 暂存 → 数据质量通过 → Hive 不可变发布制品 →
 * `mxp` 导出 + manifest/checksum → MySQL BUILDING/staging → 表形/行数 + 内容验证」，
 * 指导书 §7 阶段 3 L151 同样要求「导出 ADS 制品核 schema/行数/checksum」。
 * 在此之前 `_export.json` 只有 `rowCount`（`docs/audit/v2-completeness-audit.md:213`
 * 已登记「**无 checksum（仅路径 + 行数）**」）：行数相同但内容被截断/改写时，
 * 发布侧 `MP_EXPORT_FILES`（只判文件存在）与 `MP_ADS_ROWS_MATCH`（只判行数）都发现不了。</p>
 *
 * <p><b>口径（唯一，两侧必须逐字节相同）</b>：CRC32（IEEE 802.3，多项式 `0xEDB88320`）作用于
 * 导出文件**全部原始字节**，输出 `Long.toHexString` 形式的小写十六进制、**无前导零**
 * （`^[0-9a-f]{1,8}$`）。这与 landing 侧 `ingestion-manifest.v1` 的 checksum 口径一致
 * （`IngestionService` 用 `Long.toHexString(CRC32.getValue())`），因此同一份制品在两条链路上
 * 的摘要写法不会分裂成两套。</p>
 *
 * <p><b>oracle 的独立性</b>：本套件里的期望值不是用被测的同一个 `java.util.zip.CRC32`
 * 现算出来的（那只是自我镜像），而是**独立实现**实测的常量 —— 由 .NET `GZipStream` 的
 * gzip 尾部字段（trailer 最后 8 字节 = CRC32 小端 + ISIZE）读出，并用 CRC-32 的经典校验值
 * `"123456789" -> cbf43926` 交叉验证过；`3610a686`（`hello`）、`0`（空文件）同为实测值。</p>
 */
class MetricExportChecksumSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var session: SparkSession = _

  override def beforeAll(): Unit = {
    session = P2TestSupport.spark("mxp-checksum")
  }

  override def afterAll(): Unit = {
    P2TestSupport.stop(session)
  }

  /** 落一个临时文件（内容按 UTF-8 编码），返回 Hadoop 侧同一形式的路径字符串 */
  private def tempFile(name: String, content: String): String = {
    val dir = Files.createTempDirectory("mxp-checksum-")
    val file = dir.resolve(name)
    Files.write(file, content.getBytes(StandardCharsets.UTF_8))
    file.toString
  }

  /** 测试侧独立重算（JDK 实现，直接读文件全部字节）——只作为“流式读取是否漏字节”的对照 */
  private def expectedOf(content: String): String = {
    val crc = new CRC32()
    crc.update(content.getBytes(StandardCharsets.UTF_8))
    java.lang.Long.toHexString(crc.getValue)
  }

  "MetricExportJob.crc32" should "对已知字节给出实测 CRC32 十六进制小写（与 ingestion 清单同口径）" in {
    MetricExportJob.crc32(session, tempFile("hello.jsonl", "hello")) should be("3610a686")
    MetricExportJob.crc32(session, tempFile("digits.jsonl", "123456789")) should be("cbf43926")
    // 空文件摘要就是 "0"：它是**合法摘要**（文件真实存在但无内容），与"清单里没有 checksum 键"是两回事
    MetricExportJob.crc32(session, tempFile("empty.jsonl", "")) should be("0")
  }

  it should "读满整个文件（跨 64KB 缓冲区不截断），且输出格式恒为 ^[0-9a-f]{1,8}$" in {
    val big = new StringBuilder
    var i = 0
    while (i < 5000) { // ~200KB，远超单次 read 的 64KB 缓冲区
      big.append("""{"product_id":""").append(i).append(""","name":"商品-""").append(i).append("\"}\n")
      i += 1
    }
    val content = big.toString
    val digest = MetricExportJob.crc32(session, tempFile("big.jsonl", content))
    withClue(s"大文件摘要=$digest：") {
      digest should be(expectedOf(content))
      digest should fullyMatch regex "^[0-9a-f]{1,8}$"
    }
  }

  it should "对内容变化敏感（改一个字符即摘要变化）——否则它就不是内容验证" in {
    val a = MetricExportJob.crc32(session, tempFile("a.jsonl", "{\"a\":1}\n{\"a\":2}\n"))
    val b = MetricExportJob.crc32(session, tempFile("b.jsonl", "{\"a\":1}\n{\"a\":3}\n"))
    // 独立实现实测：195ac066 / 1898aa51
    a should be("195ac066")
    b should be("1898aa51")
    a should not be b
  }
}
