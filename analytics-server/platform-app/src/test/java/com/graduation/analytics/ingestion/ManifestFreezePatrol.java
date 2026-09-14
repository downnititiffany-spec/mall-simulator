package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 采集清单**运行时巡检**（V25-T02 ②）：只对"冻结在案的历史文件"判红，新到的采集文件只报告。
 *
 * <h2>为什么必须与契约测试分开</h2>
 * <p>{@code landing/manifests} 不是夹具目录，而是**生产落盘目录**：每次真实采集都会新增一个
 * {@code <N>.json}（见 {@code docs/contracts} 与跑批证据）。原用例
 * {@code allOnDiskManifestsStillValidate} 把"目录里所有 .json"当成历史回填清单来验，
 * 于是 2026-09-12 夜里真实采集写下的 {@code 40.json..43.json}（19 键，含 P1-05 新增四键）
 * 被误判成"旧清单被回填"，门禁自那时起即红 —— 这是**把历史数据源（= 目录）当成契约输入**的缺陷。</p>
 *
 * <p>拆法（指导书 §9.5）：</p>
 * <ul>
 *   <li><b>契约</b>：冻结副本走 {@code IngestionManifestSourceSchemaTest}（密闭、可重复）；</li>
 *   <li><b>巡检</b>：真实目录走本类 —— 冻结清单（{@code freeze.json} 里登记名字与 sha256）必须
 *       <b>原样留在原地</b>：缺失、字节改动、被回填四键 ⇒ 红；<b>新到的文件不算违规</b>，
 *       逐个报出来（名字/键数/是否含新键/sha256）供人工确认，这才是"新增采集"的正确语义。</li>
 * </ul>
 *
 * <p><b>保密边界</b>：清单只含计数与 URI，无凭据；巡检只读，不写入、不删除任何文件
 * （{@code landing/} 被 {@code .gitignore:30} 忽略，删掉它 <b>不会</b>在 git status 里显形，
 * 所以"目录必须在"本身就是一条必须显式断言的条件）。</p>
 */
final class ManifestFreezePatrol {

    /** 冻结登记表（与冻结副本同目录，{@code src/test/resources/ingestion-manifest-freeze/}） */
    static final String FREEZE_RESOURCE = "/ingestion-manifest-freeze/freeze.json";

    /** 冻结条目：名字、落盘字节的 sha256、顶层键数、是否属于 P1-05 之前的旧格式 */
    record Entry(String name, String sha256, int keys, boolean legacy) {
        String group() {
            return legacy ? "legacy" : "new";
        }
    }

    /** 新到文件（不判红，只登记） */
    record Arrival(String name, String sha256, int keys, boolean hasNewSourceKeys) {
        @Override
        public String toString() {
            return String.format("%s sha256=%s keys=%d 含新键=%s", name, sha256.substring(0, 12), keys,
                    hasNewSourceKeys);
        }
    }

    /** 巡检结果：{@code violations} 为空才算绿；{@code arrivals} 是"新到采集文件"清单。 */
    record Report(int frozenChecked, List<String> violations, List<Arrival> arrivals) {
        boolean green() {
            return violations.isEmpty();
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("冻结清单核对: ").append(frozenChecked).append(" 个；违规: ").append(violations.size());
            violations.forEach(v -> sb.append(System.lineSeparator()).append("  [违规] ").append(v));
            sb.append(System.lineSeparator()).append("新到文件（不判红）: ").append(arrivals.size());
            arrivals.forEach(a -> sb.append(System.lineSeparator()).append("  [新到] ").append(a));
            return sb.toString();
        }
    }

    private ManifestFreezePatrol() {
    }

    static JsonNode freezeManifest() throws IOException {
        try (InputStream in = ManifestFreezePatrol.class.getResourceAsStream(FREEZE_RESOURCE)) {
            if (in == null) {
                throw new IOException("冻结登记表缺失（classpath）: " + FREEZE_RESOURCE);
            }
            return IngestionManifestSchemaSubset.MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 读取冻结登记表里的一组条目（{@code "legacy"} 或 {@code "new"}）。 */
    static List<Entry> entries(JsonNode freeze, String group) {
        List<Entry> out = new ArrayList<>();
        boolean legacy = "legacy".equals(group);
        for (JsonNode e : freeze.path(group)) {
            out.add(new Entry(e.path("name").asText(), e.path("sha256").asText(),
                    e.path("keys").asInt(), legacy));
        }
        return out;
    }

    /** sha256(小写十六进制) —— 与 {@code Get-FileHash -Algorithm SHA256} 输出口径一致（大小写不敏感比较）。 */
    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 巡检一个真实清单目录。
     *
     * @param dir           清单目录（生产为 {@code landing/manifests}；负例用临时目录）
     * @param frozen        冻结条目（缺失/改动/回填 ⇒ 违规）
     * @param newSourceKeys P1-05 新增键（用于判"是否被回填"）
     */
    static Report inspect(Path dir, List<Entry> frozen, List<String> newSourceKeys) throws IOException {
        List<String> violations = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            violations.add("清单目录不存在: " + dir
                    + " —— 历史清单必须留在原地；被忽略的目录删掉不会在 git status 里显形，因此这里显式判红");
            return new Report(0, violations, List.of());
        }

        Set<String> frozenNames = new LinkedHashSet<>();
        for (Entry entry : frozen) {
            frozenNames.add(entry.name());
            Path file = dir.resolve(entry.name());
            if (!Files.isRegularFile(file)) {
                violations.add(entry.name() + " 缺失（冻结清单里的历史文件必须留在原地）");
                continue;
            }
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(entry.sha256())) {
                violations.add(entry.name() + " 内容被改动：sha256 " + actual + " ≠ 冻结 " + entry.sha256());
            }
            JsonNode node = IngestionManifestSchemaSubset.MAPPER
                    .readTree(Files.readString(file, StandardCharsets.UTF_8));
            Set<String> keys = IngestionManifestSchemaSubset.fieldNames(node);
            List<String> backfilled = newSourceKeys.stream().filter(keys::contains).toList();
            if (entry.legacy() && !backfilled.isEmpty()) {
                violations.add(entry.name() + " 疑似被回填：旧格式清单里出现了 P1-05 新增键 " + backfilled);
            }
            if (!entry.legacy() && backfilled.isEmpty()) {
                violations.add(entry.name() + " 与冻结登记不符：新格式清单应当含 " + newSourceKeys);
            }
        }

        List<Arrival> arrivals = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingInt(p -> numericPrefix(p.getFileName().toString())))
                    .toList()) {
                String name = file.getFileName().toString();
                if (frozenNames.contains(name)) {
                    continue;
                }
                JsonNode node = IngestionManifestSchemaSubset.MAPPER
                        .readTree(Files.readString(file, StandardCharsets.UTF_8));
                Set<String> keys = IngestionManifestSchemaSubset.fieldNames(node);
                arrivals.add(new Arrival(name, sha256(file), keys.size(),
                        newSourceKeys.stream().anyMatch(keys::contains)));
            }
        }
        return new Report(frozen.size(), violations, arrivals);
    }

    /** 名字里的数字前缀（{@code 40.json → 40}）；非数字名排到最后。 */
    private static int numericPrefix(String name) {
        int end = 0;
        while (end < name.length() && Character.isDigit(name.charAt(end))) {
            end++;
        }
        return end == 0 ? Integer.MAX_VALUE : Integer.parseInt(name.substring(0, end));
    }
}
