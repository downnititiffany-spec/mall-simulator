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

  "JobRegistry" should "注册首批 4 作业且依赖顺序正确" in {
    JobRegistry.allCodes should contain allOf ("odl", "bdw", "usw", "fna")
    JobRegistry.jobs.size should be(4)
    JobRegistry.dependencies("bdw") should be(List("odl"))
    JobRegistry.dependencies("usw") should be(List("bdw"))
    JobRegistry.dependencies("fna") should be(List("usw"))
    JobRegistry.dependencies("odl") should be(List.empty)
    JobRegistry.lookup("unknown") should be(None)
  }
}