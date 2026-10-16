package com.graduation.analytics.landing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-04B：Landing 输入文件枚举的唯一所有者（设计 §8.2 规则 4：**in-use 临时文件不参与 manifest，
 * 只枚举已完成文件**）。
 *
 * <p>为什么要单独一个枚举器：Flume 的 Spooling Directory Source 在 spool 里会同时存在
 * "正在写"的临时文件（{@code .NAME.tmp}）、它自己的检查点目录（{@code .flumespool/}）与
 * "已完成"的数据文件。平台若按后缀一把抓，就会把**写到一半的文件**当成完成文件采走——
 * 那是把半条 JSON 当数据（残行口径救不了它：Flume 是边写边追加，中间态本身就是坏行来源）。</p>
 *
 * <p>本类不改"滚动日志"布局的既有语义（设计 §8.2 第 6 条 / L268：Flume 目标 spool 与运行中
 * 文件的尾读**不得混为一种配置**）：{@code ROLLING_LOG} 仍然只扫一层、只认 {@code *.jsonl}，
 * 运行中文件靠断点续读（残行等待仍是 {@code LocalFileIngestor} 的既有实现）。</p>
 */
class LandingInputScannerTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private static List<String> keys(Path root, LandingLayout layout) {
        return LandingInputScanner.scan(root, layout).stream()
                .map(LandingInputScanner.ScannedFile::inputKey).toList();
    }

    @Test
    @DisplayName("滚动日志布局：只扫一层、只认 .jsonl（与既有行为逐字节一致）")
    void rollingLogStaysSingleLevelAndJsonlOnly(@TempDir Path landing) throws IOException {
        Path events = landing.resolve("events");
        write(events, "events-0900.jsonl", "{\"a\":1}\n");
        write(events, "events-1000.jsonl", "{\"a\":2}\n");
        write(events, "notes.txt", "not data");
        write(events.resolve("nested"), "events-1100.jsonl", "{\"a\":3}\n");

        assertThat(keys(LandingLayout.ROLLING_LOG.inputRoot(landing), LandingLayout.ROLLING_LOG))
                .as("子目录里的 jsonl 不属于滚动日志布局（那一层是 Flume spool 的形态）")
                .containsExactly("events-0900.jsonl", "events-1000.jsonl");
    }

    @Test
    @DisplayName("Flume 原始区：递归枚举，键＝输入根相对路径（正斜杠），按路径确定性排序")
    void flumeRawIsRecursiveAndDeterministic(@TempDir Path landing) throws IOException {
        Path raw = landing.resolve("raw");
        write(raw.resolve("dt=20260901").resolve("hour=10"), "events.2", "{\"a\":2}\n");
        write(raw.resolve("dt=20260901").resolve("hour=09"), "events.1", "{\"a\":1}\n");
        write(raw.resolve("dt=20260902").resolve("hour=09"), "events.1", "{\"a\":3}\n");

        assertThat(keys(raw, LandingLayout.FLUME_RAW)).containsExactly(
                "dt=20260901/hour=09/events.1",
                "dt=20260901/hour=10/events.2",
                "dt=20260902/hour=09/events.1");
    }

    @Test
    @DisplayName("Flume 原始区：同名不同目录的两个文件是两个输入（键不同，不得互相顶掉）")
    void sameNameInDifferentPartitionsIsTwoInputs(@TempDir Path landing) throws IOException {
        Path raw = landing.resolve("raw");
        write(raw.resolve("dt=20260901").resolve("hour=09"), "events.1", "{\"a\":1}\n");
        write(raw.resolve("dt=20260901").resolve("hour=10"), "events.1", "{\"a\":2}\n");

        List<LandingInputScanner.ScannedFile> scanned = LandingInputScanner.scan(raw, LandingLayout.FLUME_RAW);
        assertThat(scanned).hasSize(2);
        assertThat(scanned).extracting(LandingInputScanner.ScannedFile::inputKey)
                .doesNotHaveDuplicates()
                .as("滚动日志布局下 file_path 只存文件名，同名分区会撞唯一键；相对键正是为避免这件事")
                .containsExactly("dt=20260901/hour=09/events.1", "dt=20260901/hour=10/events.1");
    }

    @Test
    @DisplayName("两种布局都排除：临时文件、隐藏文件、Flume 检查点目录、下划线前缀、零字节文件")
    void incompleteAndHiddenFilesAreNeverEnumerated(@TempDir Path landing) throws IOException {
        Path raw = landing.resolve("raw");
        write(raw, "events.1", "{\"a\":1}\n");
        write(raw, ".events.2.tmp", "{\"a\":2}");         // Flume in-use 临时文件
        write(raw, "events.3.tmp", "{\"a\":3}");          // 生产者遗留的临时后缀
        write(raw, "_events.4", "{\"a\":4}");             // 下划线前缀（约定为未完成）
        write(raw, ".hidden", "{\"a\":5}\n");             // 隐藏文件
        write(raw, "empty", "");                          // 零字节：没有可采内容
        write(raw.resolve(".flumespool"), "checkpoint", "x");  // Flume 检查点目录

        assertThat(keys(raw, LandingLayout.FLUME_RAW)).containsExactly("events.1");

        Path events = landing.resolve("events");
        write(events, "events-0900.jsonl", "{\"a\":1}\n");
        write(events, "events-1000.jsonl.tmp", "{\"a\":2}");   // 写到一半
        write(events, ".events-1100.jsonl", "{\"a\":3}\n");
        write(events, "empty.jsonl", "");

        assertThat(keys(events, LandingLayout.ROLLING_LOG))
                .as("临时/隐藏/零字节同样不进滚动日志布局（规则 4 与布局无关）")
                .containsExactly("events-0900.jsonl");
    }

    @Test
    @DisplayName("输入根不存在／不是目录：返回空集，不抛异常（空批次口径由上层给出）")
    void missingRootYieldsEmptyList(@TempDir Path landing) throws IOException {
        assertThat(LandingInputScanner.scan(landing.resolve("nope"), LandingLayout.FLUME_RAW)).isEmpty();
        Path file = write(landing, "not-a-dir", "x");
        assertThat(LandingInputScanner.scan(file, LandingLayout.FLUME_RAW)).isEmpty();
    }

    @Test
    @DisplayName("候选集 ⊋ 完成集：零字节文件算「已到达」但不属于「可采集」（观测与采集的答案不是同一个集合）")
    void emptyFileIsObservedButNotScanned(@TempDir Path landing) throws IOException {
        Path events = landing.resolve("events");
        write(events, "events-0900.jsonl", "{\"a\":1}\n");
        write(events, "empty.jsonl", "");
        write(events, ".events-1000.jsonl", "{\"a\":2}\n");   // 隐藏：连候选都不是

        List<LandingInputScanner.Candidate> observed =
                LandingInputScanner.inspect(events, LandingLayout.ROLLING_LOG);
        assertThat(observed).extracting(LandingInputScanner.Candidate::inputKey)
                .as("零字节文件确实到达了：状态总览的 pendingFiles/lastArrivalAt 必须看得见它，"
                        + "否则会出现「目录里有文件、到达时间为空」这种自相矛盾的总览")
                .containsExactly("empty.jsonl", "events-0900.jsonl");
        assertThat(observed).filteredOn(LandingInputScanner.Candidate::completed)
                .extracting(LandingInputScanner.Candidate::inputKey)
                .as("但采集只认完成文件（规则 4）：零字节没有内容可采")
                .containsExactly("events-0900.jsonl");
        assertThat(observed).filteredOn(c -> c.inputKey().equals("empty.jsonl"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.size()).isZero();
                    assertThat(c.completed()).isFalse();
                });

        assertThat(keys(events, LandingLayout.ROLLING_LOG))
                .as("scan() ≡ inspect() 的完成子集（两个口径共用同一份排除规则，不得各自一套）")
                .containsExactly("events-0900.jsonl");
    }
}
