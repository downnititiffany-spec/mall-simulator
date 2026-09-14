package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.NEW_SOURCE_KEYS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V25-T02 ②：{@code landing/manifests} 的**运行时巡检**（冻结名单 + sha256）。
 *
 * <p>契约回归在 {@link IngestionManifestSourceSchemaTest}（冻结副本、密闭）；本类只回答一个
 * 生产问题：<b>「P1-05 之前落盘的历史清单，今天还在原地、还是原来的字节、没有被回填四键吗？」</b>
 * 同时把"新到的采集文件"与"旧文件被改写"这两件本质相反的事分开：</p>
 * <ul>
 *   <li>冻结名单内的文件：缺失 / 字节改动 / 旧格式出现新键 ⇒ <b>违规</b>；</li>
 *   <li>名单外的文件（新到的 {@code <N>.json}）：只登记（名字/键数/是否含新键/sha256），<b>不判红</b>
 *       —— 这正是 2026-09-12 那 4 个真实采集产物（40-43.json）被原用例误判的地方；</li>
 *   <li>目录整个不在 ⇒ <b>违规</b>：{@code landing/} 被 {@code .gitignore:30} 忽略，
 *       删掉它不会在 {@code git status} 里显形，"目录必须在"必须显式断言。</li>
 * </ul>
 *
 * <p><b>为什么上一条重要</b>：本用例最初的脏版本（gate 被 landing/ 下文件的存在性带偏）已经证明过
 * "把生产落盘目录当夹具"会红；反向的坑同样存在 —— 只要允许"目录不在就算过"，删掉历史清单就成了
 * 一条永久变绿的捷径。两个方向都钉住。</p>
 *
 * <p>巡检**只读**：不写入、不删除、不重命名 {@code landing/} 下任何文件（V25-T02 的 DoD：
 * 40-43.json 必须原样留存，证据见 {@code docs/acceptance/v25-t01-t02-baseline-20260914/}）。</p>
 */
class IngestionManifestRuntimePatrolTest {

    /** 冻结副本（用于构造负例；真实目录的对照物） */
    private static final Path FIXTURE_DIR =
            RepoRoot.path("analytics-server/platform-app/src/test/resources/ingestion-manifest-freeze");

    /** 生产落盘目录（真实采集产物；被 .gitignore 忽略，故必须显式巡检） */
    private static final Path MANIFEST_DIR = RepoRoot.path("landing/manifests");

    @Test
    @DisplayName("真实历史清单 1-43.json 原样留存、未被回填（冻结名单 + sha256 逐条核对）")
    void realHistoryOnDiskIsUntouched() throws IOException {
        ManifestFreezePatrol.Report report = ManifestFreezePatrol.inspect(
                MANIFEST_DIR, allFrozen(), NEW_SOURCE_KEYS);

        System.out.println("[V25-T02 巡检] 目录=" + MANIFEST_DIR);
        System.out.println(report.render());

        assertThat(report.frozenChecked())
                .as("冻结名单 39（P1-05 前）＋ 4（40-43.json，P1-05 后真实采集）= 43")
                .isEqualTo(43);
        assertThat(report.violations())
                .as("历史清单不得被删除/改写/回填；新到的采集文件不算违规（见下一条用例）")
                .isEmpty();
    }

