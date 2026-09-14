package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.testsupport.RepoRoot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * V25-T02 的**独立探针**（不依赖 Maven/并发泳道）：用 javac + jackson jar 直接编译
 * {@link IngestionManifestSchemaSubset}、{@link ManifestFreezePatrol} 与本文件后运行。
 *
 * <p>理由同 {@code T01Probe}：platform-common 的 testCompile 被并行泳道打断时，先把 T02 的
 * 契约/巡检逻辑独立验证一遍；官方 Maven 证据在编译恢复后补齐。</p>
 *
 * <p>用法：{@code java -cp "out;src/test/resources;<jackson>" com.graduation.analytics.ingestion.T02Probe <仓根>}</p>
 */
public final class T02Probe {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path fixtures = root.resolve("analytics-server/platform-app/src/test/resources/ingestion-manifest-freeze");
        Path manifests = root.resolve("landing/manifests");
        JsonNode freeze = ManifestFreezePatrol.freezeManifest();
        List<String> newKeys = IngestionManifestSchemaSubset.NEW_SOURCE_KEYS;

        System.out.println("仓根 = " + root);
        System.out.println("冻结登记: frozenAt=" + freeze.path("frozenAt").asText()
                + " legacyFrozenCount=" + freeze.path("legacyFrozenCount").asInt()
                + " newFrozenCount=" + freeze.path("newFrozenCount").asInt());
        System.out.println("冻结依据: " + freeze.path("basis").asText());

        List<ManifestFreezePatrol.Entry> legacy = ManifestFreezePatrol.entries(freeze, "legacy");
        List<ManifestFreezePatrol.Entry> fresh = ManifestFreezePatrol.entries(freeze, "new");
        check("冻结名单: legacy=39", legacy.size() == 39);
        check("冻结名单: new=4（40-43.json）", fresh.size() == 4);

        // ── A) 冻结副本字节受冻（名字/键数/sha256） ──
        for (String group : List.of("legacy", "new")) {
            for (ManifestFreezePatrol.Entry e : ManifestFreezePatrol.entries(freeze, group)) {
                Path copy = fixtures.resolve(group).resolve(e.name());
                check("冻结副本存在 " + group + "/" + e.name(), Files.isRegularFile(copy));
                check("sha256 一致 " + group + "/" + e.name(),
                        ManifestFreezePatrol.sha256(copy).equalsIgnoreCase(e.sha256()));
                JsonNode node = IngestionManifestSchemaSubset.MAPPER
                        .readTree(Files.readString(copy, StandardCharsets.UTF_8));
                check("键数一致 " + group + "/" + e.name() + "=" + e.keys(),
                        IngestionManifestSchemaSubset.fieldNames(node).size() == e.keys());
            }
        }

        // ── B) 契约：扩展前后违规集合逐字相同（集合差） ──
        JsonNode current = IngestionManifestSchemaSubset.schema();
        JsonNode baseline = IngestionManifestSchemaSubset.withoutNewSourceProperties(current);
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (ManifestFreezePatrol.Entry e : legacy) {
            JsonNode node = IngestionManifestSchemaSubset.MAPPER.readTree(
                    Files.readString(fixtures.resolve("legacy").resolve(e.name()), StandardCharsets.UTF_8));
            if (!IngestionManifestSchemaSubset.fieldNames(node).stream().noneMatch(newKeys::contains)) {
                check("前提：旧格式副本无新键 " + e.name(), false);
            }
            IngestionManifestSchemaSubset.validate(node, baseline).forEach(v -> before.add(e.name() + ": " + v));
            IngestionManifestSchemaSubset.validate(node, current).forEach(v -> after.add(e.name() + ": " + v));
        }
        check("契约：P1-05 前 39 个清单的违规集合扩展前后逐字相同", after.equals(before));
        check("契约：既有违规恰好是已记载的 batchId 漂移",
                !after.isEmpty() && after.stream().allMatch(
                        v -> v.contains("batchId 类型不符：期望 [integer]，实际 STRING")));
        System.out.println("  既有违规（历史漂移，非本 lane 引入）: " + after);

        for (ManifestFreezePatrol.Entry e : fresh) {
            JsonNode node = IngestionManifestSchemaSubset.MAPPER.readTree(
                    Files.readString(fixtures.resolve("new").resolve(e.name()), StandardCharsets.UTF_8));
            check("新格式副本四键齐备 " + e.name(),
                    IngestionManifestSchemaSubset.fieldNames(node).containsAll(newKeys));
            check("新格式副本通过现行 schema " + e.name(),
                    IngestionManifestSchemaSubset.validate(node, current).isEmpty());
        }

