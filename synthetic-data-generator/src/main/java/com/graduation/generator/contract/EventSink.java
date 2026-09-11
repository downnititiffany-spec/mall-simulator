package com.graduation.generator.contract;

import java.util.Optional;

/**
 * 事件落点接口（V2.1 §4.1 {@code EventSink}，逐字对应四个方法）。
 *
 * <p>两个实现是后续切片的范围：文件模式 {@link JsonlEventSink}（本切片已实现）与
 * MALL_API 模式的"经由商城接口落库"实现（广告位留给 S4，不在此处预造）。</p>
 */
public interface EventSink extends AutoCloseable {

    /** 写一条事件（不负责轮转；调用方按需调用 {@link #rotateIfNeeded()}） */
    void write(CanonicalEvent event);

    /** 达到轮转阈值时关闭当前制品并开启下一个，返回刚关闭的制品 */
    Optional<Artifact> rotateIfNeeded();

    /** 落盘缓冲（不关闭制品） */
    void flush();

    /** 关闭当前制品并产出清单（契约清单：每个制品文件一份） */
    ArtifactManifest closeAndBuildManifest();

    /** 与 {@link #closeAndBuildManifest()} 同义，供 try-with-resources 使用 */
    @Override
    default void close() {
        closeAndBuildManifest();
    }
}
