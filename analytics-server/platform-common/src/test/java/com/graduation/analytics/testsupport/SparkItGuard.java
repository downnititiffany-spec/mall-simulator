package com.graduation.analytics.testsupport;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 真实 Spark 冒烟用例的**目录写入门禁**（V25-S03 R-5）。
 *
 * <h3>整改前的问题</h3>
 * <p>{@code SparkStageExecutorSmokeIT}（V25-S03 前名为 {@code …SmokeTest}，总控 2026-09-14 改名，
 * 使其退出 surefire 默认套件）用仓库内固定目录
 * {@code <repo>/tests/r6-smoke-warehouse} 当工作根，入口处</p>
 * <pre>
 * private static final Path SMOKE_ROOT = Paths.get("tests", "r6-smoke-warehouse").toAbsolutePath();
 * if (Files.exists(SMOKE_ROOT)) {
 *     Files.walk(SMOKE_ROOT).sorted(reverseOrder()).forEach(p -&gt; Files.deleteIfExists(p));
 * }
 * </pre>
 * <p>三个问题：① <b>没有门禁</b>——直接 {@code mvn test} 就会启动真实 {@code spark-submit}
 * （外部进程、Derby 元数据库、写 HDFS 风格目录），没有任何"默认关闭"；
 * ② {@code SMOKE_ROOT} 是<b>常量</b>，一旦有人把它改成 {@code tests}、{@code .} 或仓库根，
 * 这段递归删除就会把整个目录树清掉；③ 删除是<b>无预览</b>的——
 * {@code forEach} 边遍历边删，出问题时没有"将要删什么"的记录。</p>
 *
 * <h3>整改后的判据</h3>
 * <ol>
 *   <li><b>默认关闭</b>：{@code -Dv25.spark.it=true} 才允许跑真实 Spark。
 *       未开启时用例显式失败并说明原因（不是 skip 冒充通过）。</li>
 *   <li><b>目标根白名单校验</b>：允许根由 {@code -Dv25.spark.it.root} 指定；
 *       未指定时回落到 {@code <repo>/target/v25-spark-it}（{@code target/} 是构建产物目录，
 *       绝不可能是仓库源码目录）。解析结果必须**严格位于**允许根之内，
 *       且允许根本身不得是仓库根、不得是仓库根的一级子目录（{@code target/} 除外）。</li>
 *   <li><b>只清理本次 runId 拥有的目录</b>：实际工作目录是
 *       {@code <允许根>/<testRunId>/}，删除**只**作用于该目录——不再有"固定常量目录"。</li>
 *   <li><b>删除前先列出待删目标</b>：把待删文件的相对路径与数量打印出来，
 *       再执行删除；证据里能事后核对"它当时打算删什么"。</li>
 * </ol>
 *
 * <p>凡触库/触 HDFS 的部分等 W03；本类只做门禁与"拒跑"，不做任何集群操作。</p>
 */
public final class SparkItGuard {

    /** 显式开启开关：默认关闭。 */
    public static final String ENABLED_KEY = "v25.spark.it";

    /** 允许根覆盖项。 */
    public static final String ROOT_KEY = "v25.spark.it.root";

    /** 允许根的默认回落位置（构建产物目录，不是源码目录）。 */
    public static final String DEFAULT_ROOT_RELATIVE = "target/v25-spark-it";

    /** 递归删除的规模上限：超出即拒绝（防"删到别的东西"时把整个盘扫一遍）。 */
    private static final int MAX_DELETE_ENTRIES = 200_000;

    private SparkItGuard() {
    }

    /** 是否显式开启真实 Spark 冒烟。 */
    public static boolean enabled() {
        return "true".equalsIgnoreCase(String.valueOf(System.getProperty(ENABLED_KEY, "false")).trim());
    }

    /**
     * 开启门禁：未显式开启即拒绝。
     *
     * <p>抛异常而不是 {@code assumeTrue} —— 「没开开关」必须表现为红，不能表现为 skipped。</p>
     */
    public static void requireEnabled(String who) {
        if (!enabled()) {
            throw new TestIsolationGuard.MissingConfigurationException("[" + who
                    + "] 真实 Spark 冒烟默认关闭：未提供 -D" + ENABLED_KEY + "=true。"
                    + "本用例会启动真实 spark-submit（外部进程）并递归删除工作目录，"
                    + "必须显式声明后才允许运行（不提供 skip 形态的通过）。");
        }
    }

    /** 允许根（绝对、规范化）。 */
    public static Path allowedRoot() {
        String configured = System.getProperty(ROOT_KEY);
        Path root;
        if (configured == null || configured.isBlank()) {
            root = RepoRoot.path().resolve(DEFAULT_ROOT_RELATIVE);
        } else {
            String value = configured.trim();
            if (value.contains("://")) {
                throw new TestIsolationGuard.IsolationViolationException("[" + ROOT_KEY
                        + "] 不接受 URI 形态的目标根：" + value + "（只允许本地文件系统路径）");
            }
            root = Paths.get(value).toAbsolutePath().normalize();
        }
        assertRootIsSafe(root);
        return root;
    }

