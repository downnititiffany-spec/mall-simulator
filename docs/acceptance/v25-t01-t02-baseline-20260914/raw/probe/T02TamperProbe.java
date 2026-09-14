package com.graduation.analytics.ingestion;

import com.graduation.analytics.testsupport.RepoRoot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * V25-T02 负例专项：**同名字节改写**（长度不变、内容不同）必须判红 —— 证明巡检依赖 sha256，
 * 而不是"文件名还在就算过"。
 *
 * <p>运行（见 raw/t02-tamper-negative.log 头部记录的逐字命令）：</p>
 * <pre>
 * javac -encoding UTF-8 -cp "&lt;jackson jars&gt;" -d &lt;out&gt; RepoRoot.java IngestionManifestSchemaSubset.java ManifestFreezePatrol.java T02TamperProbe.java
 * java -cp "&lt;out&gt;;analytics-server/platform-app/src/test/resources;&lt;jackson jars&gt;" com.graduation.analytics.ingestion.T02TamperProbe &lt;仓根&gt; &lt;工作目录&gt;
 * </pre>
 */
public final class T02TamperProbe {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path work = Path.of(args[1]).toAbsolutePath();
        Path fixtures = root.resolve("analytics-server/platform-app/src/test/resources/ingestion-manifest-freeze");

        var freeze = ManifestFreezePatrol.freezeManifest();
        var all = ManifestFreezePatrol.entries(freeze, "legacy");
        all.addAll(ManifestFreezePatrol.entries(freeze, "new"));
        var target = all.stream().filter(e -> e.name().equals("2.json")).findFirst().orElseThrow();

        // 用冻结副本原件做"同名字节改写"：local-file(10) → local-filf(10)，长度不变、内容不同。
        String original = Files.readString(fixtures.resolve("legacy/2.json"), StandardCharsets.UTF_8);
        String tampered = original.replace("local-file", "local-filf");
        check("前提：改写前后字符串长度相同（等长改写，靠大小/存在性抓不到）",
                original.length() == tampered.length());
        check("前提：改写前后内容确实不同", !original.equals(tampered));

        Path dir = Files.createDirectories(work.resolve("tampered"));
        Files.writeString(dir.resolve("2.json"), tampered, StandardCharsets.UTF_8);
        // 其余冻结文件原样放好，确保唯一违规就是 2.json 的字节改写。
        for (var e : all) {
            if (!e.name().equals("2.json")) {
                Path from = fixtures.resolve(e.legacy() ? "legacy" : "new").resolve(e.name());
                Files.copy(from, dir.resolve(e.name()));
            }
        }

        Path onDisk = dir.resolve("2.json");
        System.out.println("被改写文件: 2.json");
        System.out.println("  冻结 sha256 : " + target.sha256());
        System.out.println("  盘上 sha256 : " + ManifestFreezePatrol.sha256(onDisk));
        System.out.println("  冻结 bytes  : " + Files.size(fixtures.resolve("legacy/2.json"))
                + "   盘上 bytes: " + Files.size(onDisk) + "   （字节数相同 ⇒ 只有 sha256 能发现）");
        System.out.println("  差异片段: \"local-file\" → \"local-filf\"");

        ManifestFreezePatrol.Report report =
                ManifestFreezePatrol.inspect(dir, all, IngestionManifestSchemaSubset.NEW_SOURCE_KEYS);
        System.out.println("[巡检输出] 冻结登记 " + all.size() + " 个（全部 43 个），只有 2.json 被改写；"
                + report.render());
        check("负例：同名字节改写被判红（1 条违规）", report.violations().size() == 1);
        check("负例：违规文案点明 sha256 不一致",
                report.violations().get(0).contains("内容被改动")
                        && report.violations().get(0).contains(target.sha256()));
        check("负例：被改写的冻结文件没有被降级成「新到文件」放过（新到件数为 0）",
                report.arrivals().isEmpty());

        System.out.println(failures == 0 ? "TAMPER-PROBE-OK（0 处失败）" : "TAMPER-PROBE-FAILED（" + failures + "）");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what);
        if (!ok) {
            failures++;
        }
    }

    private T02TamperProbe() {
    }
}
