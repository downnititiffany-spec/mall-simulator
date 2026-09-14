package com.graduation.analytics.warehouse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * V25-T01 的**独立探针**（不依赖 Maven/并发泳道）：用 javac 直接编译扫描器 + 本文件后运行。
 *
 * <p>存在的理由：platform-common 的 testCompile 在 12:08-12:14 期间被并行泳道 Q01 的
 * {@code metric/QualityRuleCatalog.java} 改动打断，官方 Maven 命令暂时跑不起来。本探针只用到
 * JDK，因此可以先独立验证「词法器/扫描器/负例」这一层是否真的成立；官方 Maven 证据在编译恢复后补齐。</p>
 *
 * <p>用法：{@code javac -d <out> WarehouseNameLiteralScanner.java T01Probe.java && java -cp <out> com.graduation.analytics.warehouse.T01Probe <仓根>}</p>
 */
public final class T01Probe {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        System.out.println("仓根 = " + root);

        // ── ① 真实仓库扫描：旧口径（原文）应有命中，新口径（词法剥离注释）应为零 ──
        WarehouseNameLiteralScanner.Result real = WarehouseNameLiteralScanner.scan(root);
        System.out.println("scanned=" + real.scanned().size());
        System.out.println("RAW(旧门禁口径) hits=" + real.rawHits().size());
        real.rawHits().forEach(h -> System.out.println("  RAW  " + h));
        System.out.println("CODE(新门禁口径) hits=" + real.codeHits().size());
        real.codeHits().forEach(h -> System.out.println("  CODE " + h));
        System.out.println("commentOnly=" + real.commentOnly().size());
        check("真实仓：原文命中非空（历史注释仍在）", !real.rawHits().isEmpty());
        check("真实仓：词法剥离后零命中", real.codeHits().isEmpty());
        check("真实仓：原文命中全部落在注释里", real.commentOnly().size() == real.rawHits().size());

        // ── ② 词法单元用例 ──
        String scala = "val a = s\"SELECT * FROM dw_dwd.t\"   // dw_ods.t 注释\n"
                + "/* 块注释 dw_dwd.t\n   跨行块注释 dw_dim.t */\n";
        String scalaCode = WarehouseNameLiteralScanner.CommentSyntax.strip(
                scala, WarehouseNameLiteralScanner.CommentSyntax.SLASH);
        check("lexer-java/scala: 代码行仍命中", WarehouseNameLiteralScanner.matches(line(scalaCode, 0)));
        check("lexer-java/scala: 行注释被剥离", !scalaCode.contains("dw_ods"));
        check("lexer-java/scala: 块注释被剥离", !scalaCode.contains("dw_dim"));
        check("lexer-java/scala: 块注释首行不再命中", !WarehouseNameLiteralScanner.matches(line(scalaCode, 1)));
        check("lexer-java/scala: 行数不变",
                scalaCode.split("\n", -1).length == scala.split("\n", -1).length);

        String sql = "SELECT * FROM dw_ods.t WHERE x = 'dw_dwd.y'; -- dw_ads.z\n";
        String sqlCode = WarehouseNameLiteralScanner.CommentSyntax.strip(
                sql, WarehouseNameLiteralScanner.CommentSyntax.DASH);
        check("lexer-sql: 语句命中", WarehouseNameLiteralScanner.matches(line(sqlCode, 0)));
        check("lexer-sql: -- 注释被剥离", !sqlCode.contains("dw_ads"));
        check("lexer-sql: 字符串字面量保留", sqlCode.contains("dw_dwd"));

        String yml = "name: \"dw_ods\"  # dw_dwd 注释\n";
        String ymlCode = WarehouseNameLiteralScanner.CommentSyntax.strip(
                yml, WarehouseNameLiteralScanner.CommentSyntax.HASH);
        check("lexer-yml: 引号值保留", ymlCode.contains("dw_ods"));
        check("lexer-yml: # 注释剥离", !ymlCode.contains("dw_dwd"));

        String ps = "Write-Host \"dw_ods\" # dw_dwd\n<# dw_dim #>\n";
        String psCode = WarehouseNameLiteralScanner.CommentSyntax.strip(
                ps, WarehouseNameLiteralScanner.CommentSyntax.POWERSHELL);
        check("lexer-ps1: 行注释与块注释都剥离",
                !psCode.contains("dw_dwd") && !psCode.contains("dw_dim"));
        check("lexer-ps1: 字符串保留", psCode.contains("dw_ods"));

