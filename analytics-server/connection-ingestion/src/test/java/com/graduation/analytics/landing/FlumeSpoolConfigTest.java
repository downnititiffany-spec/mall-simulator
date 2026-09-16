package com.graduation.analytics.landing;

import com.graduation.analytics.testsupport.RepoRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-04B：Flume **Spooling Directory** 采集配置的门禁（设计 §8.2 L259-L266 六条规则）。
 *
 * <p>为什么给一个"没跑过的配置文件"写测试：Flume 本机没有，跑不了；但配置里最容易犯的错
 * 恰好都是**静态可判**的——把 {@code %{eventType}} 又写回分区路径（让采集侧替业务分类）、
 * 把 checkpoint 与 data 目录写成同一个（重启即损坏）、把 sink 写中的 {@code .tmp} 后缀改掉
 * （平台可能读走半截文件）、把 spool 目录和 Landing raw 目录写成同一个（§8.2 L268 明令禁止）。
 * 门禁把这些钉死，剩下的"能不能跑通"才是真正的未测项。</p>
 *
 * <p>本类**不声称** Flume 可用：它只证明配置文本没有违反已冻结的设计约束。</p>
 */
class FlumeSpoolConfigTest {

    private static final String CONF_PATH = "ingestion/flume/flume-spooldir.conf";
    private static final String README_PATH = "ingestion/flume/README.md";
    private static final Path CONF = RepoRoot.path(CONF_PATH);
    private static final Path README = RepoRoot.path(README_PATH);

    @Test
    @DisplayName("拓扑齐备：spooldir 源 + file 通道 + 一个 sink，且上下都用同一个通道")
    void topologyIsSpoolDirToFileChannel() {
        Map<String, String> conf = conf();
        assertThat(conf).as("S2-04B 的采集配置必须存在：%s", CONF).isNotEmpty();
        assertThat(conf.get("ingestion.sources.spool.type")).isEqualTo("spooldir");
        assertThat(conf.get("ingestion.channels.fileChannel.type")).isEqualTo("file");
        assertThat(conf.get("ingestion.sources.spool.channels")).isEqualTo("fileChannel");
        assertThat(keysEndingWith(".channel", conf))
                .as("sink 必须显式绑定到 fileChannel（默认通道是不可靠的隐式行为）")
                .contains("ingestion.sinks.landingSink.channel=fileChannel");
        assertThat(conf.get("ingestion.sinks.landingSink.type")).isIn("hdfs", "file_roll");
    }

    @Test
    @DisplayName("规则(3)：sink 只按 ingest 时间分区，绝不按 %{eventType} 分类")
    void sinkPartitionsByIngestTimeOnly() {
        Map<String, String> conf = conf();
        String path = conf.get("ingestion.sinks.landingSink.hdfs.path");
        assertThat(path).as("sink 落地区必须与 runtime_profile.landing_uri + 布局 %s 对齐",
                        LandingLayout.FLUME_RAW.name())
                .isNotNull()
                .contains("/" + LandingLayout.FLUME_RAW.dirName() + "/")
                .contains("dt=%Y%m%d")
                .contains("hour=%H")
                .doesNotContain("%{eventType}");
        assertThat(path).as("必须是显式 scheme，不能是裸相对路径")
                .matches("^(file|hdfs)://.*");
        assertThat(LandingLayout.FLUME_RAW.recursive())
                .as("raw 区按 dt/hour 分层，平台侧必须递归枚举")
                .isTrue();
        assertThat(conf.get("ingestion.sinks.landingSink.hdfs.useLocalTimeStamp"))
                .as("分区时间取 agent 本地时间＝ingest 时刻：事件时间会迟到乱序，不能拿来分区")
                .isEqualTo("true");
        assertThat(effectiveLines().stream().filter(line -> line.contains("%{eventType")).count())
                .as("任何生效行都不许出现 %%{eventType}（注释里解释这条规则不算）")
                .isZero();
    }

    @Test
    @DisplayName("规则(2)：checkpoint 与 data 目录分离且都非空；file channel 容量/事务成对合理")
    void fileChannelKeepsCheckpointAndDataApart() {
        Map<String, String> conf = conf();
        String checkpoint = conf.get("ingestion.channels.fileChannel.checkpointDir");
        String data = conf.get("ingestion.channels.fileChannel.dataDirs");
        assertThat(checkpoint).as("checkpoint 目录必填（重启后靠它恢复）").isNotBlank().startsWith("/");
        assertThat(data).as("data 目录必填").isNotBlank().startsWith("/");
        assertThat(checkpoint).as("两者写成同一个目录＝重启即损坏 checkpoint").isNotEqualTo(data);
        assertThat(conf.get("ingestion.channels.fileChannel.capacity")).isNotNull();
        assertThat(Long.parseLong(conf.get("ingestion.channels.fileChannel.transactionCapacity")))
                .as("transactionCapacity 不得超过 capacity，否则满仓时事务永远提交不了")
                .isLessThanOrEqualTo(Long.parseLong(conf.get("ingestion.channels.fileChannel.capacity")));
        assertThat(Long.parseLong(conf.get("ingestion.channels.fileChannel.checkpointInterval")))
                .as("检查点间隔必须是正数（0/负值等于不落盘）")
                .isPositive();
    }

