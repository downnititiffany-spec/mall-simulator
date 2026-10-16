package com.graduation.analytics

import scala.collection.mutable.ArrayBuffer

/**
 * S3-30：`MetricAdsCatalog.java`（analytics_metric 建表/插入列白名单的**唯一所有者**，位于
 * `analytics-server/metric-analysis`）**源文本的只读解析器**（测试专用）。
 *
 * 为什么是「读源文件」而不是「加模块依赖直接调 Java API」：`spark-jobs` 是 **JDK 8**（`pom.xml`
 * `<release>8</release>` ＋ `maven.compiler.source/target=8`）而 `metric-analysis` 是 JDK 17，
 * 且两者之间**没有** Maven 依赖关系 ⇒ 直接把 `MetricAdsCatalog.ALL` 拉进 ScalaTest 会把 JDK8↔JDK17
 * 的编译面绑在一起（架构级分叉），而本任务只是要消掉一份**重复的列清单**。故取与守卫家族
 * （`DwsSchemaOwnerSpec`/`AdsSchemaOwnerSpec` 读 `warehouse/ddl` 的 SQL 参考副本、`FixtureWriteShapeSpec` 读
 * Scala 源）一致的既有做法：**解析所有者源文本**。
 *
 * 只解析两件事：表名，以及**第一个** `List.of(...)`（列清单，按源文件顺序）。`keyColumns` 不在本
 * 解析面内（本类不判主键）。归属边界（不得越界表述）：本类**只**判「源文本里的列名序列」，
 * 不判建表是否真执行、不判任何口径或指标值。
 *
 * 形态脱节（找不到 `ALL = List.of(`、或某个 `new MetricAdsCatalog(` 明细解析不出来）时**抛
 * `IllegalArgumentException`**，绝不静默返回空清单/短清单 —— 否则调用方的逐表比对会退化成空转。
 */
private[analytics] object MetricAdsCatalogSource {

  /** 条目起点：`new MetricAdsCatalog(` 出现次数＝应有明细数（用于「一个都不许漏」核对）。 */
  private val EntryStart = """new\s+MetricAdsCatalog\s*\(""".r

  /** 宿主形态：`ALL = List.of(` 之后即为清单体。 */
  private val AllMarker = "ALL = List.of("

  /** 明细：`new MetricAdsCatalog("<表名>", List.of(<列名…>)`（列名体内不含 `)`）。 */
  private val EntryDetails =
    """(?s)new\s+MetricAdsCatalog\s*\(\s*"([A-Za-z0-9_]+)"\s*,\s*List\.of\(([^)]*)\)""".r

  private val Quoted = """"([^"]*)"""".r

  /**
   * @param javaSource `MetricAdsCatalog.java` 的完整源文本（UTF-8）
   * @return 按**源文件出现顺序**的 (表名, 列名序列)；顺序本身是判定依据，故不排序、不去重
   */
  def parse(javaSource: String): Seq[(String, Seq[String])] = {
    val start = javaSource.indexOf(AllMarker)
    require(
      start >= 0,
      s"MetricAdsCatalog.java 中找不到 `$AllMarker` —— 解析器与所有者形态已脱节（拒绝静默返回空清单）")
    val body = javaSource.substring(start)

    val entries = EntryStart.findAllIn(body).length
    require(entries > 0, s"`$AllMarker` 之后没有任何 `new MetricAdsCatalog(` 条目 —— 解析面为空")

    val out = ArrayBuffer.empty[(String, Seq[String])]
    EntryDetails.findAllMatchIn(body).foreach { m =>
      out += (m.group(1) -> Quoted.findAllMatchIn(m.group(2)).map(_.group(1)).toList)
    }
    require(
      out.size == entries,
      s"条目数与解析数不一致：$entries 个 `new MetricAdsCatalog(` vs ${out.size} 条明细 —— " +
        "有条目形态变了却没被发现（拒绝静默跳过）")
    out.toList
  }
}
