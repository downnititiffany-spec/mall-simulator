package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-05 / D-037 裁决 6：{@code ingestion-manifest.v1} 的四个源身份字段与**历史清单的向后兼容**。
 *
 * <p>契约权威是 {@code contract-specs/schemas/ingestion-manifest.v1.schema.json}
 * （目录级 {@code 1.2.0}，P1-05 加法扩展）。本类做**结构对账 + 历史产物回归**：</p>
 * <ol>
 *   <li>四个新键在 schema 的 {@code properties} 里，且**都不在** {@code required} 里；</li>
 *   <li>已落盘的历史清单（{@code landing/manifests/*.json}，实测 39 个，每个 15 个键、无新键）
 *       在 P1-05 前后的**违规集合逐字相同**——这正是"不许进 required"的理由（裁决 6）；</li>
 *   <li>新清单（含四字段、{@code mappingVersion=null}）同样通过——加法不能变成"新的合法、旧的非法"。</li>
 * </ol>
 *
 * <p><b>为什么不写"全部历史清单零违规"</b>：实测 {@code 1.json}-{@code 5.json} 这 5 个文件的
 * {@code batchId} 是字符串，与现行 schema 的 {@code batchId: integer} 不符。这是**先于 P1-05**
 * 存在的写入期漂移，且权威文件自己的 {@code batchId} description 里就有记载；本 lane 既不回填
 * 历史清单（裁决 6），也不改契约。因此这里改成可证伪的**集合差**断言，并把该漂移的
 * 文件名与条数显式钉住——把既有事实藏起来比断言失败更糟。</p>
 *
 * <p><b>为什么不引 JSON Schema 校验器</b>：离线构建无法新增依赖，且仓内已有同一先例
 * （{@code CanonicalEventSchemaParityTest} 的说明："只做结构对账：不引入 JSON Schema 校验器依赖"）。
 * 本类因此**自己实现**该 schema 用到的关键字子集（{@code type}/{@code required}/{@code maxLength}/
 * {@code minLength}/{@code minimum}/{@code const}），并把"子集"这件事显式写在这里，避免把
 * "结构对账"误读成"完整 Draft 2020-12 校验"。**明确不在覆盖范围内**的既有关键字：
 * {@code pattern}（如 {@code batchNo}）、{@code items}/{@code $defs} 的递归下沉、{@code format}。
 * <b>取证边界</b>：真机端到端落盘的四字段取值由 E3（真实例 + 真 MySQL 副本库）取证，
 * 两者不可互相替代。</p>
 */
class IngestionManifestSourceSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path SCHEMA =
            RepoRoot.path("contract-specs/schemas/ingestion-manifest.v1.schema.json");

    /** P1-05 新增的四个可选键（D-037 裁决 6） */
    private static final List<String> NEW_SOURCE_KEYS =
            List.of("sourceCode", "sourceId", "profileVersion", "mappingVersion");

    private static JsonNode schema() throws IOException {
        return MAPPER.readTree(Files.readString(SCHEMA, StandardCharsets.UTF_8));
    }

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

    // ---------- ② 历史清单回归：已落盘的清单仍合法 ----------

    @Test
    @DisplayName("回归：真实历史清单 landing/manifests/30.json 仍通过现行 schema（15 键、无新键）")
    void historicalManifest30StillValidates() throws IOException {
        Path manifest = RepoRoot.path("landing/manifests/30.json");
        assertThat(Files.isRegularFile(manifest))
                .as("历史清单是真实产物，不是夹具：%s", manifest)
                .isTrue();

        JsonNode node = MAPPER.readTree(Files.readString(manifest, StandardCharsets.UTF_8));

        assertThat(fieldNames(node))
                .as("前提校验：该清单确实没有四个新键（否则本用例证明不了向后兼容）")
                .doesNotContainAnyElementsOf(NEW_SOURCE_KEYS);
        assertThat(validate(node, schema())).isEmpty();
    }

    @Test
    @DisplayName("回归：**全部 39 个**已落盘清单在 P1-05 前后的违规集合逐字相同（加法不得新增一条违规）")
    void allOnDiskManifestsStillValidate() throws IOException {
        Path dir = RepoRoot.path("landing/manifests");
        assertThat(Files.isDirectory(dir)).as("历史清单目录：%s", dir).isTrue();

        List<String> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> files.add(p.getFileName().toString()));
        }
        files.sort(java.util.Comparator.comparingInt(n -> Integer.parseInt(n.substring(0, n.length() - 5))));
        assertThat(files).as("目录里应当有已落盘清单").isNotEmpty();

        // 基线 = 现行 schema **去掉** P1-05 新增的四个属性。这样"加法是否让旧清单变非法"
        // 就成了同一套子集实现、同一批文件上的**集合差**，而不是一句无法证伪的口头保证。
        JsonNode current = schema();
        JsonNode baseline = withoutNewSourceProperties(current);

        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        List<String> backfilled = new ArrayList<>();
        for (String name : files) {
            JsonNode node = MAPPER.readTree(Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
            if (fieldNames(node).stream().anyMatch(NEW_SOURCE_KEYS::contains)) {
                backfilled.add(name);
            }
            validate(node, baseline).forEach(v -> before.add(name + ": " + v));
            validate(node, current).forEach(v -> after.add(name + ": " + v));
        }

        assertThat(backfilled)
                .as("前提校验：历史清单一个都不许被回填/改写（D-037 裁决 6）——否则下面的集合对比没有意义")
                .isEmpty();
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

    /** 现行 schema 的深拷贝，去掉 P1-05 新增的四个属性（其余关键字与取值逐字不动）。 */
    private static JsonNode withoutNewSourceProperties(JsonNode schema) {
        ObjectNode copy = schema.deepCopy();
        ObjectNode props = (ObjectNode) copy.path("properties");
        NEW_SOURCE_KEYS.forEach(props::remove);
        return copy;
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

    // ---------- 极小的 schema 关键字子集实现 ----------

    /** 返回违规说明列表（空 = 通过）。只实现本 schema 用到的关键字，范围写在类注释里。 */
    private static List<String> validate(JsonNode node, JsonNode schema) {
        List<String> out = new ArrayList<>();
        for (String key : fieldNames(schema.path("required"))) {
            if (!node.has(key)) {
                out.add("缺少 required 键: " + key);
            }
        }
        JsonNode props = schema.path("properties");
        node.fieldNames().forEachRemaining(name -> {
            JsonNode rule = props.path(name);
            if (rule.isMissingNode()) {
                return;   // additionalProperties: true
            }
            JsonNode value = node.get(name);
            List<String> types = typeOf(rule);
            if (!types.isEmpty() && !matchesAnyType(value, types)) {
                out.add(name + " 类型不符：期望 " + types + "，实际 " + value.getNodeType());
            }
            if (value.isTextual()) {
                int max = rule.path("maxLength").asInt(-1);
                if (max > 0 && value.asText().length() > max) {
                    out.add(name + " 超长：" + value.asText().length() + " > " + max);
                }
                int min = rule.path("minLength").asInt(-1);
                if (min > 0 && value.asText().length() < min) {
                    out.add(name + " 过短：" + value.asText().length() + " < " + min);
                }
            }
            if (value.isNumber()) {
                if (rule.has("minimum") && value.asLong() < rule.path("minimum").asLong()) {
                    out.add(name + " 小于 minimum：" + value.asLong());
                }
            }
            if (rule.has("const") && !rule.path("const").asText().equals(value.asText())) {
                out.add(name + " 不等于 const " + rule.path("const").asText());
            }
        });
        return out;
    }

    private static boolean matchesAnyType(JsonNode value, List<String> types) {
        for (String type : types) {
            switch (type) {
                case "string" -> {
                    if (value.isTextual()) {
                        return true;
                    }
                }
                case "integer" -> {
                    if (value.isIntegralNumber()) {
                        return true;
                    }
                }
                case "number" -> {
                    if (value.isNumber()) {
                        return true;
                    }
                }
                case "null" -> {
                    if (value.isNull()) {
                        return true;
                    }
                }
                case "object" -> {
                    if (value.isObject()) {
                        return true;
                    }
                }
                case "array" -> {
                    if (value.isArray()) {
                        return true;
                    }
                }
                case "boolean" -> {
                    if (value.isBoolean()) {
                        return true;
                    }
                }
                default -> throw new IllegalStateException("本极小子集未实现类型: " + type);
            }
        }
        return false;
    }

    private static List<String> typeOf(JsonNode rule) {
        JsonNode type = rule.path("type");
        if (type.isMissingNode()) {
            return List.of();
        }
        if (type.isArray()) {
            List<String> out = new ArrayList<>();
            type.forEach(t -> out.add(t.asText()));
            return out;
        }
        return List.of(type.asText());
    }

    /**
     * 取 JSON <b>对象</b>的字段名，或 JSON <b>数组</b>里的字符串元素。
     *
     * <p>两种都要处理是踩过的坑：{@code required} 是**数组**，而 Jackson 的
     * {@code JsonNode.fieldNames()} 对数组返回空迭代器（不报错）。若只写对象分支，
     * {@code required} 会被读成"空集合"，于是 {@code hasSize(15)} 报
     * {@code Expected size: 15 but was: 0}——看起来像 schema 少了字段，
     * 实际是测试自己读错了结构（假红；同一处若写成 {@code doesNotContain} 就会变成假绿）。</p>
     */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node.isArray()) {
            node.forEach(element -> names.add(element.asText()));
            return names;
        }
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