        // ── C) 真实生产目录巡检（冻结名单 + sha256；新到只报告） ──
        List<ManifestFreezePatrol.Entry> all = new ArrayList<>(legacy);
        all.addAll(fresh);
        ManifestFreezePatrol.Report real = ManifestFreezePatrol.inspect(manifests, all, newKeys);
        System.out.println("[真实目录巡检] " + real.render());
        check("巡检：冻结 43 个", real.frozenChecked() == 43);
        check("巡检：零违规（历史原样留存、未被回填）", real.violations().isEmpty());
        System.out.println("  真实目录文件总数=" + Files.list(manifests).filter(Files::isRegularFile).count()
                + "（冻结 43 ＋ 新到 " + real.arrivals().size() + "）");

        // ── D) 负例：回填/改写/缺失/目录不在 必须判红；新到文件不得判红 ──
        Path tmp = Files.createTempDirectory("t02-probe");
        Path clean = Files.createDirectories(tmp.resolve("clean"));
        copy(fixtures.resolve("legacy/1.json"), clean.resolve("1.json"));
        copy(fixtures.resolve("new/40.json"), clean.resolve("40.json"));
        copy(fixtures.resolve("new/40.json"), clean.resolve("44.json"));   // 新到（新格式）
        copy(fixtures.resolve("legacy/2.json"), clean.resolve("45.json"));  // 新到（旧格式）
        ManifestFreezePatrol.Report cleanReport = ManifestFreezePatrol.inspect(clean, frozen(all, "1.json", "40.json"), newKeys);
        System.out.println("[负例-新到] " + cleanReport.render());
        check("负例：新到文件不判红（这正是 2026-09-12 的误报）", cleanReport.violations().isEmpty());
        check("负例：新到文件被逐个报告",
                cleanReport.arrivals().size() == 2
                        && cleanReport.arrivals().get(0).name().equals("44.json")
                        && cleanReport.arrivals().get(1).name().equals("45.json"));

        Path backfilled = Files.createDirectories(tmp.resolve("backfilled"));
        copy(fixtures.resolve("new/40.json"), backfilled.resolve("1.json"));
        copy(fixtures.resolve("new/40.json"), backfilled.resolve("40.json"));
        ManifestFreezePatrol.Report backfilledReport =
                ManifestFreezePatrol.inspect(backfilled, frozen(all, "1.json", "40.json"), newKeys);
        System.out.println("[负例-回填] " + backfilledReport.render());
        check("负例：回填被报'内容被改动'",
                backfilledReport.violations().stream().anyMatch(v -> v.contains("1.json 内容被改动")));
        check("负例：回填被报'疑似被回填'＋新增键",
                backfilledReport.violations().stream()
                        .anyMatch(v -> v.contains("1.json 疑似被回填") && v.contains("sourceCode")));

        Path tampered = Files.createDirectories(tmp.resolve("tampered"));
        String json = Files.readString(fixtures.resolve("legacy/1.json"), StandardCharsets.UTF_8);
        Files.writeString(tampered.resolve("1.json"), json.replace("local-file", "local-filf"), StandardCharsets.UTF_8);
        copy(fixtures.resolve("new/40.json"), tampered.resolve("40.json"));
        ManifestFreezePatrol.Report tamperedReport =
                ManifestFreezePatrol.inspect(tampered, frozen(all, "1.json", "40.json"), newKeys);
        System.out.println("[负例-改写] " + tamperedReport.render());
        check("负例：等长字节改写被 sha256 抓到",
                tamperedReport.violations().size() == 1
                        && tamperedReport.violations().get(0).contains("1.json 内容被改动"));

        Path missing = Files.createDirectories(tmp.resolve("missing"));
        copy(fixtures.resolve("new/40.json"), missing.resolve("40.json"));
        ManifestFreezePatrol.Report missingReport =
                ManifestFreezePatrol.inspect(missing, frozen(all, "1.json", "40.json"), newKeys);
        System.out.println("[负例-缺失] " + missingReport.render());
        check("负例：缺失判红", missingReport.violations().size() == 1
                && missingReport.violations().get(0).contains("1.json 缺失"));

        ManifestFreezePatrol.Report noDir =
                ManifestFreezePatrol.inspect(tmp.resolve("no-such-dir"), all, newKeys);
        System.out.println("[负例-目录不在] " + noDir.render());
        check("负例：目录整个不在 ⇒ 显式判红", !noDir.green()
                && noDir.violations().size() == 1
                && noDir.violations().get(0).contains("清单目录不存在"));

        System.out.println(failures == 0 ? "PROBE-OK（0 处失败）" : "PROBE-FAILED（" + failures + " 处失败）");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static List<ManifestFreezePatrol.Entry> frozen(
            List<ManifestFreezePatrol.Entry> all, String... names) {
        List<String> wanted = List.of(names);
        return all.stream().filter(e -> wanted.contains(e.name())).toList();
    }

    private static void copy(Path from, Path to) throws Exception {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what);
        if (!ok) {
            failures++;
        }
    }

    private T02Probe() {
    }
}
