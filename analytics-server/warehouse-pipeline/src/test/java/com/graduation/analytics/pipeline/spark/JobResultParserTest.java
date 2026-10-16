package com.graduation.analytics.pipeline.spark;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6 快速测试（L0，不启动 Spark）：从 spark-submit 日志文本中解析最终 JobResult JSON。
 * 覆盖：最终行识别、普通 JSON 日志不误判、缺结果行判失败、退出码非 0 判失败、
 * 结果行在超长日志尾部仍可解析（对应日志截断场景）、FAILED 状态透传。
 */
class JobResultParserTest {

    private static final String SUCCESS_LOG =
            "java.io.FileNotFoundException: (前面的普通异常日志)\n" +
                    "{\"level\":\"INFO\",\"event\":\"landing read ok\"}\n" +
                    "{\"jobCode\":\"odl\",\"inputRecords\":30,\"outputRecords\":30,\"rejectedRecords\":0," +
                    "\"snapshotId\":\"S20260901_7\",\"attemptNo\":1,\"status\":\"SUCCESS\"," +
                    "\"message\":\"ok\",\"elapsedMs\":1250}\n";

    @Test
    void parsesFinalJobResultLineFromLog() {
        Optional<JobResultParser.JobResultInfo> parsed = JobResultParser.parseLog(SUCCESS_LOG);
        assertThat(parsed).isPresent();
        JobResultParser.JobResultInfo r = parsed.get();
        assertThat(r.success()).isTrue();
        assertThat(r.jobCode()).isEqualTo("odl");
        assertThat(r.inputRecords()).isEqualTo(30);
        assertThat(r.outputRecords()).isEqualTo(30);
        assertThat(r.rejectedRecords()).isZero();
        assertThat(r.snapshotId()).isEqualTo("S20260901_7");
        assertThat(r.attemptNo()).isEqualTo(1);
        assertThat(r.elapsedMs()).isEqualTo(1250);
    }

    @Test
    void parsesQualityChecksAndBlockingFailures() {
        // R6-13：dqc/pub 的 checks 数组必须被解析出来（含 severity/layer/targetTable），
        // 缺失 severity 的检查按 BLOCKING 处理（安全默认：不放过未知严重度）
        String log = "{\"jobCode\":\"dqc\",\"inputRecords\":51,\"outputRecords\":0,\"rejectedRecords\":0," +
                "\"attemptNo\":1,\"status\":\"FAILED\",\"message\":\"质量门阻断\",\"elapsedMs\":800," +
                "\"checks\":[" +
                "{\"ruleCode\":\"ADS_STAGING_PRESENT\",\"layer\":\"ADS_STAGING\"," +
                "\"targetTable\":\"dw_ads.ads_hot_product__staging\",\"checkCount\":8,\"errorCount\":1," +
                "\"threshold\":\"分区存在且 Location 可读（允许 0 行专题）\",\"severity\":\"BLOCKING\",\"passed\":false,\"detail\":\"缺失1表\"}," +
                "{\"ruleCode\":\"PUB_DQ_EVENT_ID_UNIQUE\",\"layer\":\"PUBLISH\",\"checkCount\":51," +
                "\"errorCount\":3,\"threshold\":\"0.0005\",\"severity\":\"ERROR\",\"passed\":false}," +
                "{\"ruleCode\":\"LEGACY_NO_SEVERITY\",\"checkCount\":1,\"errorCount\":1,\"passed\":false}" +
                "]}\n";

        JobResultParser.JobResultInfo r = JobResultParser.parseLog(log).orElseThrow();

        assertThat(r.checks()).hasSize(3);
        assertThat(r.checks().get(0).ruleCode()).isEqualTo("ADS_STAGING_PRESENT");
        assertThat(r.checks().get(0).layer()).isEqualTo("ADS_STAGING");
        assertThat(r.checks().get(0).targetTable()).isEqualTo("dw_ads.ads_hot_product__staging");
        assertThat(r.checks().get(0).blocking()).isTrue();
        // F-88：这里断言的是**作业回传标签**的原样透传（该适配器不负责平台口径）。
        // 平台口径（ERROR 也阻断发布，D-142 §1）由 RuleSeverity + DataQualityGate 拥有，
        // 由 PipelineService.persistChecks 落库时归一化 —— 见 RuleSeverityTest / DataQualityGateTest。
        assertThat(r.checks().get(1).severity()).isEqualTo("ERROR");
        assertThat(r.checks().get(1).blocking()).isFalse();
        assertThat(r.checks().get(2).severity()).isEqualTo("BLOCKING"); // 缺省保守判定
        // 作业侧判据：只有 BLOCKING 且未通过的算"作业为什么会 FAILED"
        assertThat(r.blockingFailures()).extracting(JobResultParser.CheckInfo::ruleCode)
                .containsExactly("ADS_STAGING_PRESENT", "LEGACY_NO_SEVERITY");
    }

