package com.graduation.analytics.mapping.dryrun;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.SourcePathPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * {@code sampleRef} 受控引用策略（设计 §7.3 规则 10：「sampleRef 只能引用平台批准的位置，不任意 URL/本机路径」）。
 *
 * <p>本类**不重写**仓库相对/绝对路径/{@code ..}/反斜杠的基本判定：那一条规则的所有者是
 * {@link SourcePathPolicy}，(P1-03 已验收)。这里只补 {@code sampleRef} 独有的四道闸：</p>
 *
 * <ol>
 *   <li><b>协议一律拒</b>：任何 {@code <scheme>:}（{@code file:} {@code http:} {@code https:} {@code hdfs:}
 *       {@code jar:} {@code classpath:} …）直接拒。P1-03 的规则只拦 {@code ^[A-Za-z]:}（盘符），
 *       所以 {@code file://x} 能溜过去，必须有这道闸。</li>
 *   <li><b>不做百分号解码，且拒绝 {@code %}</b>：全链路没有任何解码步骤，{@code %2e%2e} 只会被当作
 *       字面文件名；既然合法样本名不需要 {@code %}，就直接拒，避免"读的人将来加了解码"引发的二次歧义。</li>
 *   <li><b>控制字符与空路径段</b>（{@code a//b}、结尾 {@code /}）拒绝。</li>
 *   <li><b>真实路径二次防线</b>：解析结果即使字面仍在根内，也必须
 *       {@code toRealPath()} 后仍在根的真实路径内，且**不得是符号链接**。</li>
 * </ol>
 *
 * <p>所有拒绝消息都**不回显调用方传入的值**（任务书 §6：响应 DTO 不含绝对本机路径；这正是 P1-03
 * 真机验收踩出来的规则），只回显字段名与脱敏后的长度。</p>
 *
 * <p>「文件是否存在/可读」不在这里判：不存在属于**资源不存在**（404），由调用方按业务码区分；
 * 本类只回答"这个引用是否被允许指向某个受控位置"。</p>
 */
public final class SampleRefPolicy {

    /** 字段名（用于脱敏报错文案，也是 API 的入参名） */
    public static final String FIELD = "sampleRef";

    /** 与 {@link SourcePathPolicy#MAX_LENGTH} 同量级：路径类入参不超过 255 字符 */
    public static final int MAX_LENGTH = SourcePathPolicy.MAX_LENGTH;

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private SampleRefPolicy() {
    }

    /**
     * 校验引用本身（不触盘）。违规抛 {@code PARAM_INVALID}，消息脱敏。
     *
     * @return 去除首尾空白后的引用
     */
    public static String requireSampleRef(String rawRef) {
        // 基本策略（非空/长度/绝对路径/盘符/UNC/反斜杠/.. 段）由 P1-03 的所有者判定
        String ref = SourcePathPolicy.requireRepoRelative(rawRef, FIELD);
        reject(ref, SCHEME.matcher(ref).find(), "不允许协议前缀（URL/URI 一律不受支持）");
        reject(ref, ref.indexOf('%') >= 0, "不允许百分号转义");
        reject(ref, ref.chars().anyMatch(c -> c < 0x20 || c == 0x7f), "不允许控制字符");
        for (String segment : ref.split("/", -1)) {
            if (segment.isEmpty()) {
                reject(ref, true, "不允许空路径段（重复或结尾的分隔符）");
            }
            if (segment.equals(".")) {
                reject(ref, true, "不允许 . 路径段");
            }
        }
        return ref;
    }

    /**
     * 在配置的样本根目录下解析引用（四道闸 + P1-03 包含判定），返回**字面**路径。
     *
     * <p>根目录必须已存在且是目录：它来自平台配置（{@code platform.mapping.sample-root}），
     * 缺失说明部署/配置没准备好，属平台侧故障（{@code IllegalStateException} → 500），
     * **不回落**任何默认目录（D-003 同一条理由：回落到别的目录＝把"样本从哪来"变成猜测）。</p>
     */
    public static Path resolveUnderSampleRoot(Path sampleRoot, String rawRef) {
        String ref = requireSampleRef(rawRef);
        if (!Files.isDirectory(sampleRoot)) {
            throw new IllegalStateException("样本根目录不存在或不是目录（platform.mapping.sample-root）：" + sampleRoot);
        }
        Path resolved = SourcePathPolicy.resolveUnderRoot(sampleRoot, ref, FIELD, "样本");
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return resolved; // 交给调用方按「资源不存在」处理
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    FIELD + " 不得指向符号链接（值已脱敏，未回显）");
        }
        Path realRoot;
        Path real;
        try {
            realRoot = sampleRoot.toRealPath();
            real = resolved.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("解析 sampleRef 真实路径失败：" + resolved, e);
        }
        requireRealPathWithinRoot(realRoot, real);
        return resolved;
    }

    /**
     * 真实路径必须仍在根的真实路径内（第四道闸的判定本体）。
     *
     * <p>单独成方法是为了让这条规则可以被**与文件系统无关**地测到：符号链接需要操作系统权限
     * （Windows 未开开发者模式时根本建不出来），若把判定和 {@code toRealPath()} 揉在一起，
     * 这条规则就只有在"能建符号链接的机器"上才被测过。判定本体用纯路径即可穷举。</p>
     */
    static void requireRealPathWithinRoot(Path realRoot, Path real) {
        if (!real.startsWith(realRoot)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    FIELD + " 的真实路径逃逸出样本根目录（值已脱敏，未回显）");
        }
    }

    private static void reject(String ref, boolean condition, String reason) {
        if (condition) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    FIELD + " " + reason + "（值已脱敏，未回显；长度 " + ref.length() + "）");
        }
    }
}
