package com.graduation.analytics.runtime;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.source.entity.SourceRegistry;
import com.graduation.analytics.warehouse.RunSourceIdentity;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-07 + A12 的唯一解析链测试（L0，不连库）：{@code sourceId → source_registry 行 →
 * (source_code, warehouse_prefix) → RunSourceIdentity}。
 *
 * <p>D-081 之④要求 {@code forSource} 至少覆盖两个负例；这里把**全部**负例都做成真实调用：
 * 未绑定源、源不存在、源编码为空、前缀为空、前缀形状非法（四码之一）。
 * 每一条都断言"拒绝，而不是返回某个缺省命名空间"——这是 fail-closed 口径的可判据形式。</p>
 *
 * <p>不连库：{@link SourceRegistryService} 是接口，本类用 Mockito 替身；真库上的同一语义
 * （行锁、NOT NULL 约束）由 E3 真 HTTP + 真 MySQL 取证。</p>
 */
class ActiveProfileWarehouseNamespaceProviderTest {

    private static final Long SOURCE_ID = 7L;

    private final SourceRegistryService sources = mock(SourceRegistryService.class);

    private static SourceRegistryView view(String sourceCode, String warehousePrefix) {
        SourceRegistry row = new SourceRegistry();
        row.setId(SOURCE_ID);
        row.setSourceCode(sourceCode);
        row.setDisplayName("异种源 B");
        row.setIngestMode("FILE");
        row.setProfilePath("analytics-server/source-profiles/mall-b.v1.json");
        row.setTimezone("Asia/Shanghai");
        row.setCurrency("CNY");
        row.setStatus(SourceRegistry.STATUS_DRAFT);
        row.setProfileVersion("1.0");
        row.setWarehousePrefix(warehousePrefix);
        return SourceRegistryView.of(row, SOURCE_ID);
    }

    private ActiveProfileWarehouseNamespaceProvider provider(SourceRegistryView view) {
        when(sources.get(SOURCE_ID)).thenReturn(view);
        return new ActiveProfileWarehouseNamespaceProvider(sources);
    }

    private static String codeOf(Throwable e) {
        return ((PlatformBizException) e).getCode();
    }

    // ── 正常路径：两个字段来自同一次读取 ──────────────────────────────────────

    @Test
    @DisplayName("runSource：源编码与命名空间一次读出，二者同源")
    void runSourceReturnsBothFieldsOfTheSameRow() {
        ActiveProfileWarehouseNamespaceProvider p = provider(view("mall-b", "dw_b"));

        RunSourceIdentity identity = p.runSource(SOURCE_ID);

        assertThat(identity.sourceCode()).isEqualTo("mall-b");
        assertThat(identity.namespace()).isEqualTo(WarehouseNamespace.of("dw_b"));
        assertThat(identity.namespace().ods()).isEqualTo("dw_b_ods");
    }

    @Test
    @DisplayName("currentRunSource：按唯一 ACTIVE 档案绑定的 source_id 解析（不自造第二种当前源口径）")
    void currentRunSourceUsesActiveBindingSourceId() {
        when(sources.currentSourceId()).thenReturn(Optional.of(SOURCE_ID));
        ActiveProfileWarehouseNamespaceProvider p = provider(view("mock-mall", "dw"));

        assertThat(p.currentRunSource().sourceCode()).isEqualTo("mock-mall");
        // 兼容视图（EvidenceBuilder 等消费方）必须与同一解析结果一致
        assertThat(p.current()).isEqualTo(p.runSource(SOURCE_ID).namespace());
        assertThat(p.forSource(SOURCE_ID)).isEqualTo(p.runSource(SOURCE_ID).namespace());
    }

    // ── 负例（D-081 之④） ────────────────────────────────────────────────────

