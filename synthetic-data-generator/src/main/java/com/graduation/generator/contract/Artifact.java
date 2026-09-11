package com.graduation.generator.contract;

import java.time.Instant;
import java.util.Optional;

/**
 * 制品（单个产物文件）在运行期内的对账信息，对应 V2.1 §4.2 的 {@code generation_artifact} 行
 * （不含 run_id——run 由调用方在持久化时补上）。
 *
 * @param uri          制品位置（本实现写绝对 {@code file:///} URI：与采集清单的 acceptedUri 形态一致，便于人工对账）
 * @param kind         制品类型：{@code EVENT_JSONL} / {@code MANIFEST} / {@code DIRTY_SAMPLE}
 * @param checksum     内容校验和（算法见 {@link JsonlEventSink#CHECKSUM_ALGORITHM}）
 * @param bytes        文件字节数
 * @param recordCount  JSONL 行数（清单类制品为 0）
 * @param minEventTime 该制品内最小 event_time（§4.2 min_event_time）
 * @param maxEventTime 该制品内最大 event_time（§4.2 max_event_time）
 */
public record Artifact(String uri, String kind, String checksum, long bytes, long recordCount,
                       Instant minEventTime, Instant maxEventTime) {

    public static final String KIND_EVENT_JSONL = "EVENT_JSONL";
    public static final String KIND_MANIFEST = "MANIFEST";
    public static final String KIND_DIRTY_SAMPLE = "DIRTY_SAMPLE";

    public Artifact {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("制品 uri 必填（V2.1 §4.2）");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("制品 kind 必填（本实现扩展列，见 V1__generator_meta.sql）");
        }
        if (bytes < 0 || recordCount < 0) {
            throw new IllegalArgumentException("制品字节数与记录数不得为负");
        }
        if (recordCount > 0 && (minEventTime == null || maxEventTime == null)
                && KIND_EVENT_JSONL.equals(kind)) {
            throw new IllegalArgumentException("非空事件制品必须带 min/max event_time（§4.2 对账字段）：" + uri);
        }
    }

    /** 便于调用方区分"本次是否发生轮转" */
    public static Optional<Artifact> optional(Artifact artifact) {
        return Optional.ofNullable(artifact);
    }
}

