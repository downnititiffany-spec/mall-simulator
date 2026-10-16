package com.graduation.analytics

import com.graduation.analytics.job.{JobArgs, JobRegistry}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JobArgsRegistrySpec extends AnyFlatSpec with Matchers {

  "JobArgs.parse" should "解析标准参数并校验必填" in {
    val args = Array(
      "--runtimeProfileId=1", "--jobCode=bdw", "--businessDate=20260901",
      "--inputVersion=v1", "--attemptNo=2", "--shufflePartitions=100")
    val parsed = JobArgs.parse(args).toOption.get
    parsed.runtimeProfileId should be(1L)
    parsed.jobCode should be("bdw")
    parsed.businessDate should be("20260901")
    parsed.inputVersion should be(Some("v1"))
    parsed.attemptNo should be(2)
    parsed.extra("shufflePartitions") should be("100")
  }

  it should "拒绝缺参与非法日期" in {
    JobArgs.parse(Array("--jobCode=bdw", "--businessDate=20260901")) should be('left)
    JobArgs.parse(Array("--runtimeProfileId=1", "--jobCode=bdw", "--businessDate=2026-09-01")) should be('left)
  }

  "JobRegistry" should "注册全部 11 作业且依赖顺序正确" in {
    JobRegistry.allCodes should contain allOf ("odl", "bdw", "dim", "tdw", "usw", "fna", "ljp", "sci", "dqc", "pub", "mxp")
    JobRegistry.jobs.size should be(11)
    JobRegistry.dependencies("bdw") should be(List("odl", "dim"))
    JobRegistry.dependencies("dim") should be(List("odl"))
    JobRegistry.dependencies("tdw") should be(List("odl", "dim"))
    JobRegistry.dependencies("usw") should be(List("bdw", "tdw"))
    JobRegistry.dependencies("fna") should be(List("usw"))
    // R6-13：fna 写暂存 → dqc 只查暂存 → pub 元数据指针发布正式分区
    JobRegistry.dependencies("dqc") should be(List("fna"))
    JobRegistry.dependencies("pub") should be(List("dqc"))
    // R7-3：pub 之后才允许导出（导出必须读已发布的正式分区）
    JobRegistry.dependencies("mxp") should be(List("pub"))
    JobRegistry.dependencies("odl") should be(List.empty)
    JobRegistry.dependencies("ljp") should be(List.empty)
    JobRegistry.dependencies("sci") should be(List.empty)
    JobRegistry.lookup("unknown") should be(None)
  }

  it should "真检环并给出拓扑序（设计 §10.1 L375：扩 DAG 必须真检环，不得沿用「首批无环」假定）" in {
    // 在产 DAG：无环，且拓扑序必须满足「前置在前」这一编排前提
    JobRegistry.hasCycle should be(false)
    val order = JobRegistry.topologicalOrder().getOrElse(fail("在产 DAG 被判成有环"))
    order.toSet should be(JobRegistry.dependencies.keySet)
    def before(a: String, b: String): Unit =
      withClue(s"拓扑序要求 $a 先于 $b，实得 order=$order：") {
        order.indexOf(a) should be < order.indexOf(b)
      }
    before("odl", "dim")
    before("odl", "bdw")   // P2-04-a：bdw 的 SQL LEFT JOIN dim 两张快照（bfs 编排必须 dim 先于 bdw）
    before("dim", "bdw")
    before("odl", "tdw")
    before("dim", "tdw")
    before("bdw", "usw")
    before("tdw", "usw")
    before("usw", "fna")
    before("fna", "dqc")
    before("dqc", "pub")
    before("pub", "mxp")

    // 真实环必须被检出（原实现 `hasCycle = false` 恒假 ⇒ 这三条必红）
    JobRegistry.topologicalOrder(Map("a" -> List("b"), "b" -> List("a"))) should be(None)
    JobRegistry.topologicalOrder(Map("a" -> List("a"))) should be(None) // 自环
    JobRegistry.topologicalOrder(Map("a" -> List("b"), "b" -> List("c"), "c" -> List("a"))) should be(None)

    // 非环用例（含未知前置：不在注册表内的前置被忽略，不构成环、也不静默变成「有环」误报）
    JobRegistry.topologicalOrder(Map.empty) should be(Some(List.empty))
    JobRegistry.topologicalOrder(Map("a" -> List.empty)) should be(Some(List("a")))
    JobRegistry.topologicalOrder(Map("a" -> List("b"), "b" -> List.empty)) should be(Some(List("b", "a")))
    JobRegistry.topologicalOrder(Map("a" -> List("zzz"))) should be(Some(List("a")))

    // 依赖表的每个前置都必须是已注册作业（否则编排里会静默不跑该前置）
    JobRegistry.dependencies.keySet should be(JobRegistry.jobs.keySet)
    JobRegistry.dependencies.foreach { case (code, pres) =>
      pres.foreach(p => withClue(s"$code 的前置 $p 未注册：") { JobRegistry.jobs.keySet should contain(p) })
    }
  }
}