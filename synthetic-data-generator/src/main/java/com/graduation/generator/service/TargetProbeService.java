package com.graduation.generator.service;

import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.TargetRow;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetCheckView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * §4.4 L158 的 {@code POST /targets/{id}/test}：目标商城连通性检查。
 *
 * <p><b>只做能真做的检查，不造"检查项"</b>：契约对 {@code TargetCheckResult} 没有声明任何字段，
 * 并明确"不发明『检查项/是否通过/耗时』等字段"。所以这里只回三件事：目标 id、是否可达、以及一句
 * 可核对的过程说明（成功是什么、失败是什么异常）。</p>
 *
 * <p>两种目标各自做真实检查：</p>
 * <ul>
 *   <li>HTTP 目标（{@code base_url} + {@code adapter_type} 指向商城 REST）：对 {@code host:port} 建立真实 TCP 连接
 *       （2 秒超时）。这只能证明"端口可达"，**不能**证明商品/行为接口可用——那属于 S4 的
 *       {@code ReferenceMallHttpAdapter}，且行为接口是否存在取决于 B-04 裁决，故在 detail 里如实声明。</li>
 *   <li>文件目标（{@code adapter_type=CANONICAL_EVENT_FILE}）：在目标输出目录真实建目录、写探针文件、再删除，
 *       并复用 {@code landing} 目录禁写规则，检查的是"能不能落盘"。</li>
 * </ul>
 */
@Service
public class TargetProbeService {

    private static final Logger log = LoggerFactory.getLogger(TargetProbeService.class);

    /** 文件模式目标的适配器标识（与 §3.3 B 的模式名一致，避免另造一套命名） */
    public static final String ADAPTER_CANONICAL_EVENT_FILE = "CANONICAL_EVENT_FILE";

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final String PROBE_FILE = ".generator-probe";

    private final GeneratorMetaStore store;
    private final Path outputRoot;

    public TargetProbeService(GeneratorMetaStore store,
                              @Value("${generator.output.root:./generator-output}") String outputRoot) {
        this.store = store;
        this.outputRoot = Path.of(outputRoot).toAbsolutePath().normalize();
    }

    public TargetCheckView probe(long targetId) {
        TargetRow target = store.findTarget(targetId)
                .orElseThrow(() -> new GenerationRunService.RunNotFoundException("目标不存在：id=" + targetId));
        if (ADAPTER_CANONICAL_EVENT_FILE.equalsIgnoreCase(target.adapterType())) {
            return probeFileTarget(target);
        }
        return probeHttpTarget(target);
    }

    private TargetCheckView probeFileTarget(TargetRow target) {
        Path dir = target.baseUrl() == null || target.baseUrl().isBlank()
                ? outputRoot
                : Path.of(target.baseUrl()).toAbsolutePath().normalize();
        if (isLanding(dir)) {
            return new TargetCheckView(target.id(), false,
                    "输出目录位于分析平台 landing 目录下，文件模式禁止写入（V2.1 §3.3 B）：" + dir);
        }
        Path probe = dir.resolve(PROBE_FILE);
        try {
            Files.createDirectories(dir);
            Files.writeString(probe, "generator-probe");
            boolean written = Files.isRegularFile(probe);
            Files.deleteIfExists(probe);
            return new TargetCheckView(target.id(), written,
                    written ? "输出目录可写并已清理探针文件：" + dir : "探针文件写入后不可见：" + dir);
        } catch (IOException e) {
            log.warn("文件目标检查失败：id={} dir={}", target.id(), dir, e);
            return new TargetCheckView(target.id(), false,
                    "输出目录不可写：" + dir + "（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）");
        }
    }

    private TargetCheckView probeHttpTarget(TargetRow target) {
        if (target.baseUrl() == null || target.baseUrl().isBlank()) {
            return new TargetCheckView(target.id(), false, "base_url 为空，无法做连通性检查");
        }
        URI uri;
        try {
            uri = URI.create(target.baseUrl());
        } catch (IllegalArgumentException e) {
            return new TargetCheckView(target.id(), false, "base_url 不是合法 URI：" + target.baseUrl());
        }
        String host = uri.getHost();
        if (host == null) {
            return new TargetCheckView(target.id(), false, "base_url 缺少 host：" + target.baseUrl());
        }
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return new TargetCheckView(target.id(), true,
                    "TCP 可达 %s:%d（%d ms 超时）；仅证明端口可达，商品/行为接口能力属 S4 适配器，本次未验证"
                            .formatted(host, port, CONNECT_TIMEOUT_MS));
        } catch (IOException e) {
            return new TargetCheckView(target.id(), false,
                    "TCP 不可达 %s:%d（%s: %s）".formatted(host, port, e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    private static int defaultPort(String scheme) {
        String normalized = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "https" -> 443;
            default -> 80;
        };
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
