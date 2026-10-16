package com.graduation.analytics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import scala.collection.JavaConverters._

/**
 * S3-31：**仓库根查找（repo root）在 `spark-jobs` 测试树里必须只有一个所有者**。
 *
 * 背景（backlog 行实测）：向上走目录找仓库根这段逻辑此前在测试树里被**各写一份**。同一仓库里同一件事
 * 有多份实现时，任何一份改了口径（换锚点文件、改终止条件、改异常文案）都不会被别的副本发现 —— 这正是
 * 「重复所有者」的典型形态。本类把它收敛成**结构守卫**：树里只有 `P2TestSupport` 允许持有 walk-up 循环，
 * 其余文件必须引用它；并且反证本类**自己**不会成为新的所有者。
 *
 * 自指纪律（照抄 S3-30 的教训）：特征片段用**字符串拼装**得到，而不是写成一整段字面量。S3-30 的反证用例
 * 曾两次匹配到自己的注释文本与合成样例（`s330_green2.log`/`s330_green3.log`），拼装可让本类源文件
 * **不包含**该特征片段，从而不必靠"排除自身文件"来兜底。
 */
class RepoRootSingleOwnerSpec extends AnyFlatSpec with Matchers {

  private val TestTreeRelative = "spark-jobs/src/test/scala"

  /** walk-up 循环的起点声明片段（**拼装**，见类注释自指纪律）。 */
  private val DeclFragment: String = "var " + "dir: Path = start"

  /** walk-up 循环的步进片段（**拼装**，见类注释自指纪律）。 */
  private val StepFragment: String = "dir = dir" + ".getParent"

  private val OwnerRelative = "spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala"

  private def readRepoFile(relative: String): String =
    new String(Files.readAllBytes(P2TestSupport.repoRoot.resolve(relative)), StandardCharsets.UTF_8)

  /** 测试树里全部 `.scala` 源文件：相对路径（正斜杠）→ 源文本。 */
  private def scalaSourcesUnder(relative: String): Seq[(String, String)] = {
    val stream = Files.walk(P2TestSupport.repoRoot.resolve(relative))
    try {
      stream
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".scala"))
        .map { p =>
          val rel = P2TestSupport.repoRoot.relativize(p).toString.replace('\\', '/')
          (rel, new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
        }
        .toVector
    } finally stream.close()
  }

  "S3-31 仓库根查找" should "在整个 spark-jobs 测试树里只有唯一所有者（不是各写一份）" in {
    val sources = scalaSourcesUnder(TestTreeRelative)
    withClue(s"扫描面疑为空（只扫到 ${sources.size} 个 .scala）：") { sources.size should be > 30 }
    val owners = sources
      .filter { case (_, text) => text.contains(DeclFragment) && text.contains(StepFragment) }
      .map(_._1)
      .toSet
    owners should be(Set(OwnerRelative))
  }

  it should "让两个被收编的消费方引用唯一所有者，且不再自持循环" in {
    val collapsed = Seq(
      "spark-jobs/src/test/scala/com/graduation/analytics/SurrogateKeyVectorSupport.scala",
      "spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala"
    )
    collapsed.foreach { relative =>
      val text = readRepoFile(relative)
      withClue(s"$relative 未引用唯一所有者：") { text should include("P2TestSupport.repoRoot") }
      withClue(s"$relative 仍自持 walk-up 步进：") { text should not include StepFragment }
      withClue(s"$relative 仍自持 walk-up 起点：") { text should not include DeclFragment }
    }
  }

  it should "收编后契约向量仍定位到同一份真实文件（行为不变，摘要独立复算）" in {
    val path = SurrogateKeyVectorSupport.specPath
    withClue(s"契约向量文件不存在：$path ：") { Files.isRegularFile(path) should be(true) }
    withClue(s"契约向量未落在仓库根之下：$path ：") { path.startsWith(P2TestSupport.repoRoot) should be(true) }

    val md = MessageDigest.getInstance("SHA-256")
    val recomputed = md.digest(Files.readAllBytes(path)).map(b => f"${b & 0xFF}%02x").mkString
    SurrogateKeyVectorSupport.specSha256 should be(recomputed)
    withClue("契约向量文本为空：") { SurrogateKeyVectorSupport.specText.isEmpty should be(false) }
    withClue("契约 status 未读出（读取链断裂）：") { SurrogateKeyVectorSupport.specStatus.isEmpty should be(false) }
  }
}
