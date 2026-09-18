package com.graduation.generator.boundary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.1 §3.4-3 / §3.4-1 生成器侧边界守卫（L0，源码级负向证据）。
 *
 * <p>为什么用源码断言：第三程序最容易"顺手"退化成商城的一个模块——注入一个商城的 Mapper、
 * 复用商城的库、加一个 {@code mall.*} 配置键、把产物写进分析平台 landing。这些退化行为不会让任何
 * 功能测试变红，所以必须用常量集的负向扫描常驻钉住。</p>
 *
 * <p>四个用例分别对应：① 不引用商城/平台 Java 包与库表；② 配置自有命名空间与自有端口；
 * ③ 迁移脚本只建自己的五张表；④ 构建不依赖另外两个程序的构件（独立构建产物）。</p>
 */
class GeneratorBoundarySourcePolicyTest {

    /** Maven surefire 的工作目录是本模块根目录 */
    private static final Path MODULE_ROOT = Path.of("").toAbsolutePath().normalize();

    /** Java 源码禁止痕迹（商城侧 + 分析平台侧） */
    private static final Map<String, Pattern> JAVA_FORBIDDEN = new LinkedHashMap<>();

    static {
        JAVA_FORBIDDEN.put("商城 Java 包", Pattern.compile("com\\.graduation\\.mall\\b"));
        JAVA_FORBIDDEN.put("分析平台 Java 包", Pattern.compile("com\\.graduation\\.analytics\\b"));
        JAVA_FORBIDDEN.put("商城业务表名", Pattern.compile("\\bmall_[a-z][a-z0-9_]*"));
        JAVA_FORBIDDEN.put("商城/平台数据库名", Pattern.compile("mall_simulator|analytics_meta|analytics_metric"));
        JAVA_FORBIDDEN.put("商城程序类", Pattern.compile("MallSimulatorApplication|MallBusinessService|MallController"));
        JAVA_FORBIDDEN.put("mall.* 配置键", Pattern.compile("\"mall\\.[a-z]"));
        JAVA_FORBIDDEN.put("平台配置键", Pattern.compile("\"platform\\.[a-z]"));
        JAVA_FORBIDDEN.put("平台端口/服务地址", Pattern.compile("127\\.0\\.0\\.1:809[01]|localhost:809[01]"));
    }

    /** 配置类资源禁止痕迹 */
    private static final Map<String, Pattern> CONFIG_FORBIDDEN = new LinkedHashMap<>();

    static {
        CONFIG_FORBIDDEN.put("mall.* 配置键", Pattern.compile("^\\s*mall\\.[a-z]", Pattern.MULTILINE));
        CONFIG_FORBIDDEN.put("platform.* 配置键", Pattern.compile("^\\s*platform\\.[a-z]", Pattern.MULTILINE));
        CONFIG_FORBIDDEN.put("商城/平台数据库名", Pattern.compile("mall_simulator|analytics_meta|analytics_metric"));
        CONFIG_FORBIDDEN.put("商城 Java 包", Pattern.compile("com\\.graduation\\.mall\\b"));
    }

    /** §4.2 规定的五张表，缺一不可 */
    private static final List<String> REQUIRED_TABLES = List.of(
            "generator_target", "generation_plan", "generation_run",
            "generation_artifact", "generation_event_stat");

    /** 只允许本程序自己的表前缀 */
    private static final Pattern FOREIGN_TABLE = Pattern.compile(
            "\\b(mall_[a-z][a-z0-9_]*|ingestion_[a-z][a-z0-9_]*|ods_[a-z][a-z0-9_]*|dwd_[a-z][a-z0-9_]*"
                    + "|dws_[a-z][a-z0-9_]*|ads_[a-z][a-z0-9_]*|metric_[a-z][a-z0-9_]*)\\b");

