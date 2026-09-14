package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.MAPPER;
import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.NEW_SOURCE_KEYS;
import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.fieldNames;
import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.schema;
import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.typeOf;
import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.validate;
import static com.graduation.analytics.ingestion.IngestionManifestSchemaSubset.withoutNewSourceProperties;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-05 / D-037 裁决 6：{@code ingestion-manifest.v1} 的四个源身份字段与**历史清单的向后兼容**。
 *
 * <p>契约权威是 {@code contract-specs/schemas/ingestion-manifest.v1.schema.json}
 * （目录级 {@code 1.2.0}，P1-05 加法扩展）。本类做**结构对账 + 冻结历史产物的契约回归**：</p>
 * <ol>
 *   <li>四个新键在 schema 的 {@code properties} 里，且**都不在** {@code required} 里；</li>
 *   <li>P1-05 之前落盘的历史清单（实测 39 个，每个 15 个键、无新键）在 P1-05 前后的
 *       **违规集合逐字相同**——这正是"不许进 required"的理由（裁决 6）；</li>
 *   <li>新清单（含四字段、{@code mappingVersion=null}）同样通过——加法不能变成"新的合法、旧的非法"。</li>
 * </ol>
 *
 * <h2>V25-T02：为什么②从"真实生产目录"改成"冻结副本"</h2>
 * <p>原②直接遍历 {@code landing/manifests} 并要求"目录里每个 .json 都是 15 键、无新键"。但该目录
 * <b>不是夹具目录</b>：每次真实采集都会新增 {@code <N>.json}。2026-09-12 夜里的真实采集写下
 * {@code 40.json..43.json}（19 键、含 P1-05 新增四键），于是这条"历史回归"用例把**新到的采集产物**
 * 误判成"旧清单被回填"，门禁自那时起即红。</p>
 *
 * <p>拆法（指导书 §9.5）：本类只对 {@code src/test/resources/ingestion-manifest-freeze/} 下的
 * **冻结副本**（{@code legacy/1..39.json} ＋ {@code new/40..43.json}，字节由 {@code freeze.json}
 * 登记 sha256）做契约断言 —— 密闭、可重复、不随采集增长；真实目录的"原样留存/未被回填"由
 * {@link IngestionManifestRuntimePatrolTest} 按**冻结名单 + sha256** 巡检，且新到文件只报告不判红。
 * 两组证据都在 {@code docs/acceptance/v25-t01-t02-baseline-20260914/README.md} 里对账。</p>
 *
 * <p><b>为什么不写"全部历史清单零违规"</b>：实测 {@code 1.json}-{@code 5.json} 这 5 个文件的
 * {@code batchId} 是字符串，与现行 schema 的 {@code batchId: integer} 不符。这是**先于 P1-05**
 * 存在的写入期漂移，且权威文件自己的 {@code batchId} description 里就有记载；本 lane 既不回填
 * 历史清单（裁决 6），也不改契约。因此这里改成可证伪的**集合差**断言，并把该漂移的
 * 文件名与条数显式钉住——把既有事实藏起来比断言失败更糟。</p>
 *
 * <p><b>为什么不引 JSON Schema 校验器</b>：离线构建无法新增依赖，且仓内已有同一先例
 * （{@code CanonicalEventSchemaParityTest} 的说明："只做结构对账：不引入 JSON Schema 校验器依赖"）。
 * 本类因此复用 {@link IngestionManifestSchemaSubset}（由原内部实现抽出，与运行时巡检同一套代码），
 * 并把"子集"这件事显式写在那里，避免把"结构对账"误读成"完整 Draft 2020-12 校验"。
 * <b>取证边界</b>：真机端到端落盘的四字段取值由 E3（真实例 + 真 MySQL 副本库）取证，
 * 两者不可互相替代。</p>
 */
class IngestionManifestSourceSchemaTest {

    /** 冻结副本根目录（与 classpath 资源 {@code /ingestion-manifest-freeze/} 同一批文件） */
    private static final Path FIXTURE_DIR =
            RepoRoot.path("analytics-server/platform-app/src/test/resources/ingestion-manifest-freeze");

    // ---------- ① schema 结构：四个键在 properties 里、都不在 required 里 ----------

    @Test
    @DisplayName("四个源身份字段都在 schema.properties 里")
    void newSourceFieldsAreDeclared() throws IOException {
        Set<String> props = fieldNames(schema().path("properties"));

        assertThat(props).containsAll(NEW_SOURCE_KEYS);
    }

    @Test
    @DisplayName("四个源身份字段都**不在** required 里（否则历史清单瞬间非法）")
    void newSourceFieldsAreNotRequired() throws IOException {
        Set<String> required = fieldNames(schema().path("required"));

        assertThat(required)
                .as("P1-05 之前落盘的 landing/manifests/*.json 没有这些键，进 required 会让它们立刻判非法")
                .doesNotContainAnyElementsOf(NEW_SOURCE_KEYS);
        assertThat(required)
                .as("required 仍是 15 项（加法扩展不得顺手改既有 required）")
                .hasSize(15);
    }