    @Test
    @DisplayName("规则(1)(4)：只吃完成文件——.tmp/隐藏/未结尾的都不匹配，完成文件才匹配")
    void includePatternAdmitsOnlyCompletedFiles() {
        String pattern = conf().get("ingestion.sources.spool.includePattern");
        assertThat(pattern).isNotBlank();
        Pattern compiled = Pattern.compile(pattern);
        List<String> mustReject = List.of(
                "mock-mall-20260901120000-1.jsonl.tmp",
                ".hidden.jsonl",
                "_tmp.jsonl",
                "mock-mall-20260901120000-1.txt",
                ".flumespool");
        List<String> mustAccept = List.of(
                "mock-mall-20260901120000-1.jsonl",
                "r9-m1-123006.jsonl");
        for (String name : mustReject) {
            assertThat(compiled.matcher(name).matches())
                    .as("进行中/临时文件不得进采集：%s（模式 %s）", name, pattern)
                    .isFalse();
        }
        for (String name : mustAccept) {
            assertThat(compiled.matcher(name).matches())
                    .as("完成文件必须被采集：%s（模式 %s）", name, pattern)
                    .isTrue();
        }
        assertThat(conf().get("ingestion.sources.spool.deletePolicy"))
                .as("消费后改名留档，不做删除（审计要求）")
                .isEqualTo("never");
        assertThat(conf().get("ingestion.sources.spool.fileSuffix")).isNotBlank();
    }

    @Test
    @DisplayName("规则(4) 在 sink 侧同构：不得改写 hdfs.fileSuffix（写中文件必须仍是 .tmp，平台凭它排除）")
    void sinkKeepsDefaultTmpSuffix() {
        Map<String, String> conf = conf();
        assertThat(conf).as("一旦把 fileSuffix 改成 .jsonl，平台会把写了一半的文件当完成文件读走")
                .doesNotContainKey("ingestion.sinks.landingSink.hdfs.fileSuffix");
    }

    @Test
    @DisplayName("§8.2 L268：spool 目录与 Landing raw 目录是两处，不得混成一处")
    void spoolDirIsNotTheLandingArea() {
        Map<String, String> conf = conf();
        String spool = conf.get("ingestion.sources.spool.spoolDir");
        String sinkPath = conf.get("ingestion.sinks.landingSink.hdfs.path");
        assertThat(spool).isNotBlank().startsWith("/");
        assertThat(spool).as("spool 是 Flume 的**输入**，raw 是**输出**").doesNotContain("/raw/");
        assertThat(sinkPath).doesNotContain("/spool");
        assertThat(effectiveLines().stream().filter(line -> line.toUpperCase().contains("TAILDIR")).count())
                .as("两种语义（尾读 vs 完成文件）不得写进同一份配置")
                .isZero();
        assertThat(conf).doesNotContainKey("ingestion.sources.spool.positionFile");
    }

    @Test
    @DisplayName("坏文件不静默跳过：decodeErrorPolicy=FAIL；LINE 反序列化并显式给行宽")
    void badFilesStopTheLineInsteadOfBeingSkipped() {
        Map<String, String> conf = conf();
        assertThat(conf.get("ingestion.sources.spool.decodeErrorPolicy"))
                .as("默认值随版本变化，必须显式写 FAIL：坏文件留在 spool 里等人处理")
                .isEqualTo("FAIL");
        assertThat(conf.get("ingestion.sources.spool.deserializer")).isEqualTo("LINE");
        assertThat(conf.get("ingestion.sources.spool.deserializer.maxLineLength")).isNotNull();
        assertThat(conf.get("ingestion.sources.spool.inputCharset")).isEqualTo("UTF-8");
    }

    @Test
    @DisplayName("配置文本可解析：每行要么注释/空行，要么 key = value（不留半截语法）")
    void everyEffectiveLineIsAKeyValuePair() {
        for (String line : effectiveLines()) {
            assertThat(line).as("生效行必须是 key = value：%s", line).contains("=");
            String key = line.substring(0, line.indexOf('=')).trim();
            assertThat(key).as("键名不得含空白（Flume 会默默读不到）：%s", line).doesNotContain(" ");
        }
    }

    @Test
    @DisplayName("README 指向本配置且标未实测：不允许存在没人认领的配置（也不允许声称已跑通）")
    void readmePointsAtThisConfigAndAdmitsItIsUntested() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);
        assertThat(readme)
                .as("%s 必须指明两条采集路径各用哪个配置", README)
                .contains("flume-spooldir.conf")
                .contains("flume-taildir.conf")
                .contains("未实测");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, String> conf() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : effectiveLines()) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return values;
    }

    /** 去掉注释与空行后的生效行（门禁只看真正会被 Flume 读到的行）。 */
    private static List<String> effectiveLines() {
        try {
            return Files.readAllLines(CONF, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + CONF + " 失败（S2-04B 配置尚未落盘？）", e);
        }
    }

    private static List<String> keysEndingWith(String suffix, Map<String, String> conf) {
        return conf.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(suffix))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
    }

}
