package com.graduation.analytics.landing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Landing 输入文件的枚举（S2-04B）——**"哪些文件算输入"的唯一所有者**（设计 §8.2 规则 4）。
 *
 * <p>设计原文：in-use 临时文件**不得进入清单**，只枚举**已完成**文件。Flume Spooling Directory Source
 * 的落地形态会让同一个 spool 目录里同时存在：正在写的 {@code .NAME.tmp}、它自己的检查点目录
 * {@code .flumespool/}、以及写完并原子改名后的数据文件。按后缀一把抓就会把**写到一半的文件**
 * 当完成文件采走——那不是"残行"（残行是完整行之后多出的半行，等下一个换行即可），
 * 而是**首尾都不完整**的中间态，残行口径救不了。</p>
 *
 * <p>排除规则（两种布局共用，规则 4 与布局无关）：隐藏文件（{@code .} 前缀）、
 * {@code _} 前缀（生产者约定的未完成标记）、{@code *.tmp} 后缀（不区分大小写）、非普通文件。
 * 零字节文件**是候选但不完成**（见 {@link #inspect}）：采集不要它，观测要看得见它。
 * {@link LandingLayout#ROLLING_LOG} 额外只认 {@code *.jsonl}，且**不递归**——
 * 它的运行中文件由断点续读负责（设计 §8.2 L268：不得把 Flume 目标 spool 与运行中的文件尾读
 * 混写成同一种配置）。</p>
 *
 * <p><b>它不是第二个路径规范化所有者</b>：断点键（{@code LocalFileIngestor.checkpointKey}，
 * 规范化绝对路径）仍由 {@code LocalFileIngestor} 唯一拥有；这里给出的是**清单/批次账的输入身份**，
 * 即输入根相对路径、统一正斜杠（{@code dt=20260901/hour=09/events.1}）。
 * 两者用途不同、不得互相替代：前者定位"同一物理文件读到哪"，后者回答"本批次登记的是哪一个输入"。
 * 滚动日志布局下相对路径恰好就是文件名，因此既有批次账的写法逐字节不变。</p>
 */
public final class LandingInputScanner {

    private LandingInputScanner() {
    }

    /** {@code inputKey}＝输入根相对路径（正斜杠）；{@code file}＝用于读取的真实路径。 */
    public record ScannedFile(Path file, String inputKey) {
    }

    /**
     * **候选**文件（观测口径）：形状上属于本布局输入区的文件，{@code completed}＝有内容可读。
     *
     * <p>零字节文件是候选但**未完成**：它不会被采走（规则 4），但它确实"到达了"
     * ——状态总览的 {@code pendingFiles}/{@code lastArrivalAt} 要能看见它，否则运维会看到
     * 「目录里有文件、到达时间为空」。两个问题的所有者是同一个类，但**答案不是同一个集合**。</p>
     */
    public record Candidate(Path file, String inputKey, long size, boolean completed) {
    }

    /**
     * 枚举**候选**文件（含零字节的未完成文件），按 {@code inputKey} 升序。
     *
     * <p>用途是**观测**（`status()` 的 pendingFiles/pendingBytes/lastArrivalAt/checkpointFiles/
     * newFileCount）：这些字段回答"源还在不在产出"，不是"这一轮要采哪些"。采集必须用
     * {@link #scan}——把候选当输入会把写到一半的文件采走。</p>
     */
    public static List<Candidate> inspect(Path inputRoot, LandingLayout layout) {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = layout.recursive() ? Files.walk(inputRoot) : Files.list(inputRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !isExcludedByShape(inputRoot, path, layout))
                    .map(path -> candidate(inputRoot, path))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Candidate::inputKey))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Landing 输入枚举失败: " + inputRoot, e);
        }
    }

    /**
     * 枚举 {@code inputRoot} 下的**已完成**输入文件，按 {@code inputKey} 升序（确定性批次顺序）。
     *
     * <p>{@code inputRoot} 不存在或不是目录 ⇒ 空集（"空批次"口径由上层给出，不在这里编造文件）。
     * 枚举本身出错 ⇒ 抛 {@link UncheckedIOException}：**宁可整轮失败也不静默少读文件**。</p>
     */
    public static List<ScannedFile> scan(Path inputRoot, LandingLayout layout) {
        return inspect(inputRoot, layout).stream()
                .filter(Candidate::completed)
                .map(candidate -> new ScannedFile(candidate.file(), candidate.inputKey()))
                .toList();
    }

    private static Candidate candidate(Path inputRoot, Path path) {
        try {
            long size = Files.size(path);
            return new Candidate(path, inputKey(inputRoot, path), size, size > 0L);
        } catch (IOException e) {
            // 读不到大小 ⇒ 既不观测也不采集：宁可少这一行，也不把"大小未知"的文件当输入。
            // 该文件下一轮仍在目录里，缺口是可观测的（不是静默丢数据）。
            return null;
        }
    }

    /** 输入身份：输入根相对路径，统一正斜杠（与 {@code checklist} 的 Windows/容器两种部署都无关）。 */
    public static String inputKey(Path inputRoot, Path file) {
        return inputRoot.relativize(file).toString().replace('\\', '/');
    }

    /** 只看**形状**（不看大小）：这段判定与"是否已完成"无关，两个口径共用同一份排除规则。 */
    private static boolean isExcludedByShape(Path inputRoot, Path file, LandingLayout layout) {
        String name = file.getFileName().toString();
        // 隐藏/未完成判定必须看**相对路径的每一段**，不能只看文件名：Flume 的检查点目录
        // `.flumespool/` 里的文件名字很正常（checkpoint、.lock），只看文件名会把 Flume 的
        // 检查点当成数据文件采走。`_` 前缀同理（Spark/HDFS 的 `_temporary` 之类）。
        if (hasExcludedSegment(inputRoot, file)) {
            return true;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".tmp")) {
            return true;
        }
        return !layout.recursive() && !lower.endsWith(".jsonl");
    }

    /** 相对输入根的任意一段以 {@code .} 或 {@code _} 开头 ⇒ 该文件不算输入（含它所在的整个目录）。 */
    private static boolean hasExcludedSegment(Path inputRoot, Path file) {
        for (Path segment : inputRoot.relativize(file)) {
            String name = segment.toString();
            if (name.startsWith(".") || name.startsWith("_")) {
                return true;
            }
        }
        return false;
    }
}
