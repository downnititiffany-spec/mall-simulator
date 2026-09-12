import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.publish.MetricPublisherPort;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishReport;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 前置预演驱动（**非生产代码**，仅存在于验收报告目录）：
 * 用真实的导入器实现（AdsExportReader / MetricAdsWriter / MetricPublishValidator /
 * MetricPublisher / MySqlMetricStore / MetricPublishRepository）把本地 spark 导出物
 * （metric-staging/&lt;snapshotId&gt;/_export.json + *.jsonl）导入一个**隔离库**，
 * 以验证「导出物 → MySQL」闭环的机制、参数与幂等性。
 *
 * 与生产接线的一致点：
 *   ① 复用 metric-analysis/platform-common 的编译产物（target/classes，javac 时间戳已核对）；
 *   ② Spring 上下文按 PlatformDataSources 的同名 Bean 装配（metricPublishDataSource /
 *      metricReadDataSource / metricPublishJdbcTemplate / metricReadJdbcTemplate /
 *      metricPublishTransactionManager），并开启 @EnableTransactionManagement，
 *      使 @Transactional 代理真实生效；
 *   ③ 数据源账号沿用生产账号 metric_pub（读写）/ metric_read（仅 SELECT）；
 *   ④ 指标字典从 analytics_meta.metric_definition 读取（与 PipelineService.metricDefinitions() 同源）。
 *
 * 与生产接线的差异（报告中必须显式声明）：数据库指向隔离库、不经过 Spark/PUBLISH_METRIC 阶段调度、
 * metric_snapshot.version 因隔离库为空而从 1 起（生产为 12）。
 *
 * 用法：RehearsalRunner &lt;label&gt; &lt;snapshotId&gt; &lt;businessDate&gt; &lt;businessTime&gt;
 *        &lt;runtimeProfileId&gt; &lt;runtimeProfileVersion&gt; &lt;pipelineRunId&gt; &lt;exportDir&gt; &lt;jdbcUrl&gt;
 * 退出码：0=发布 ok；3=发布 ok=false（失败路径实测时属预期）；4=驱动自身异常
 */
public class RehearsalRunner {

    @Configuration
    @EnableTransactionManagement
    @ComponentScan(basePackages = "com.graduation.analytics.metric")
    static class RehearsalConfig {

        @Bean("metricPublishDataSource")
        public DataSource metricPublishDataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setUrl(System.getProperty("rehearsal.publish.url"));
            ds.setUsername(System.getProperty("rehearsal.publish.user"));
            ds.setPassword(System.getProperty("rehearsal.publish.password"));
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return ds;
        }

