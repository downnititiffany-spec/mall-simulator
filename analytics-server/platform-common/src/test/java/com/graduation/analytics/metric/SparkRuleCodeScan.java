package com.graduation.analytics.metric;

import com.graduation.analytics.warehouse.WarehouseNameLiteralScanner.CommentSyntax;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Spark 生产源码的「大写字面量」提取器（S3-49 规则码漂移门禁的扫描面）。
 *
 * <p><b>为什么需要它</b>：规则码的唯一所有者是 Java 侧 {@code RuleSeverity.REGISTERED}
 * （{@code platform-common}），而**真正执行**这些规则的代码在 {@code spark-jobs}（Scala/JDK8）。
 * 两侧只靠字面量对齐 —— 此前**只在一侧改码没有任何自动守卫**（{@code PROJECT_STATUS.md}
 * backlog 行原文：「「Spark 规则码字面量 ↔ 登记集」没有自动守卫」，S3-10 是靠人工发现的；
 * 读侧按 §7.3.1 判「未登记规则」直接拒发）。本类只做**提取**，不判类；
 * 「哪些码算已登记、哪些 token 不是规则码」由
 * {@code SparkRuleCodeRegistryGuardTest} 用登记表与显式非规则 token 表判定。</p>
 *
 * <p><b>扫描面</b>：{@code spark-jobs/src/main/scala/**} 下的 {@code .scala}
 * （**不含测试树**：测试里的码是断言期望值，不是第二处所有者 —— 与库名门禁同一口径）。
 * 生产文本文件数、三个规则码承载文件都另有下限/在范围断言（见守卫），防止范围失效伪装成通过。</p>
 *
 * <p><b>注释剥离复用唯一实现</b>：{@link CommentSyntax#strip}(text, {@code SLASH}) 来自
 * {@code WarehouseNameLiteralScanner}（该类的行为由 {@code WarehouseNameLiteralGateTest.lexerFamilies}
 * 钉住）。本类**不另写**第二份注释剥离器 —— 两份剥离口径就是新的第二所有者。
 * 剥离只把注释区间换成空格，行数与行号不变，因此命中位置可直接引用。</p>
 *
 * <p><b>提取口径（宽口径，不断言语义）</b>：字符串字面量内容匹配 {@code ^[A-Z][A-Z0-9_]{3,}$}
 * 即记一处（{@code file} 为仓库相对路径、正斜杠；{@code line} 为 1 起始行号）。
 * 之所以用「宽口径 ＋ 显式非规则 token 表」而不是「只抓 {@code QualityCheck(} 首参」：
 * 后者看不见 {@code AdsQualityJob.scala:94} 的 {@code Set("AMOUNT_RECONCILE", …)} 策略表与
 * {@code :107} 的 {@code byRule.get("EVENT_ID_UNIQUE")}（实测存在的另两类站点），
 * 而这两种写法的码同样会进库、同样会被读侧按登记表判定。</p>
 *
 * <p><b>已知边界（不夸大）</b>：①只认双引号里的**整串**大写 token（{@code s"$prefix_CODE"} 这类
 * 拼接码抓不到 —— 实测 Spark 生产树里没有这种写法）；②不解析 Scala 语法（纯文本 ＋ 词法剥注释），
 * 因此「这个字面量是不是规则码」的判断权在守卫的非规则 token 表里，本类不做猜测；
 * ③只扫 {@code src/main/scala}，不含 {@code src/main/resources}。</p>
 */
final class SparkRuleCodeScan {

    /** 扫描面（仓库相对路径） */
    static final String SPARK_MAIN = "spark-jobs/src/main/scala";

    /** 大写字面量形态：至少 4 字符，避免把 {@code "ADS"}/{@code "DWS"} 这类层名短 token 混进来 */
    static final Pattern UPPER_LITERAL = Pattern.compile("\"([A-Z][A-Z0-9_]{3,})\"");

    private SparkRuleCodeScan() {
    }

    /** 一处字面量命中（仓库相对路径、1 起始行号、token 原文） */
    record Lit(String file, int line, String code) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + code;
        }
    }

    /** 一次扫描的结果：进范围的文件 ＋ 全部命中（按文件/行号排序） */
    record Result(List<Path> scanned, List<Lit> literals) {

        /** 去重后的 token（保持字典序，便于稳定报错） */
        List<String> codes() {
            Set<String> distinct = new LinkedHashSet<>();
            literals.forEach(l -> distinct.add(l.code()));
            return distinct.stream().sorted().toList();
        }

        /** 某 token 的命中数 */
        long countOf(String code) {
            return literals.stream().filter(l -> l.code().equals(code)).count();
        }
    }

    /** 扫描仓根下的 Spark 生产树 */
    static Result scan(Path root) {
        Path dir = root.resolve(SPARK_MAIN);
        List<Path> files = scalaFiles(dir);
        List<Lit> hits = new ArrayList<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String stripped = CommentSyntax.strip(read(file), CommentSyntax.SLASH);
            String[] lines = stripped.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = UPPER_LITERAL.matcher(lines[i]);
                while (m.find()) {
                    hits.add(new Lit(rel, i + 1, m.group(1)));
                }
            }
        }
        hits.sort(Comparator.comparing(Lit::file).thenComparingInt(Lit::line).thenComparing(Lit::code));
        return new Result(List.copyOf(files), List.copyOf(hits));
    }

    /** {@code src/main/scala} 下的 {@code .scala} 文件（跳过 {@code target}，按路径排序 ⇒ 结果稳定） */
    static List<Path> scalaFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("扫描面不存在（门禁不得空跑）: " + dir);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".scala"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历失败: " + dir, e);
        }
    }

    /** 读文件（UTF-8）；失败直接抛，不静默当空文件 */
    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + file, e);
        }
    }
}