        String xml = "<a>dw_ods</a><!-- dw_dwd -->\n";
        String xmlCode = WarehouseNameLiteralScanner.CommentSyntax.strip(
                xml, WarehouseNameLiteralScanner.CommentSyntax.XML);
        check("lexer-xml: 正文保留", xmlCode.contains("dw_ods"));
        check("lexer-xml: <!-- --> 剥离", !xmlCode.contains("dw_dwd"));

        String json = "{ \"x\": \"dw_ods\" }\n";
        check("lexer-json: NONE 一字不改", WarehouseNameLiteralScanner.CommentSyntax
                .strip(json, WarehouseNameLiteralScanner.CommentSyntax.NONE).equals(json));

        // ── ③ 临时夹具负例：真 SQL 必红 ──
        Path tmp = Files.createTempDirectory("t01-probe");
        write(tmp, "spark-jobs/src/main/scala/f/Bad.scala",
                "object Bad {\n  val s = s\"INSERT OVERWRITE TABLE dw_dwd.dwd_order_detail PARTITION (dt) SELECT 1\"\n}\n");
        write(tmp, "analytics-server/platform-common/src/main/java/f/BadTextBlock.java",
                "class BadTextBlock {\n  static final String DDL = \"\"\"\n      CREATE TABLE IF NOT EXISTS dw_ods.ods_order (id BIGINT);\n      \"\"\";\n}\n");
        write(tmp, "warehouse/ddl/bad.sql",
                "-- 这行是注释 dw_ods.x\nINSERT OVERWRITE TABLE dw_dwd.dwd_order_detail PARTITION (dt) SELECT 1;\n");
        write(tmp, "scripts/bad.sh",
                "#!/bin/sh\n# dw_ods.ods_order 只在注释里\nspark-sql -e \"SELECT * FROM dw_ads.ads_overview\"\n");
        write(tmp, "spark-jobs/src/main/scala/f/Commented.scala",
                "/**\n * 表 spark_catalog.dw_dwd.dwd_order_detail 的 user_key\n * 前缀写法 dw_$layer 也只是文档\n */\nobject Commented\n");

        WarehouseNameLiteralScanner.Result fixture = WarehouseNameLiteralScanner.scan(tmp);
        System.out.println("夹具 CODE hits=" + fixture.codeHits());
        System.out.println("夹具 RAW  hits=" + fixture.rawHits());
        check("负例：真 SQL（scala 三引号）报红", fixture.codeHits().stream()
                .anyMatch(h -> h.file().endsWith("Bad.scala") && h.line() == 2));
        check("负例：Java 文本块报红", fixture.codeHits().stream()
                .anyMatch(h -> h.file().endsWith("BadTextBlock.java") && h.line() == 3));
        check("负例：SQL 文件报红且行号为 2（注释行被剥离）", fixture.codeHits().stream()
                .anyMatch(h -> h.file().endsWith("bad.sql") && h.line() == 2));
        check("负例：shell 字符串报红", fixture.codeHits().stream()
                .anyMatch(h -> h.file().endsWith("bad.sh") && h.line() == 3));
        check("负例：仅注释的文件不报红", fixture.codeHits().stream()
                .noneMatch(h -> h.file().endsWith("Commented.scala")));
        check("负例：仅注释的文件在原文口径下确实命中（否则是空跑）", fixture.rawHits().stream()
                .anyMatch(h -> h.file().endsWith("Commented.scala")));

        // ── ④ fail-closed：未登记扩展名必须抛 ──
        boolean threw = false;
        try {
            WarehouseNameLiteralScanner.syntaxOf("scripts/foo.psm1");
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("fail-closed：未登记扩展名抛异常", threw);

        System.out.println(failures == 0 ? "PROBE-OK（0 处失败）" : "PROBE-FAILED（" + failures + " 处失败）");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String line(String text, int index) {
        return text.split("\n", -1)[index];
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what);
        if (!ok) {
            failures++;
        }
    }

    private T01Probe() {
    }
}