    @Test
    @DisplayName("字段类型与 D-037 裁决 6/8 一致：sourceId 是 integer、mappingVersion 可为 null")
    void fieldTypesMatchDecision() throws IOException {
        JsonNode props = schema().path("properties");

        assertThat(typeOf(props.path("sourceCode"))).containsExactly("string");
        assertThat(typeOf(props.path("sourceId"))).containsExactly("integer");
        assertThat(props.path("sourceId").path("minimum").asInt())
                .as("sourceId ≥ 1：登记主键不会是 0")
                .isEqualTo(1);
        assertThat(typeOf(props.path("profileVersion"))).containsExactly("string");
        assertThat(typeOf(props.path("mappingVersion")))
                .as("mappingVersion 在 P1-05 恒为 null，schema 必须同时允许 string 与 null")
                .containsExactlyInAnyOrder("string", "null");
    }

    @Test
    @DisplayName("schema 的既有 15 个键一个不少（加法扩展不得替换既有字段）")
    void existingKeysSurviveTheExtension() throws IOException {
        JsonNode root = schema();

        assertThat(fieldNames(root.path("required")))
                .containsExactlyInAnyOrder("batchId", "batchNo", "runtimeProfileId", "source", "status",
                        "startedAt", "finishedAt", "files", "acceptedRecords", "quarantinedRecords",
                        "acceptedBytes", "schemaVersions", "acceptedUri", "quarantineUri", "checksum");
        assertThat(root.path("properties").path("source").path("const").asText())
                .as("D-037 裁决 5：source 仍是连接器类型 local-file，没被复用成源标识")
                .isEqualTo("local-file");
    }

    // ---------- ② 冻结副本回归：P1-05 之前的清单仍合法（密闭，不读生产目录） ----------

    @Test
    @DisplayName("冻结副本字节受冻：名字/键数/sha256 与 freeze.json 逐条一致（删夹具不能变绿）")
    void frozenCopiesMatchFrozenChecksums() throws IOException {
        JsonNode freeze = ManifestFreezePatrol.freezeManifest();
        System.out.println("[V25-T02 冻结登记] frozenAt=" + freeze.path("frozenAt").asText()
                + " 依据=" + freeze.path("basis").asText());

        assertThat(ManifestFreezePatrol.entries(freeze, "legacy"))
                .as("P1-05 之前的历史清单冻结 39 个（删掉一个夹具就红，不能靠少验几个变绿）")
                .hasSize(39)
                .extracting(ManifestFreezePatrol.Entry::name)
                .containsExactlyElementsOf(expectedNames(1, 39));
        assertThat(ManifestFreezePatrol.entries(freeze, "new"))
                .as("P1-05 之后真实采集写下的 4 个（40-43）同样冻结，用于钉住'新格式'的契约")
                .hasSize(4)
                .extracting(ManifestFreezePatrol.Entry::name)
                .containsExactlyElementsOf(expectedNames(40, 43));

        for (String group : List.of("legacy", "new")) {
            for (ManifestFreezePatrol.Entry entry : ManifestFreezePatrol.entries(freeze, group)) {
                Path copy = FIXTURE_DIR.resolve(group).resolve(entry.name());
                assertThat(copy).as("冻结副本必须存在: %s", copy).isRegularFile();
                assertThat(ManifestFreezePatrol.sha256(copy))
                        .as("%s/%s 的字节必须与冻结登记一致（夹具不得被悄悄改写）", group, entry.name())
                        .isEqualToIgnoringCase(entry.sha256());
                assertThat(fieldNames(read(copy)))
                        .as("%s/%s 的顶层键数", group, entry.name())
                        .hasSize(entry.keys());
            }
        }
    }

