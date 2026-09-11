package com.graduation.analytics.common;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Landing 根路径解析的**唯一实现**（V2.1 §5.4/§6.2）。
 *
 * <p>为什么必须只有一处：同一段 {@code file://} 前缀截断逻辑原先在
 * {@code IngestionService}、{@code PipelineService}、{@code LocalLandingStorage} 各写了一遍，
 * 且都把标准写法 {@code file:///D:/landing} 截成 {@code /D:/landing}——在 Windows 上直接
 * {@code InvalidPathException}，在 Linux 上会得到一个形如 {@code /D:/landing} 的错误目录，
 * 而 {@code hdfs://...} 会被静默当成相对目录。三份实现意味着三种不同的错法。</p>
 *
 * <p>支持的写法：</p>
 * <ul>
 *   <li>标准 URI：{@code file:///D:/landing}、{@code file:///data/landing}</li>
 *   <li>盘符无斜杠：{@code file://D:/landing}</li>
 *   <li>历史相对写法：{@code file://./landing}、裸路径 {@code ./landing} / {@code landing}</li>
 * </ul>
 *
 * <p>明确拒绝（报错而不是猜）：空值、主机名形式 {@code file://host/share}（含义有歧义）、
 * 非 file 协议 {@code hdfs://} 等（集群落地的解析属于连接器职责，见 §5.3）。</p>
 */
public final class LandingUri {

    private LandingUri() {
    }

    /** 解析 landingUri/路径为本地绝对路径；不可解析时抛 {@link MallBizException}（不返回默认目录）。 */
    public static Path resolve(String landingUri) {
        if (landingUri == null || landingUri.isBlank()) {
            throw new MallBizException(MallBizException.INTERNAL,
                    "landingUri 为空，无法定位 Landing 根（V2.1 §6.2）");
        }
        String raw = landingUri.trim();
        if (raw.startsWith("file://")) {
            String rest = raw.substring("file://".length());
            if (isWindowsDriveWithLeadingSlash(rest)) {
                // file:///D:/landing → /D:/landing → D:/landing
                rest = rest.substring(1);
            } else if (!isAcceptableFileUriTail(rest)) {
                throw new MallBizException(MallBizException.INTERNAL,
                        "不明 landingUri（file:// 后应接 file:///data/landing、file://./landing 或 file://D:/landing）：" + raw);
            }
            return Paths.get(rest).toAbsolutePath().normalize();
        }
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd > 0) {
            throw new MallBizException(MallBizException.INTERNAL,
                    "暂不支持的 landingUri 协议：" + raw.substring(0, schemeEnd) + "://（当前仅支持本地 file:// 与裸路径）");
        }
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    /** {@code /D:/...}：POSIX 绝对路径里夹着 Windows 盘符，是 file:/// 三斜杠的正常产物 */
    private static boolean isWindowsDriveWithLeadingSlash(String rest) {
        return rest.length() > 2 && rest.charAt(0) == '/' && rest.charAt(2) == ':';
    }

    /** {@code /data/...}（POSIX 绝对）、{@code ./...}（相对）、{@code D:/...}（盘符裸写）为合法尾部 */
    private static boolean isAcceptableFileUriTail(String rest) {
        if (rest.startsWith("/") || rest.startsWith(".")) {
            return true;
        }
        return rest.length() > 1 && rest.charAt(1) == ':';
    }
}
