package com.graduation.analytics.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage 7：analytics 写入型 IT 预收编脚本必须继续保持双库、opt-in、密码不进 CLI。 */
class AnalyticsIsolationScriptsContractTest {

    private static final Path PREPARE = RepoRoot.path("scripts/it-prepare-isolation.ps1");
    private static final Path RUNNER = RepoRoot.path("scripts/run-isolated-tests.ps1");
    private static final Path NAMING = RepoRoot.path("scripts/isolation-naming.ps1");
    private static final Path STAGE7_HTTP = RepoRoot.path("scripts/stage7-http-isolated.ps1");
    private static final Path ADS_IT = RepoRoot.path(
            "analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsMySqlIT.java");
    private static final Path PUBLISHER_IT = RepoRoot.path(
            "analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricPublisherMySqlIT.java");

    @Test
    void prepareScriptCreatesAnalyticsScopeOnlyBehindExplicitOptIn() throws IOException {
        String source = Files.readString(PREPARE);
        String naming = Files.readString(NAMING);
        assertThat(source)
                .contains("[switch]$IncludeAnalytics")
                .contains("\"${RunId}_analytics_meta\"")
                .contains("\"${RunId}_analytics_metric\"")
                .contains("New-IsolationUserName -RunId $RunId -Role 'metaapp'")
                .contains("New-IsolationUserName -RunId $RunId -Role 'metricapp'")
                .contains("isolation-naming.ps1")
                .contains("GetEnvironmentVariable('V25_IT_META_PASSWORD', 'Process')")
                .contains("GetEnvironmentVariable('V25_IT_METRIC_PUBLISH_PASSWORD', 'Process')");
        assertThat(naming)
                .contains("if ($raw.Length -le 32)")
                .contains("SHA256")
                .contains("if ($name.Length -gt 32)");
    }

    @Test
    void runnerStagesDistinctMetaAndMetricScopesWithoutReplacingExistingAnalyticsGate() throws IOException {
        String source = Files.readString(RUNNER);
        assertThat(source)
                .contains("[switch]$IncludeAnalyticsWriteIts")
                .contains("metaDb = \"${RunId}_analytics_meta\"")
                .contains("metricDb = \"${RunId}_analytics_metric\"")
                .contains("metaUser = (New-IsolationUserName -RunId $RunId -Role 'metaapp')")
                .contains("metricUser = (New-IsolationUserName -RunId $RunId -Role 'metricapp')")
                .contains("isolation-naming.ps1")
                .contains("$env:V25_IT_META_DB = $analyticsWrite.metaDb")
                .contains("$env:V25_IT_METRIC_DB = $analyticsWrite.metricDb")
                .contains("MAVEN_ARGS = '-Pisolated-analytics-schema'")
                .contains("AnalyticsIsolationFlywayIT")
                .contains("analytics 双库 Flyway 未通过：拒绝继续写入型 IT")
                .contains("@('IsolationGuardMySqlIT')")
                .contains("@('MetricAdsMySqlIT', 'MetricPublisherMySqlIT')")
                .contains("foreach ($requiredClass in @($t.requireClasses))");
    }

    @Test
    void analyticsPasswordsAreNotAcceptedAsCommandLineParameters() throws IOException {
        for (Path script : new Path[]{PREPARE, RUNNER}) {
            String source = Files.readString(script);
            int start = source.indexOf("param(");
            int end = source.indexOf("\n)", start);
            assertThat(start).as(script + " 必须有 param 块").isGreaterThanOrEqualTo(0);
            assertThat(end).as(script + " param 块必须闭合").isGreaterThan(start);
            String params = source.substring(start, end);
            assertThat(params)
                    .as(script + " 不得新增 *Password 命令行参数，避免口令进入进程列表/历史")
                    .doesNotContain("Password");
        }
    }

    @Test
    void metricWriteItsStayTaggedForUnifiedIsolationSuite() throws IOException {
        assertThat(Files.readString(ADS_IT)).contains("@Tag(\"it\")");
        assertThat(Files.readString(PUBLISHER_IT)).contains("@Tag(\"it\")");
    }

    @Test
    void stage7HttpLaneIsRunScopedAnd3307Only() throws IOException {
        String source = Files.readString(STAGE7_HTTP);
        assertThat(source)
                .contains("New-IsolationUserName -RunId $RunId -Role 'metaapp'")
                .contains("New-IsolationUserName -RunId $RunId -Role 'metricapp'")
                .contains("jdbc:mysql://127.0.0.1:3307/$metaDb")
                .contains("jdbc:mysql://127.0.0.1:3307/$metricDb")
                .contains("target\\v25-it\\$RunId\\http")
                .contains("$attemptId = 'attempt-' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff')")
                .contains("$attemptRoot = Join-Path $httpRoot $attemptId")
                .contains("if ($metaUrl -match ':3306/' -or $metricUrl -match ':3306/')");
    }

    @Test
    void stage7HttpLaneKeepsSecretsOutOfCliAndUsesRealHttpBoundaries() throws IOException {
        String source = Files.readString(STAGE7_HTTP);
        assertThat(source)
                .contains("GetEnvironmentVariable('V25_IT_META_PASSWORD', 'Process')")
                .contains("GetEnvironmentVariable('V25_IT_METRIC_PUBLISH_PASSWORD', 'Process')")
                .doesNotContain("[string]$MetaPassword")
                .doesNotContain("[string]$MetricPassword")
                .contains("/api/v1/runtime-profiles/1/test")
                .contains("/api/v1/runtime-profiles/1/activate")
                .contains("/api/v1/ingestion/runs")
                .contains("/api/v1/pipeline-runs")
                .contains("$attemptVersion = \"stage7-$RunId-$attemptId\"")
                .contains("$idem = $attemptVersion")
                .contains("sourceDataVersion=$attemptVersion");
    }

    @Test
    void stage7HttpLaneOwnsAndStopsOnlyItsPlatformProcess() throws IOException {
        String source = Files.readString(STAGE7_HTTP);
        assertThat(source)
                .contains("-PassThru")
                .contains("if ($proc -and -not $proc.HasExited)")
                .contains("Stop-Process -Id $proc.Id -Force")
                .contains("New-Item -ItemType Directory -Force -Path $eventsDir,$warehouseDir,$metricStaging,$logDir")
                .doesNotContain("-Path $eventsDir,$warehouseDir,$metastoreDir")
                .contains("latest 指针便于控制端固定读取")
                .doesNotContain("Get-Process java | Stop-Process");
    }
}