        @Bean("metricReadDataSource")
        public DataSource metricReadDataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setUrl(System.getProperty("rehearsal.read.url"));
            ds.setUsername(System.getProperty("rehearsal.read.user"));
            ds.setPassword(System.getProperty("rehearsal.read.password"));
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return ds;
        }

        @Bean("metaDataSource")
        public DataSource metaDataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setUrl(System.getProperty("rehearsal.meta.url"));
            ds.setUsername(System.getProperty("rehearsal.meta.user"));
            ds.setPassword(System.getProperty("rehearsal.meta.password"));
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return ds;
        }

        @Bean("metricPublishJdbcTemplate")
        public JdbcTemplate metricPublishJdbcTemplate() {
            return new JdbcTemplate(metricPublishDataSource());
        }

        @Bean("metricReadJdbcTemplate")
        public JdbcTemplate metricReadJdbcTemplate() {
            return new JdbcTemplate(metricReadDataSource());
        }

        @Bean("metaJdbcTemplate")
        public JdbcTemplate metaJdbcTemplate() {
            return new JdbcTemplate(metaDataSource());
        }

        @Bean("metricPublishTransactionManager")
        public DataSourceTransactionManager metricPublishTransactionManager() {
            return new DataSourceTransactionManager(metricPublishDataSource());
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.out.println("USAGE: RehearsalRunner <label> <snapshotId> <businessDate> <businessTime> "
                    + "<runtimeProfileId> <runtimeProfileVersion> <pipelineRunId> <exportDir>");
            System.exit(4);
        }
        String label = args[0];
        String snapshotId = args[1];
        String businessDate = args[2];
        String businessTime = args[3];
        long profileId = Long.parseLong(args[4]);
        Integer profileVersion = Integer.valueOf(args[5]);
        Long pipelineRunId = Long.valueOf(args[6]);
        Path exportDir = Path.of(args[7]);

        System.out.println("=== REHEARSAL RUN [" + label + "] ===");
        System.out.println("snapshotId        = " + snapshotId);
        System.out.println("businessDate      = " + businessDate);
        System.out.println("businessTime      = " + businessTime);
        System.out.println("runtimeProfileId  = " + profileId);
        System.out.println("profileVersion    = " + profileVersion);
        System.out.println("pipelineRunId     = " + pipelineRunId);
        System.out.println("exportDir         = " + exportDir);
        System.out.println("publishJdbcUrl    = " + System.getProperty("rehearsal.publish.url"));
        System.out.println("readJdbcUrl       = " + System.getProperty("rehearsal.read.url"));
        System.out.println("publishUser       = " + System.getProperty("rehearsal.publish.user"));
        System.out.println("readUser          = " + System.getProperty("rehearsal.read.user"));
        System.out.println("metaJdbcUrl       = " + System.getProperty("rehearsal.meta.url"));

        long t0 = System.currentTimeMillis();
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RehearsalConfig.class)) {
            // ── 指标字典（与 PipelineService.metricDefinitions() 同源：analytics_meta.metric_definition） ──
            JdbcTemplate meta = ctx.getBean("metaJdbcTemplate", JdbcTemplate.class);
            Map<String, DefinitionRef> definitions = new LinkedHashMap<>();
            meta.query("SELECT metric_code, definition_version, unit FROM metric_definition",
                    rs -> {
                        definitions.put(rs.getString(1),
                                new DefinitionRef(rs.getString(2) == null ? "" : rs.getString(2),
                                        rs.getString(3) == null ? "" : rs.getString(3)));
                    });
            System.out.println("metric_definition rows = " + definitions.size() + " codes=" + definitions.keySet());

            MetricPublisherPort publisher = ctx.getBean(MetricPublisherPort.class);
            System.out.println("publisher impl    = " + publisher.getClass().getName());

            PublishRequest request = new PublishRequest(profileId, profileVersion, snapshotId, businessDate,
                    businessTime, pipelineRunId, exportDir, definitions);

            PublishReport report = publisher.publish(request);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("--- PublishReport ---");
            System.out.println("ok            = " + report.ok());
            System.out.println("errorCode     = " + report.errorCode());
            System.out.println("message       = " + report.message());
            System.out.println("adsRows       = " + report.adsRows());
            System.out.println("metricValues  = " + report.metricValues());
            System.out.println("--- evidence ---");
            report.evidence().forEach((k, v) -> System.out.println("  " + k + " = " + v));
            System.out.println("--- checks (" + report.checks().size() + ") ---");
            for (MetricPublisherPort.Check c : report.checks()) {
                System.out.println("  [" + (c.passed() ? "PASS" : "FAIL") + "] " + c.ruleCode()
                        + " severity=" + c.severity() + " total=" + c.checkCount() + " errors=" + c.errorCount()
                        + " | " + c.detail());
            }

            // ── 只读账号回读（metric_read，与看板同源） ──
            JdbcTemplate read = ctx.getBean("metricReadJdbcTemplate", JdbcTemplate.class);
            System.out.println("--- read-back (metric_read) ---");
            System.out.println("metric_snapshot: status/active_flag/version = " + read.query(
                    "SELECT status, active_flag, version, definition_version FROM metric_snapshot WHERE snapshot_id=?",
                    rs -> rs.next() ? rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getInt(3) + "/" + rs.getString(4)
                            : "<no row>", snapshotId));
            System.out.println("metric_value rows = " + read.queryForObject(
                    "SELECT COUNT(*) FROM metric_value WHERE snapshot_id=?", Long.class, snapshotId));
            List<String> tables = List.of("ads_operation_overview_m", "ads_sale_trend_m", "ads_behavior_funnel_m",
                    "ads_active_trend_m", "ads_hot_product_m", "ads_product_conversion_m", "ads_user_profile_m",
                    "ads_data_quality_m");
            for (String t : tables) {
                Long n = read.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE snapshot_id=?", Long.class, snapshotId);
                System.out.println("  " + t + " rows = " + n);
            }
            System.out.println("run elapsedMs = " + elapsed);
            System.out.println("=== REHEARSAL RESULT [" + label + "] ok=" + report.ok()
                    + " errorCode=" + report.errorCode() + " ===");
            System.exit(report.ok() ? 0 : 3);
        } catch (Throwable e) {
            System.out.println("=== REHEARSAL DRIVER EXCEPTION [" + label + "] ===");
            e.printStackTrace(System.out);
            System.exit(4);
        }
    }
}
