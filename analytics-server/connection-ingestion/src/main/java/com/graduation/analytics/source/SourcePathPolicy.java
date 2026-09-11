package com.graduation.analytics.source;

import com.graduation.analytics.common.PlatformBizException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * {@code profile_path} 路径策略（P1-03）：只接受**仓库相对、POSIX 分隔、不含 {@code ..}** 的路径。
 *
 * <p>为什么必须在写入前拦：{@code profile_path} 会出现在 API 响应里、会被服务进程用于读文件。
 * 若允许绝对路径，登记表就变成了「本机目录结构」的副本——换一台机器验收就全错，
 * 而且响应会泄露部署机路径（任务书 §6：DTO 不含绝对本机路径）。</p>
 *
 * <p>严格程度的取舍：连反斜杠也拒（Windows 风格分隔符一律不写进登记表），
 * 因为 {@code \} 在 POSIX 上是合法文件名字符，两种风格混用会让"同一个路径"有两种写法、
 * 使唯一性与比对失效。宁可让调用方改正，也不做隐式归一化。</p>
 */
public final class SourcePathPolicy {

    /** 与 V16 的 {@code profile_path VARCHAR(255)} 对齐，超长直接拒（而不是让数据库截断） */
    public static final int MAX_LENGTH = 255;

    private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:");
    private static final Pattern UNC = Pattern.compile("^\\\\");

    private SourcePathPolicy() {
    }

    /** 是否满足仓库相对路径策略（不抛异常，供测试与明细项复用） */
    public static boolean isRepoRelative(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        String path = rawPath.trim();
        if (path.length() > MAX_LENGTH) {
            return false;
        }
        if (path.startsWith("/") || DRIVE_LETTER.matcher(path).find() || UNC.matcher(path).find()) {
            return false;
        }
        if (path.contains("\\")) {
            return false;
        }
        try {
            if (Path.of(path).isAbsolute()) {
                return false;
            }
        } catch (InvalidPathException e) {
            return false;
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 断言仓库相对；不满足则 {@code PARAM_INVALID}。
     *
     * <p><b>消息里绝不回显调用方传来的值</b>（任务书 §6：响应 DTO 不含绝对本机路径）。
     * 这条规则是被真机验收逼出来的：E3 首次运行时 {@code PUT} 传绝对路径拿到的 400 消息
     * 原样带回了 {@code D:\Develop_code\...}，而这条消息还会被控制器写进
     * {@code operation_audit_log.reason}——一次误传就把部署机的目录结构复制进响应与审计表。
     * 调用方本来就知道自己传了什么，回显零信息量、纯风险，故一律脱敏。</p>
     */
    public static String requireRepoRelative(String rawPath, String fieldName) {
        if (!isRepoRelative(rawPath)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    fieldName + " 必须是仓库相对路径（POSIX 分隔符、不含 ..、非绝对路径、最长 "
                            + MAX_LENGTH + " 字符）；收到的值违反该策略，已脱敏不回显（长度 "
                            + (rawPath == null ? 0 : rawPath.length()) + "）");
        }
        return rawPath.trim();
    }

    /**
     * 在配置的画像根目录下解析路径。
     *
     * <p>二次防线：即使策略被绕过，解析结果也必须仍在根目录之内——否则抛 {@code PARAM_INVALID}，
     * 绝不把根目录之外的文件当画像读。消息同样不回显原值（见
     * {@link #requireRepoRelative(String, String)}）。</p>
     */
    public static Path resolveUnderRoot(Path root, String rawPath) {
        requireRepoRelative(rawPath, "profile_path");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(rawPath.trim()).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "profile_path 逃逸出画像根目录（值已脱敏，未回显）");
        }
        return resolved;
    }
}
