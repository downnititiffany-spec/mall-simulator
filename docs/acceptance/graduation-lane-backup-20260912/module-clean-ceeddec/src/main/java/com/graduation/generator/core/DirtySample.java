package com.graduation.generator.core;

/**
 * 脏数据样本（§20.5）：一条被注入的脏数据及其期望处理结果。
 *
 * <p>移植说明：旧商城中该类型嵌在 {@code DirtyDataInjector} 内部
 * （{@code DirtyDataInjector.DirtySample(type, expectedHandling, json)}）。注入器本身
 * 属于后续切片，而纯规划层的 {@link GenerationResult} 需要引用这个类型，
 * 因此这里把它提升为 core 下的普通记录：字段名、顺序与语义完全不变。</p>
 */
public record DirtySample(String type, String expectedHandling, String json) {
}