    @Test
    @DisplayName("冻结副本：**全部 39 个**P1-05 前清单在扩展前后的违规集合逐字相同（加法不得新增一条违规）")
    void frozenLegacyCopiesStillValidateBeforeAndAfterP1_05() throws IOException {
        JsonNode freeze = ManifestFreezePatrol.freezeManifest();
        List<ManifestFreezePatrol.Entry> legacy = ManifestFreezePatrol.entries(freeze, "legacy");
        assertThat(legacy).hasSize(39);

        // 基线 = 现行 schema **去掉** P1-05 新增的四个属性。这样"加法是否让旧清单变非法"
        // 就成了同一套子集实现、同一批文件上的**集合差**，而不是一句无法证伪的口头保证。
        JsonNode current = schema();
        JsonNode baseline = withoutNewSourceProperties(current);

        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (ManifestFreezePatrol.Entry entry : legacy) {
            JsonNode node = read(FIXTURE_DIR.resolve("legacy").resolve(entry.name()));
            assertThat(fieldNames(node))
                    .as("前提校验：冻结的旧格式副本 %s 一个新增键都没有（否则下面的集合对比没有意义）", entry.name())
                    .doesNotContainAnyElementsOf(NEW_SOURCE_KEYS);
            validate(node, baseline).forEach(v -> before.add(entry.name() + ": " + v));
            validate(node, current).forEach(v -> after.add(entry.name() + ": " + v));
        }

        assertThat(after)
                .as("P1-05 的契约扩展必须是加法：旧清单的违规集合不得增加任何一条")
                .isEqualTo(before);

        // 观测事实（不是本 lane 造成的，也不是本 lane 该修的）：1.json-5.json 的 batchId 是字符串。
        // 该漂移在权威文件自己的 batchId description 里就有记载（"存在历史漂移：1.json-5.json 为字符串"），
        // 因此这里把它**显式钉住**，而不是把断言放宽到"什么都不查"。
        assertThat(after)
                .as("既有违规必须全部是已记载的 batchId 历史漂移，不得出现新类别")
                .isNotEmpty()
                .allMatch(v -> v.contains("batchId 类型不符：期望 [integer]，实际 STRING"));
        assertThat(after)
                .as("漂移范围就是 1.json-5.json 这 5 个文件")
                .extracting(v -> v.substring(0, v.indexOf(':')))
                .containsExactly("1.json", "2.json", "3.json", "4.json", "5.json");
    }

    @Test
    @DisplayName("冻结副本：40-43.json（P1-05 后真实采集产物）通过现行 schema 且四键齐备")
    void frozenNewCopiesValidateWithAllFourKeys() throws IOException {
        JsonNode current = schema();
        List<ManifestFreezePatrol.Entry> fresh = ManifestFreezePatrol.entries(
                ManifestFreezePatrol.freezeManifest(), "new");

        for (ManifestFreezePatrol.Entry entry : fresh) {
            JsonNode node = read(FIXTURE_DIR.resolve("new").resolve(entry.name()));
            assertThat(fieldNames(node))
                    .as("真实采集产物的新格式：%s 必须含四个源身份键", entry.name())
                    .containsAll(NEW_SOURCE_KEYS);
            assertThat(validate(node, current))
                    .as("%s 必须通过现行 schema（新格式合法，不是只靠旧格式向后兼容）", entry.name())
                    .isEmpty();
        }
    }

    // ---------- ③ 新清单也合法（含四字段） ----------

    @Test
    @DisplayName("新清单（含四字段、mappingVersion=null）通过 schema")
    void manifestWithNewFieldsValidates() throws IOException {
        JsonNode node = MAPPER.readTree("""
                {
                  "batchId": 101,
                  "batchNo": "ing-20260912100000-abcdef01",
                  "runtimeProfileId": 1,
                  "source": "local-file",
                  "status": "READY",
                  "startedAt": "2026-09-12T10:00:00.000",
                  "finishedAt": "2026-09-12T10:00:00.100",
                  "files": [],
                  "acceptedRecords": 0,
                  "quarantinedRecords": 0,
                  "acceptedBytes": 0,
                  "schemaVersions": [],
                  "acceptedUri": "accepted/101",
                  "quarantineUri": "quarantine/101",
                  "checksum": "00000000",
                  "sourceCode": "mock-mall",
                  "sourceId": 1,
                  "profileVersion": "1.0",
                  "mappingVersion": null
                }
                """);

        assertThat(validate(node, schema())).isEmpty();
    }

    @Test
    @DisplayName("mappingVersion 的占位值必须被 schema 拒绝？——不：schema 允许 string（P2 会用）")
    void mappingVersionSchemaAllowsFutureStringVersion() throws IOException {
        JsonNode node = MAPPER.readTree("""
                {
                  "batchId": 1, "batchNo": "ing-20260912100000-abcdef01", "runtimeProfileId": 1,
                  "source": "local-file", "status": "READY",
                  "startedAt": "2026-09-12T10:00:00", "finishedAt": "2026-09-12T10:00:01",
                  "files": [], "acceptedRecords": 0, "quarantinedRecords": 0, "acceptedBytes": 0,
                  "schemaVersions": [], "acceptedUri": "accepted/1", "quarantineUri": "quarantine/1",
                  "checksum": "0", "sourceCode": "mock-mall", "sourceId": 1, "profileVersion": "1.0",
                  "mappingVersion": "vocab-2026.09"
                }
                """);

        assertThat(validate(node, schema()))
                .as("schema 允许 string 是为 P2 留的口子；「P1-05 写出的必须是 null」由写出侧用例钉住"
                        + "（IngestionSourceManifestTest#manifestCarriesSourceIdentityFields）")
                .isEmpty();
    }

    // ---------- 小工具 ----------

    private static JsonNode read(Path file) throws IOException {
        return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static List<String> expectedNames(int from, int to) {
        List<String> names = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            names.add(i + ".json");
        }
        return names;
    }
}