    @Test
    @DisplayName("生成器 Java 源码不引用商城/平台包、库表或它们的端口")
    void generatorJavaHasNoMallOrPlatformCoupling() {
        List<Path> sources = scan("src/main/java", ".java");
        assertThat(sources).as("生成器生产源码必须存在（守卫不可空跑）").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = read(source);
            for (Map.Entry<String, Pattern> rule : JAVA_FORBIDDEN.entrySet()) {
                Matcher matcher = rule.getValue().matcher(text);
                if (matcher.find()) {
                    offenders.add("%s 出现%s：\"%s\"（第 %d 行）".formatted(
                            relative(source), rule.getKey(), matcher.group(), lineOf(text, matcher.start())));
                }
            }
        }
        assertThat(offenders)
                .as("V2.1 §3.4-3：生成器不得引用商城实体/Mapper/Service/数据源，也不得依赖分析平台")
                .isEmpty();
    }

    @Test
    @DisplayName("生成器配置使用自有命名空间与自有端口 8092")
    void generatorConfigHasOwnNamespaceAndPort() {
        List<Path> configs = new ArrayList<>();
        configs.addAll(scan("src/main/resources", ".yml"));
        configs.addAll(scan("src/main/resources", ".yaml"));
        configs.addAll(scan("src/main/resources", ".properties"));
        assertThat(configs).as("生成器配置资源必须存在（守卫不可空跑）").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path config : configs) {
            String text = read(config);
            for (Map.Entry<String, Pattern> rule : CONFIG_FORBIDDEN.entrySet()) {
                Matcher matcher = rule.getValue().matcher(text);
                if (matcher.find()) {
                    offenders.add("%s 出现%s：\"%s\"（第 %d 行）".formatted(
                            relative(config), rule.getKey(), matcher.group().trim(),
                            lineOf(text, matcher.start())));
                }
            }
        }
        assertThat(offenders)
                .as("V2.1 §3.4-1/3：生成器配置不得出现商城或平台的键与库名")
                .isEmpty();

        String main = read(MODULE_ROOT.resolve("src/main/resources/application.yml"));
        assertThat(main).as("独立端口必须是 8092（V2.1 §3.1 原文）").contains("port: 8092");
        assertThat(main).as("独立元数据库必须是本程序自己的库").contains("generator_meta");
        assertThat(main).as("文件模式产物必须落在本程序自己的目录（§3.3 B）").contains("generator-output");
    }

    @Test
    @DisplayName("迁移脚本只建 §4.2 的五张表，不碰商城表与数仓表")
    void migrationsDeclareOnlyOwnContractTables() {
        List<Path> scripts = scan("src/main/resources/db/generator", ".sql");
        assertThat(scripts).as("生成器迁移脚本必须存在（守卫不可空跑）").isNotEmpty();

        String all = new StringBuilder().append("").toString();
        for (Path script : scripts) {
            all += read(script);
        }
        assertThat(all).as("五张契约表必须齐全").contains(REQUIRED_TABLES.toArray(new String[0]));

        List<String> offenders = new ArrayList<>();
        for (Path script : scripts) {
            String text = read(script);
            Matcher matcher = FOREIGN_TABLE.matcher(text);
            while (matcher.find()) {
                offenders.add("%s 引用了外部表 %s（第 %d 行）".formatted(
                        relative(script), matcher.group(), lineOf(text, matcher.start())));
            }
        }
        assertThat(offenders)
                .as("V2.1 §3.4-3：生成器迁移不得涉及商城库表或数仓/指标表")
                .isEmpty();
    }

    @Test
    @DisplayName("构建不依赖商城或分析平台构件（独立构建产物）")
    void buildDoesNotDependOnOtherPrograms() {
        String pom = read(MODULE_ROOT.resolve("pom.xml"));
        List<String> forbiddenArtifacts = List.of(
                "mall-simulator", "analytics-server", "platform-common", "platform-app",
                "connection-ingestion", "warehouse-pipeline", "metric-analysis", "ai-decision", "spark-jobs");
        List<String> offenders = new ArrayList<>();
        for (String artifact : forbiddenArtifacts) {
            if (pom.contains("<artifactId>" + artifact + "</artifactId>")) {
                offenders.add("pom.xml 依赖了其它程序构件：" + artifact);
            }
        }
        assertThat(offenders).as("V2.1 §3.4-1/3：生成器必须是可独立构建的产物").isEmpty();
        assertThat(pom).as("生成器是单模块工程，不是聚合 reactor 的一部分").doesNotContain("<modules>");
        assertThat(pom).as("必须能产出可执行 jar").contains("spring-boot-maven-plugin");
        assertThat(pom).as("独立版本号").contains("<artifactId>synthetic-data-generator</artifactId>");
    }

    @Test
    @DisplayName("Stage 7 producer harness 只走 3307 + 8090/8092 公开边界，凭据不进 CLI")
    void stage7ProducerHarnessKeepsIsolationAndPublicHttpBoundary() {
        Path script = MODULE_ROOT.getParent().resolve("scripts/stage7-producer-isolated.ps1");
        assertThat(script).as("Stage 7 producer harness 必须存在").exists();
        String text = read(script);

        assertThat(text)
                .contains("jdbc:mysql://127.0.0.1:3307/$mallDb")
                .contains("jdbc:mysql://127.0.0.1:3307/$genDb")
                .contains("if ($mallUrl -match ':3306/' -or $genUrl -match ':3306/')")
                .contains("http://127.0.0.1:8090/api/v1/auth/login")
                .contains("http://127.0.0.1:8090/api/v1/admin/products")
                .contains("changeType='adjust'")
                .contains("http://127.0.0.1:8092/api/v1/targets")
                .contains("http://127.0.0.1:8092/api/v1/generation-runs")
                .contains("Wait-Get 'http://127.0.0.1:8092/api/v1/scenarios' 120")
                .contains("generator 启动进程提前退出")
                .contains("--mode=MALL_API")
                .contains("operation-journal.jsonl")
                .contains("$artifactResponse | ForEach-Object { $_ }")
                .contains("$missingCreatedOrders")
                .contains("$missingRefundCompleted")
                .contains("rolling JSONL 与本次 operation journal 关联不完整")
                .contains("/api/v1/mall/outbox/publish")
                .contains("$baselineFailedIds")
                .contains("$baselineResidualPending")
                .contains("$newFailedIds")
                .contains("Outbox 本次新增失败事件")
                .contains("Outbox 本次 run 留下新 pending")
                .contains("$loggedEventIds")
                .contains("$duplicateEventIds")
                .contains("rolling JSONL 出现重复 event_id")
                .contains("uniqueEventIdCount")
                .contains("duplicateEventIdCount")
                .contains("'order_created','order_paid','refund_created','refund_completed'");

        assertThat(text)
                .contains("Read-CredrefPassword")
                .contains("credential_ref=$tokenRef")
                .doesNotContain("[string]$MallPassword")
                .doesNotContain("[string]$GeneratorPassword")
                .doesNotContain("--password=")
                .doesNotContain("jdbc:mysql://127.0.0.1:3306/");
    }

    /**
     * M1-9 ② 硬约束 6 的常驻负向证据：<b>引擎侧不许内置任何一家商城的路由字面量</b>。
     *
     * <p>为什么这条必须用源码扫描而不是行为断言：把 {@code /api/v1/mall/products} 写进引擎，
     * 只要目标恰好是参考商城，所有功能测试都会绿——退化不会让任何用例变红，只会让"换一家商城"
     * 在实现上不成立（引擎里躺着上一家的地址，第二家就得靠"如果……否则……"打补丁）。
     * 因此路由只允许出现在适配器里，引擎/落点只能通过 {@code MallTargetAdapter.operationRoutes} 拿表。</p>
     *
     * <p>正向对照不可省：同一条正则必须在参考商城适配器里命中。否则"引擎里没命中"可能只是因为
     * 正则写错了（比如把路径写成了永远匹配不上的形状），那样的绿灯是假的。</p>
     */
    @Test
    @DisplayName("引擎源码不含任何商城路由字面量，且同一条正则在适配器里必须命中（正向对照）")
    void engineSourcesCarryNoMallRouteLiterals() {
        Pattern routeLiteral = Pattern.compile("\"/[a-z0-9][^\"]*(?:/api/|/open/)[^\"]*\"|/(?:api|open)/v[0-9]");

        List<Path> engineSources = scan("src/main/java", ".java").stream()
                .filter(path -> relative(path).replace('\\', '/').contains("/engine/"))
                .toList();
        assertThat(engineSources).as("引擎源码必须存在（守卫不可空跑）").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path source : engineSources) {
            String text = read(source);
            Matcher matcher = routeLiteral.matcher(text);
            while (matcher.find()) {
                offenders.add("%s 内置了商城路径字面量 \"%s\"（第 %d 行）".formatted(
                        relative(source), matcher.group(), lineOf(text, matcher.start())));
            }
        }
        assertThat(offenders)
                .as("硬约束 6：路由只能来自适配器（MallTargetAdapter.operationRoutes），"
                        + "引擎/落点里不许出现任何商城地址——否则\"换一家商城\"就退化成改引擎")
                .isEmpty();

        // 正向对照：参考商城适配器里这些字面量必须存在，证明上面的正则确实能命中商城路径
        List<Path> adapterSources = scan("src/main/java", ".java").stream()
                .filter(path -> relative(path).replace('\\', '/').contains("/adapter/"))
                .toList();
        assertThat(adapterSources).as("适配器源码必须存在").isNotEmpty();
        assertThat(adapterSources.stream().filter(path -> routeLiteral.matcher(read(path)).find()).toList())
                .as("正向对照：同一条正则必须在适配器源码里命中至少一处商城路径，"
                        + "否则\"引擎里没命中\"只是因为正则写错了")
                .isNotEmpty();

        // 第二家也必须有自己的路由前缀，且不许把参考商城的地址当成自己的路由。
        // 这里刻意不用正则（转义引号容易写错，写错了还会静默空跑），改成逐行取"常量值"再判定。
        String secondMall = read(MODULE_ROOT.resolve(
                "src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java"));
        List<String> secondRoutes = new ArrayList<>();
        List<String> routeOffenders = new ArrayList<>();
        String secondRoutePrefix = "/open/v2/";
        for (String rawLine : secondMall.split("\n")) {
            String line = rawLine.trim();
            // 只看路由常量声明行，且常量值必须是字面量（含 '+' 的拼接行不在这里判定）
            if (!line.endsWith(";") || !line.contains("_PATH = \"") || line.contains("+")) {
                continue;
            }
            String value = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
            if (!value.startsWith("/")) {
                continue;
            }
            secondRoutes.add(value);
            if (!value.startsWith(secondRoutePrefix)) {
                routeOffenders.add(line);
            }
        }
        assertThat(secondRoutes).as("第二家适配器必须以常量形式声明自己的路由（否则下面的前缀断言是空跑）")
                .contains("/open/v2/items", "/open/v2/members", "/open/v2/orders");
        assertThat(routeOffenders).as("第二家适配器不得把参考商城的地址声明成自己的路由").isEmpty();
    }

    // ---------- 工具 ----------

    private static List<Path> scan(String relativeDir, String suffix) {
        Path dir = MODULE_ROOT.resolve(relativeDir);
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static String relative(Path path) {
        return MODULE_ROOT.relativize(path).toString();
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