    @Test
    void ignoresOrdinaryJsonLogLines() {
        // 前面有普通 JSON 日志（含 jobCode 字样但不完整）时不得误判
        String log = "{\"jobCode\":\"odl\",\"msg\":\"starting\"}\n" +
                "{\"jobCode\":\"odl\",\"inputRecords\":30,\"outputRecords\":30,\"rejectedRecords\":0," +
                "\"snapshotId\":\"S20260901_7\",\"attemptNo\":1,\"status\":\"SUCCESS\"," +
                "\"message\":\"ok\",\"elapsedMs\":900}\n";
        assertThat(JobResultParser.parseLog(log))
                .hasValueSatisfying(r -> assertThat(r.success()).isTrue());
    }

    @Test
    void emptyWhenNoResultLine() {
        String log = "spark-submit started\nsome warning line\n(no result json)\n";
        assertThat(JobResultParser.parseLog(log)).isEmpty();
    }

    @Test
    void emptyWhenLogIsNull() {
        assertThat(JobResultParser.parseLog(null)).isEmpty();
    }

    @Test
    void resolvesStatusByExitCode() {
        JobResultParser.JobResultInfo ok = JobResultParser.parseLog(SUCCESS_LOG).orElseThrow();
        // 退出码非 0：即使日志声称 SUCCESS 也判定 FAILED（进程异常终止）
        JobResultParser.JobResultInfo failedByExit =
                JobResultParser.resolve(1, Optional.of(ok));
        assertThat(failedByExit.success()).isFalse();
        assertThat(failedByExit.message()).contains("exit=1");
        // 退出码 0：按日志判定
        assertThat(JobResultParser.resolve(0, Optional.of(ok)).success()).isTrue();
    }

    @Test
    void exitCodeZeroWithMissingResultStillFails() {
        // 进程退出码 0 但没有任何结果行：视为失败（契约缺失，不冒充成功）
        JobResultParser.JobResultInfo r = JobResultParser.resolve(0, Optional.empty());
        assertThat(r.success()).isFalse();
        assertThat(r.jobCode()).isEmpty();
    }

