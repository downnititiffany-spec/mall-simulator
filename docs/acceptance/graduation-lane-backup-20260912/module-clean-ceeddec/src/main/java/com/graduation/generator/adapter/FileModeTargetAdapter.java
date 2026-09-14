package com.graduation.generator.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件模式目标（{@code adapter_type=CANONICAL_EVENT_FILE}，§3.3 B）的探测：
 * 在目标输出目录真实建目录、写探针文件、确认可见、再删除。
 *
 * <p>这段逻辑原本在 {@code TargetProbeService} 里，S4a 把它挪到适配器：探测的<b>所有者</b>应当是适配器，
 * 服务只负责"按 {@code adapter_type} 找适配器"。挪动没有改口径——{@code landing} 目录禁写规则与
 * 探针文件清理行为逐条保留（原测试 {@code targetsCrudAndProbeAreReal} 的断言即是回归网）。</p>
 *
 * <p>能力表为空：文件目标没有"商城能力"可言，硬塞一个 {@code file_write} 之类的新词汇只会污染
 * {@link MallCapability} 的语义。</p>
 */
public final class FileModeTargetAdapter implements MallTargetAdapter {

    private static final Logger log = LoggerFactory.getLogger(FileModeTargetAdapter.class);

    /** 与 §3.3 B 的模式名同字面量（{@code GenerationRunService.MODE_CANONICAL_EVENT_FILE}） */
    public static final String ADAPTER_TYPE = "CANONICAL_EVENT_FILE";

    private static final String PROBE_FILE = ".generator-probe";
    private static final Map<MallCapability, CapabilityVerdict> NO_CAPABILITIES = Map.of();

    private final Path outputRoot;

    public FileModeTargetAdapter(String outputRoot) {
        this.outputRoot = Path.of(outputRoot).toAbsolutePath().normalize();
    }

    public FileModeTargetAdapter() {
        this("./generator-output");
    }

    @Override
    public String adapterType() {
        return ADAPTER_TYPE;
    }

    @Override
    public TargetCheckResult test(TargetConfig config) {
        Path dir = config.baseUrl() == null || config.baseUrl().isBlank()
                ? outputRoot
                : Path.of(config.baseUrl()).toAbsolutePath().normalize();
        if (isLanding(dir)) {
            return new TargetCheckResult(config.id(), false,
                    "输出目录位于分析平台 landing 目录下，文件模式禁止写入（V2.1 §3.3 B）：" + dir,
                    NO_CAPABILITIES);
        }
        Path probe = dir.resolve(PROBE_FILE);
        try {
            Files.createDirectories(dir);
            Files.writeString(probe, "generator-probe");
            boolean written = Files.isRegularFile(probe);
            Files.deleteIfExists(probe);
            return new TargetCheckResult(config.id(), written,
                    written ? "输出目录可写并已清理探针文件：" + dir : "探针文件写入后不可见：" + dir,
                    NO_CAPABILITIES);
        } catch (IOException e) {
            log.warn("文件目标检查失败：id={} dir={}", config.id(), dir, e);
            return new TargetCheckResult(config.id(), false,
                    "输出目录不可写：" + dir + "（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）",
                    NO_CAPABILITIES);
        }
    }

    private static boolean isLanding(Path path) {
        for (Path segment : path) {
            if ("landing".equalsIgnoreCase(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
