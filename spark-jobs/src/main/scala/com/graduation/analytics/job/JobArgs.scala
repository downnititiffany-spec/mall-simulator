package com.graduation.analytics.job

/**
 * 作业统一入口参数（§24.5）：--runtimeProfileId --jobCode --businessDate
 * --inputVersion --outputSnapshotId --attemptNo，其余键值对透传。
 */
case class JobArgs(
    runtimeProfileId: Long,
    jobCode: String,
    businessDate: String,   // yyyyMMdd
    inputVersion: Option[String],
    outputSnapshotId: Option[String],
    attemptNo: Int,
    extra: Map[String, String]
)

object JobArgs {

  val DATE_PATTERN = "^\\d{8}$".r

  def parse(args: Array[String]): Either[String, JobArgs] = {
    val kv = args.toSeq.map(_.trim).filter(_.startsWith("--"))
      .map(_.drop(2).split("=", 2))
      .flatMap { parts =>
        if (parts.length == 2) Some(parts(0) -> parts(1)) else None
      }.toMap

    def need(key: String): Either[String, String] =
      kv.get(key).filter(_.nonEmpty).toRight(s"缺少必要参数 --$key")

    def toLongOpt(v: String): Option[Long] = scala.util.Try(v.toLong).toOption
    def toIntOpt(v: String): Option[Int] = scala.util.Try(v.toInt).toOption

    for {
      profileId <- need("runtimeProfileId").flatMap(v =>
        toLongOpt(v).toRight(s"runtimeProfileId 必须为数字: $v"))
      jobCode <- need("jobCode")
      businessDate <- need("businessDate").flatMap { d =>
        val ok = DATE_PATTERN.pattern.matcher(d).matches()
        Either.cond(ok, d, s"businessDate 必须为 yyyyMMdd: $d")
      }
    } yield JobArgs(
      runtimeProfileId = profileId,
      jobCode = jobCode,
      businessDate = businessDate,
      inputVersion = kv.get("inputVersion").filter(_.nonEmpty),
      outputSnapshotId = kv.get("outputSnapshotId").filter(_.nonEmpty),
      attemptNo = kv.get("attemptNo").flatMap(toIntOpt).getOrElse(1),
      extra = kv
    )
  }
}