    @Test
    @DisplayName("④ 未绑定源：SOURCE_NOT_BOUND（不回落「当前源」、不猜库名）")
    void unboundSourceFailsClosed() {
        when(sources.currentSourceId()).thenReturn(Optional.empty());
        ActiveProfileWarehouseNamespaceProvider p = new ActiveProfileWarehouseNamespaceProvider(sources);

        assertThatThrownBy(() -> p.runSource(null))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_BOUND))
                .hasMessageContaining("未绑定源");
        assertThatThrownBy(p::currentRunSource)
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_BOUND));
        assertThatThrownBy(p::current)
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_BOUND));
    }

    @Test
    @DisplayName("④ 源不存在：SOURCE_NOT_FOUND 原样透出（不被改写成别的码，也不返回缺省库名）")
    void missingSourcePropagatesNotFound() {
        when(sources.get(SOURCE_ID)).thenThrow(new PlatformBizException(
                PlatformBizException.SOURCE_NOT_FOUND, "源不存在：7"));
        ActiveProfileWarehouseNamespaceProvider p = new ActiveProfileWarehouseNamespaceProvider(sources);

        assertThatThrownBy(() -> p.runSource(SOURCE_ID))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_FOUND));
        assertThatThrownBy(() -> p.forSource(SOURCE_ID))
                .as("库名视图同样不得兜底返回 dw")
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("④ 库里前缀为空：PARAM_INVALID（null 与空白都拒，不回落到缺省 dw）")
    void blankStoredPrefixFailsClosed() {
        for (String blank : new String[] {null, "", "   "}) {
            ActiveProfileWarehouseNamespaceProvider p = provider(view("mall-b", blank));
            String label = blank == null ? "null" : "[" + blank + "]";
            assertThatThrownBy(() -> p.runSource(SOURCE_ID))
                    .as("库中前缀 %s 必须拒绝", label)
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID))
                    .hasMessageContaining("warehouse_prefix");
        }
    }

    @Test
    @DisplayName("④ 库里前缀形状非法：透出对应 WAREHOUSE_PREFIX_* 码")
    void illegalStoredPrefixKeepsItsCode() {
        Object[][] cases = {
                {"DW", "WAREHOUSE_PREFIX_PATTERN"},
                {"dw__b", "WAREHOUSE_PREFIX_UNDERSCORE"},
                {"default", "WAREHOUSE_PREFIX_RESERVED"},
                {"dw_ods", "WAREHOUSE_PREFIX_LAYER_SUFFIX"},
        };
        for (Object[] c : cases) {
            ActiveProfileWarehouseNamespaceProvider p = provider(view("mall-b", (String) c[0]));
            assertThatThrownBy(() -> p.runSource(SOURCE_ID))
                    .as("库中前缀 %s 必须拒绝", c[0])
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(c[1]));
        }
    }

    @Test
    @DisplayName("A12：库里源编码为空 → PARAM_INVALID（--sourceSystem 无值可下发，拒绝提交而非猜源）")
    void blankStoredSourceCodeFailsClosed() {
        for (String blank : new String[] {null, "", "   "}) {
            ActiveProfileWarehouseNamespaceProvider p = provider(view(blank, "dw_b"));
            String label = blank == null ? "null" : "[" + blank + "]";
            assertThatThrownBy(() -> p.runSource(SOURCE_ID))
                    .as("库中源编码 %s 必须拒绝", label)
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID))
                    .hasMessageContaining("source_code");
        }
    }

    @Test
    @DisplayName("本类不再读 runtime_profile.hive_database_prefix（构造依赖只有 SourceRegistryService）")
    void dependsOnlyOnSourceRegistry() {
        // 结构性声明：构造函数只有一个参数。旧实现读 RuntimeProfileService，
        // 若有人把这条旧链加回来，本断言会红（D-073：该列的生产读取点必须为零）。
        assertThat(ActiveProfileWarehouseNamespaceProvider.class.getDeclaredConstructors())
                .hasSize(1)
                .allSatisfy(ctor -> assertThat(ctor.getParameterTypes())
                        .containsExactly(SourceRegistryService.class));
    }
}