    @Test
    void parsesFailedStatusFromLog() {
        String log = "{\"jobCode\":\"usw\",\"inputRecords\":0,\"outputRecords\":0,\"rejectedRecords\":0," +
                "\"attemptNo\":1,\"status\":\"FAILED\",\"message\":\"分区写失败: /tmp/x\",\"elapsedMs\":310}\n";
        Optional<JobResultParser.JobResultInfo> parsed = JobResultParser.parseLog(log);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().success()).isFalse();
        assertThat(parsed.get().message()).contains("分区写失败");
    }

    @Test
    void resultAtTailOfVeryLongLogStillParses() {
        // 模拟超长日志（submitter 截断后只留尾部）——结果行在尾部时必须可解析
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("line ").append(i).append(" garbage\n");
        }
        sb.append("{\"jobCode\":\"fna\",\"inputRecords\":8,\"outputRecords\":8,\"rejectedRecords\":0,")
                .append("\"snapshotId\":\"S20260901_7\",\"attemptNo\":2,\"status\":\"SUCCESS\",")
                .append("\"message\":\"ok\",\"elapsedMs\":4200}\n");
        assertThat(JobResultParser.parseLog(sb.toString()))
                .hasValueSatisfying(r -> {
                    assertThat(r.success()).isTrue();
                    assertThat(r.snapshotId()).isEqualTo("S20260901_7");
                    assertThat(r.attemptNo()).isEqualTo(2);
                });
    }

    // ── R6-12：输出分区证据（§15.3） ──────────────────────────────────────

    @Test
    void parsesOutputPartitionsFromResultLine() {
        String log = "{\"jobCode\":\"fna\",\"inputRecords\":1,\"outputRecords\":4,\"rejectedRecords\":0,"
                + "\"snapshotId\":\"S20260901_13\",\"attemptNo\":1,\"status\":\"SUCCESS\","
                + "\"message\":\"ok\",\"elapsedMs\":15854,\"outputPartitions\":["
                + "{\"table\":\"dw_ads.ads_operation_overview\",\"dt\":\"20260901\","
                + "\"snapshotId\":\"S20260901_13\",\"rowCount\":1,"
                + "\"path\":\"file:/D:/wh/dw_ads.db/ads_operation_overview/dt=20260901\"},"
                + "{\"table\":\"dw_ads.ads_user_profile\",\"dt\":\"20260901\","
                + "\"snapshotId\":\"S20260901_13\",\"rowCount\":3,"
                + "\"path\":\"file:/D:/wh/dw_ads.db/ads_user_profile/dt=20260901\"}]}\n";
        JobResultParser.JobResultInfo r = JobResultParser.parseLog(log).orElseThrow();
        assertThat(r.outputPartitions()).hasSize(2);
        assertThat(r.outputPartitions().get(0).table()).isEqualTo("dw_ads.ads_operation_overview");
        assertThat(r.outputPartitions().get(0).dt()).isEqualTo("20260901");
        assertThat(r.outputPartitions().get(0).snapshotId()).isEqualTo("S20260901_13");
        assertThat(r.outputPartitions().get(0).rowCount()).isEqualTo(1);
        assertThat(r.outputPartitions().get(0).path()).endsWith("ads_operation_overview/dt=20260901");
        assertThat(r.outputPartitions().get(1).table()).isEqualTo("dw_ads.ads_user_profile");
        assertThat(r.outputPartitions().get(1).rowCount()).isEqualTo(3);
    }

    @Test
    void missingOutputPartitionsYieldsEmptyListNotFabricated() {
        // 旧作业结果（无 outputPartitions 字段）：解析为空表，绝不臆造证据
        JobResultParser.JobResultInfo r = JobResultParser.parseLog(SUCCESS_LOG).orElseThrow();
        assertThat(r.outputPartitions()).isEmpty();
    }

    @Test
    void exitCodeFailureStillCarriesCollectedPartitions() {
        // 失败判定不得丢弃已采集到的分区证据（失败留痕，§15.3）
        String log = "{\"jobCode\":\"usw\",\"inputRecords\":0,\"outputRecords\":0,\"rejectedRecords\":0,"
                + "\"attemptNo\":1,\"status\":\"SUCCESS\",\"message\":\"ok\",\"elapsedMs\":310,"
                + "\"outputPartitions\":[{\"table\":\"dw_dws.dws_trade_day\",\"dt\":\"20260901\","
                + "\"rowCount\":5,\"path\":\"file:/D:/wh/dw_dws.db/dws_trade_day/dt=20260901\"}]}\n";
        JobResultParser.JobResultInfo failed = JobResultParser.resolve(1,
                JobResultParser.parseLog(log));
        assertThat(failed.success()).isFalse();
        assertThat(failed.outputPartitions()).hasSize(1);
        assertThat(failed.outputPartitions().get(0).rowCount()).isEqualTo(5);
    }
}