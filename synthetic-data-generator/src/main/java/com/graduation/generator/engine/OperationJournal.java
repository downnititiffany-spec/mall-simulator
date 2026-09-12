package com.graduation.generator.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MALL_API 运行的操作流水（内存态，运行收口时由运行服务落成制品）。
 *
 * <p>线程安全：抓取阶段单线程写、收口阶段读，用 {@code synchronized} 兜住"以后有人并发跑"的可能，
 * 不给未来的并发留一个静默丢行的坑。</p>
 */
public final class OperationJournal {

    private final List<OperationJournalEntry> entries = new ArrayList<>();
    private long nextSeq = 1;

    /** 追加一条并返回带序号的记录 */
    public synchronized OperationJournalEntry append(String operation, boolean supported, String httpMethod,
                                                     String route, String canonicalId, String externalId,
                                                     String status, String detail) {
        OperationJournalEntry entry = new OperationJournalEntry(nextSeq++, operation, supported, httpMethod, route,
                canonicalId, externalId, status, detail);
        entries.add(entry);
        return entry;
    }

    public synchronized List<OperationJournalEntry> entries() {
        return List.copyOf(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 按操作名统计 {@code OK} 的条数（运行报告与验收对账用） */
    public synchronized Map<String, Long> succeededByOperation() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OperationJournalEntry entry : entries) {
            if (entry.succeeded()) {
                counts.merge(entry.operation(), 1L, Long::sum);
            }
        }
        return counts;
    }

    public synchronized long countByStatus(String status) {
        return entries.stream().filter(entry -> status.equals(entry.status())).count();
    }

    /**
     * 落成 JSONL（每行一个 JSON 对象，键序稳定）。
     *
     * <p>用自己拼 JSON 而不是引入序列化器：这几个字段全是字符串/布尔/数字，手写可控且与
     * {@link OperationJournalEntry#toJson()} 的键序完全一致（可复现对账）。</p>
     */
    public synchronized byte[] toJsonl() {
        StringBuilder out = new StringBuilder();
        for (OperationJournalEntry entry : entries) {
            out.append('{');
            Map<String, Object> row = entry.toJson();
            boolean first = true;
            for (Map.Entry<String, Object> field : row.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(field.getKey()).append("\":").append(value(field.getValue()));
            }
            out.append("}\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String value(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof Boolean || raw instanceof Number) {
            return raw.toString();
        }
        StringBuilder text = new StringBuilder("\"");
        for (char ch : raw.toString().toCharArray()) {
            switch (ch) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        text.append("\\u%04x".formatted((int) ch));
                    } else {
                        text.append(ch);
                    }
                }
            }
        }
        return text.append('"').toString();
    }
}