    @Test
    @DisplayName("负例：新到的采集文件只报告不判红；被回填/改写/缺失的冻结文件才判红")
    void patrolSeparatesArrivalsFromRealViolations(@TempDir Path tmp) throws IOException {
        // 冻结组：1.json（旧格式 15 键）与 40.json（新格式 19 键）原样放好
        Files.createDirectories(tmp);
        copyFixture("legacy/1.json", tmp.resolve("1.json"));
        copyFixture("new/40.json", tmp.resolve("40.json"));
        // 新到：44.json 是新格式采集产物（19 键）；45.json 是新到但仍是旧格式（15 键）
        copyFixture("new/40.json", tmp.resolve("44.json"));
        copyFixture("legacy/2.json", tmp.resolve("45.json"));

        ManifestFreezePatrol.Report clean = ManifestFreezePatrol.inspect(tmp, frozen("1.json", "40.json"),
                NEW_SOURCE_KEYS);
        assertThat(clean.violations())
                .as("新到文件（44/45）不得被判成'旧清单被回填'——这就是 2026-09-12 的误报")
                .isEmpty();
        assertThat(clean.arrivals())
                .as("新到文件必须被逐个报出来（人工确认用），而不是静默忽略")
                .extracting(ManifestFreezePatrol.Arrival::name)
                .containsExactly("44.json", "45.json");
        assertThat(clean.arrivals())
                .filteredOn(a -> a.name().equals("44.json"))
                .singleElement()
                .satisfies(a -> assertThat(a.hasNewSourceKeys()).isTrue());
        assertThat(clean.arrivals())
                .filteredOn(a -> a.name().equals("45.json"))
                .singleElement()
                .satisfies(a -> assertThat(a.hasNewSourceKeys()).as("新到但仍是旧格式，同样不判红").isFalse());

        // (a) 旧格式冻结文件被回填四键（用 40.json 的字节覆盖 1.json）
        Path backfilledDir = Files.createDirectories(tmp.resolve("backfilled"));
        copyFixture("new/40.json", backfilledDir.resolve("1.json"));
        copyFixture("new/40.json", backfilledDir.resolve("40.json"));
        ManifestFreezePatrol.Report backfilled = ManifestFreezePatrol.inspect(
                backfilledDir, frozen("1.json", "40.json"), NEW_SOURCE_KEYS);
        assertThat(backfilled.violations())
                .as("回填必须同时报'内容被改动'与'疑似被回填'")
                .anyMatch(v -> v.contains("1.json 内容被改动"))
                .anyMatch(v -> v.contains("1.json 疑似被回填") && v.contains("sourceCode"));

        // (b) 冻结文件被改一个字节（同长度替换：把 source 的值 local-file 改成 local-filf）
        Path tamperedDir = Files.createDirectories(tmp.resolve("tampered"));
        String json = Files.readString(FIXTURE_DIR.resolve("legacy/1.json"), StandardCharsets.UTF_8);
        Files.writeString(tamperedDir.resolve("1.json"), json.replace("local-file", "local-filf"),
                StandardCharsets.UTF_8);
        copyFixture("new/40.json", tamperedDir.resolve("40.json"));
        ManifestFreezePatrol.Report tampered = ManifestFreezePatrol.inspect(
                tamperedDir, frozen("1.json", "40.json"), NEW_SOURCE_KEYS);
        assertThat(tampered.violations())
                .as("字节级改动必须被 sha256 抓到（即使键数没变）")
                .hasSize(1)
                .allMatch(v -> v.contains("1.json 内容被改动"));

        // (c) 冻结文件被删除
        Path missingDir = Files.createDirectories(tmp.resolve("missing"));
        copyFixture("new/40.json", missingDir.resolve("40.json"));
        ManifestFreezePatrol.Report missing = ManifestFreezePatrol.inspect(
                missingDir, frozen("1.json", "40.json"), NEW_SOURCE_KEYS);
        assertThat(missing.violations())
                .as("缺失必须判红（否则'删掉历史'就成了变绿捷径）")
                .hasSize(1)
                .allMatch(v -> v.contains("1.json 缺失"));
    }

    @Test
    @DisplayName("负例：清单目录整个不在 ⇒ 显式判红（被忽略的目录删掉不会在 git status 显形）")
    void missingDirectoryIsAViolation(@TempDir Path tmp) throws IOException {
        ManifestFreezePatrol.Report report = ManifestFreezePatrol.inspect(
                tmp.resolve("no-such-dir"), allFrozen(), NEW_SOURCE_KEYS);

        assertThat(report.green()).isFalse();
        assertThat(report.violations())
                .as("必须显式说清'目录不存在'，而不是因为遍历不到文件就当作零违规")
                .hasSize(1)
                .allMatch(v -> v.contains("清单目录不存在"));
    }

    // ---------- 小工具 ----------

    private static List<ManifestFreezePatrol.Entry> allFrozen() throws IOException {
        JsonNode freeze = ManifestFreezePatrol.freezeManifest();
        List<ManifestFreezePatrol.Entry> all = new ArrayList<>(ManifestFreezePatrol.entries(freeze, "legacy"));
        all.addAll(ManifestFreezePatrol.entries(freeze, "new"));
        return all;
    }

    /** 只取冻结名单里的这几个文件（负例不必摆放全部 43 个）。 */
    private static List<ManifestFreezePatrol.Entry> frozen(String... names) throws IOException {
        List<String> wanted = List.of(names);
        return allFrozen().stream().filter(e -> wanted.contains(e.name())).toList();
    }

    private static void copyFixture(String relative, Path target) throws IOException {
        Files.copy(FIXTURE_DIR.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING);
    }
}