    private static void assertRootIsSafe(Path root) {
        Path repo = RepoRoot.path().toAbsolutePath().normalize();
        // 0. 允许根本身就是文件系统根（如 D:\）时 getParent() 为 null，
        //    下面"过于靠近盘根"那条判据整条失效——探针案例 2.5a 就是这样漏过的。
        //    显式挡住：允许根不得是文件系统根。
        if (root.getParent() == null || root.equals(root.getRoot())) {
            throw new TestIsolationGuard.IsolationViolationException("允许根 " + root
                    + " 是文件系统根/盘根：递归删除会清掉整盘，拒绝运行。");
        }
        if (root.equals(repo)) {
            throw new TestIsolationGuard.IsolationViolationException(
                    "允许根等于仓库根：" + root + " —— 递归删除会清掉仓库，拒绝运行。");
        }
        if (root.getParent() != null && root.getParent().equals(repo)
                && !root.getFileName().toString().equals("target")) {
            throw new TestIsolationGuard.IsolationViolationException("允许根 " + root
                    + " 是仓库根的一级子目录（如 tests/、docs/、src/）：递归删除风险过高，拒绝运行。"
                    + "请用 " + DEFAULT_ROOT_RELATIVE + " 或 -D" + ROOT_KEY + " 指定更深的白名单目录。");
        }
        if (!repo.startsWith(root) && root.startsWith(repo.getRoot())
                && root.getNameCount() <= repo.getRoot().getNameCount() + 1) {
            throw new TestIsolationGuard.IsolationViolationException("允许根 " + root
                    + " 过于靠近盘根：拒绝运行。");
        }
    }

    /**
     * 本次运行的工作根：{@code <允许根>/<testRunId>/}。
     *
     * <p>路径判据：必须绝对、末级目录名必须含本次 testRunId、不得落在
     * {@code /graduation/**} 集群路径下。全部用公开 API / 本地判据复核，不复制实现。</p>
     */
    public static Path runRoot() {
        // 必须走 requireTestRunId()：requiredProperty 只保证"存在"，
        // 不保证形状。这里要把 runId 拼进目录名，非法形状（空格、/、..）会让
        // "只清理本次 runId 拥有的目录"这条判据失去意义。
        String runId = TestIsolationGuard.requireTestRunId();
        Path root = allowedRoot();
        Path scoped = root.resolve(runId).normalize();
        if (!scoped.isAbsolute()) {
            throw new TestIsolationGuard.IsolationViolationException("工作目录不是绝对路径：" + scoped);
        }
        if (!scoped.startsWith(root)) {
            throw new TestIsolationGuard.IsolationViolationException("工作目录 " + scoped
                    + " 逃出了允许根 " + root + "：拒绝运行。");
        }
        if (!scoped.getFileName().toString().contains(runId)) {
            throw new TestIsolationGuard.IsolationViolationException("工作目录 " + scoped
                    + " 末级目录名不含本次 testRunId（" + runId + "）：拒绝运行。");
        }
        String normalized = scoped.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/graduation/")) {
            throw new TestIsolationGuard.IsolationViolationException(
                    "工作目录落在集群路径 /graduation/** 下：" + scoped + "：拒绝运行。");
        }
        return scoped;
    }

    /**
     * 删除**本次 runId 拥有**的目录：先列出待删目标，再删。
     *
     * @return 实际删除的条目数
     */
    public static int deleteOwnedRunDir() throws IOException {
        Path target = runRoot();
        Path root = allowedRoot();
        // 再次确认：只允许删 <允许根>/<testRunId>，不允许删允许根本身
        if (target.equals(root) || !target.startsWith(root) || target.getParent() == null
                || !target.getParent().equals(root)) {
            throw new TestIsolationGuard.IsolationViolationException("拒绝删除 " + target
                    + "：只允许删除允许根 " + root + " 下、以本次 testRunId 命名的那一个目录。");
        }
        if (!Files.exists(target)) {
            System.out.println("[spark-it] 待删目录不存在（无需清理）：" + target);
            return 0;
        }
        List<String> toDelete = listForDeletion(target);
        System.out.println("[spark-it] 清理预览：根=" + target + " 待删条目数=" + toDelete.size()
                + "（仅限本次 runId 拥有的目录）");
        for (String relative : toDelete) {
            System.out.println("[spark-it]   将删除: " + relative);
        }
        deleteRecursively(target);
        System.out.println("[spark-it] 清理完成：已删除 " + (toDelete.size() + 1) + " 个条目（含目录本身）");
        return toDelete.size() + 1;
    }

    private static List<String> listForDeletion(Path root) throws IOException {
        List<Path> all = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.forEach(all::add);
        }
        if (all.size() > MAX_DELETE_ENTRIES) {
            throw new TestIsolationGuard.IsolationViolationException("待删条目数 " + all.size()
                    + " 超过上限 " + MAX_DELETE_ENTRIES + "：路径可能不是测试工作目录，拒绝删除。");
        }
        List<String> relative = new ArrayList<>(all.size());
        for (Path p : all) {
            relative.add(root.relativize(p).toString());
        }
        relative.sort(Comparator.naturalOrder());
        return relative;
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 供证据采集：一行描述当前门禁状态（不含口令）。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(enabled());
        try {
            sb.append(" allowedRoot=").append(allowedRoot());
        } catch (RuntimeException e) {
            sb.append(" allowedRoot=<拒绝:").append(e.getClass().getSimpleName()).append('>');
        }
        String runId = System.getProperty("v25.it.testRunId");
        sb.append(" testRunId=").append(runId == null ? "<未提供>" : runId);
        return sb.toString();
    }

    /** 便于断言：把相对路径清单转成可读文本。 */
    public static String previewText() {
        try {
            Path target = runRoot();
            if (!Files.exists(target)) {
                return "(不存在) " + target;
            }
            return String.join(System.lineSeparator(), listForDeletion(target));
        } catch (IOException e) {
            return "(预览失败: " + e.getMessage() + ")";
        } catch (RuntimeException e) {
            return "(被门禁拒绝: " + e.getMessage() + ")";
        }
    }

    static {
        // 明确记录：本类不接受 URI 形态的根
        assert !DEFAULT_ROOT_RELATIVE.toLowerCase(Locale.ROOT).contains("://");
    }
}
