package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.dto.SourceRegistryCreateReq;
import com.graduation.analytics.source.dto.SourceRegistryUpdateReq;
import com.graduation.analytics.source.entity.SourceRegistry;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-07 E2（D-081 之②与③）：源级数仓命名空间前缀的**真实拒绝**与**零迁移等价性**。
 *
 * <p>为什么单独一个类：前缀的"存在性 + 形状"门只有一处实现
 * （{@link WarehouseNamespaceProvider#requireSourcePrefix(String)}），它被挂在
 * {@code SourceRegistryServiceImpl} 的 create / update / activate **三条写入路径**上。
 * 三条路径必须给出**同一个错误码**——本类用 4 个错误码 × 3 条路径的矩阵逐格真实调用、
 * 逐格断言错误码与"没有写坏行"，而不是只测其中一条路径后推断另两条。</p>
 *
 * <p>本类是 L0（surefire，不连库）：数据库语义（NOT NULL 约束、事务回滚）由 E3 真 HTTP + 真 MySQL 取证。
 * 这里断言的是服务层逻辑——真库上 V18 把列建为 NOT NULL 是否有兜底，属 E3 的取证范围。</p>
 */
class SourceWarehousePrefixGateTest {

    private static final String SEED_CODE = "mock-mall";
    private static final String SEED_PATH = "analytics-server/source-profiles/mock-mall.v1.json";

    private final SourceRegistryTestSupport.InMemoryStore store = new SourceRegistryTestSupport.InMemoryStore();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path profileRoot;

    private SourceRegistryService service() {
        return new SourceRegistryServiceImpl(
                SourceRegistryTestSupport.mapper(store),
                SourceRegistryTestSupport.bindingMapper(store),
                new SourceProfileValidator(profileRoot.toString(), objectMapper));
    }

    /** 与真库 V16 种子行同形（V18 回填后 warehouse_prefix='dw'），status 由用例指定 */
    private SourceRegistry rowOf(String status, String warehousePrefix) {
        SourceRegistry seed = new SourceRegistry();
        seed.setSourceCode(SEED_CODE);
        seed.setDisplayName("参考商城（源 A）");
        seed.setIngestMode("FILE");
        seed.setProfilePath(SEED_PATH);
        seed.setTimezone("Asia/Shanghai");
        seed.setCurrency("CNY");
        seed.setStatus(status);
        seed.setProfileVersion("1.0");
        seed.setWarehousePrefix(warehousePrefix);
        return seed;
    }

    private SourceRegistryCreateReq createReq(String warehousePrefix) {
        return new SourceRegistryCreateReq("p2-07-probe-1", "探针源", "FILE", SEED_PATH,
                "Asia/Shanghai", "CNY", null, "1.0", warehousePrefix);
    }

    private static SourceRegistryUpdateReq updateReq(String warehousePrefix) {
        return new SourceRegistryUpdateReq(null, null, null, null, null, null, null, null, warehousePrefix);
    }

    private static String codeOf(Throwable e) {
        return ((PlatformBizException) e).getCode();
    }

    // ── ② 四码 × 三路径：逐格真实拒绝 ─────────────────────────────────────────

    @Test
    @DisplayName("② create 路径：四个错误码逐一真实拒绝，且不落行")
    void createRejectsAllFourCodes() {
        List<String> bad = List.of("DW", "dw__b", "default", "dw_ods");
        List<String> expected = List.of("WAREHOUSE_PREFIX_PATTERN", "WAREHOUSE_PREFIX_UNDERSCORE",
                "WAREHOUSE_PREFIX_RESERVED", "WAREHOUSE_PREFIX_LAYER_SUFFIX");
        for (int i = 0; i < bad.size(); i++) {
            SourceRegistryTestSupport.InMemoryStore fresh = new SourceRegistryTestSupport.InMemoryStore();
            SourceRegistryService svc = new SourceRegistryServiceImpl(
                    SourceRegistryTestSupport.mapper(fresh),
                    SourceRegistryTestSupport.bindingMapper(fresh),
                    new SourceProfileValidator(profileRoot.toString(), objectMapper));
            String value = bad.get(i);
            String want = expected.get(i);
            assertThatThrownBy(() -> svc.create(createReq(value)))
                    .as("create(%s) 必须拒绝", value)
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(want));
            assertThat(fresh.sourceCodes()).as("create(%s) 被拒后不得有行", value).isEmpty();
        }
    }

    @Test
    @DisplayName("② update 路径：四个错误码逐一真实拒绝，且库中前缀保持原值")
    void updateRejectsAllFourCodesAndKeepsStoredValue() {
        List<String> bad = List.of("DW", "dw__b", "default", "dw_ods");
        List<String> expected = List.of("WAREHOUSE_PREFIX_PATTERN", "WAREHOUSE_PREFIX_UNDERSCORE",
                "WAREHOUSE_PREFIX_RESERVED", "WAREHOUSE_PREFIX_LAYER_SUFFIX");
        for (int i = 0; i < bad.size(); i++) {
            SourceRegistryTestSupport.InMemoryStore fresh = new SourceRegistryTestSupport.InMemoryStore();
            SourceRegistryService svc = new SourceRegistryServiceImpl(
                    SourceRegistryTestSupport.mapper(fresh),
                    SourceRegistryTestSupport.bindingMapper(fresh),
                    new SourceProfileValidator(profileRoot.toString(), objectMapper));
            Long id = fresh.insert(rowOf(SourceRegistry.STATUS_DRAFT, "dw")).getId();
            String value = bad.get(i);
            String want = expected.get(i);
            assertThatThrownBy(() -> svc.update(id, updateReq(value)))
                    .as("update(%s) 必须拒绝（PUT 不能绕过 create 的门）", value)
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(want));
            assertThat(fresh.selectById(id).getWarehousePrefix())
                    .as("update(%s) 被拒后库中前缀必须仍是 dw", value).isEqualTo("dw");
        }
    }

    @Test
    @DisplayName("② activate 路径：四个错误码逐一真实拒绝，且状态与绑定都不动")
    void activateRejectsAllFourCodesAndChangesNothing() {
        List<String> bad = List.of("DW", "dw__b", "default", "dw_ods");
        List<String> expected = List.of("WAREHOUSE_PREFIX_PATTERN", "WAREHOUSE_PREFIX_UNDERSCORE",
                "WAREHOUSE_PREFIX_RESERVED", "WAREHOUSE_PREFIX_LAYER_SUFFIX");
        for (int i = 0; i < bad.size(); i++) {
            SourceRegistryTestSupport.InMemoryStore fresh = new SourceRegistryTestSupport.InMemoryStore();
            SourceRegistryService svc = new SourceRegistryServiceImpl(
                    SourceRegistryTestSupport.mapper(fresh),
                    SourceRegistryTestSupport.bindingMapper(fresh),
                    new SourceProfileValidator(profileRoot.toString(), objectMapper));
            // 直接种入一行"库里存着坏前缀"的历史行（模拟人工改库/历史遗留）——正常路径写不进这种值
            Long id = fresh.insert(rowOf(SourceRegistry.STATUS_DRAFT, bad.get(i))).getId();
            String value = bad.get(i);
            String want = expected.get(i);
            assertThatThrownBy(() -> fresh.inTransaction(() -> svc.activate(id)))
                    .as("activate 遇到库中坏前缀 %s 必须拒绝（幂等短路之前）", value)
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(want));
            assertThat(fresh.selectById(id).getStatus())
                    .as("activate(%s) 被拒后状态必须仍是 DRAFT", value)
                    .isEqualTo(SourceRegistry.STATUS_DRAFT);
            assertThat(fresh.bindingWriteCount()).as("activate(%s) 被拒后不得写绑定", value).isZero();
            fresh.endTransaction();
        }
    }

    // ── 缺省与空白：不存在"静默落 dw"这条路 ────────────────────────────────────

    @Test
    @DisplayName("create 缺前缀（null/空串/纯空白）→ PARAM_INVALID；不因 blankIsDefault 落成 dw")
    void createRejectsMissingPrefix() {
        for (String missing : new String[] {null, "", "   "}) {
            SourceRegistryTestSupport.InMemoryStore fresh = new SourceRegistryTestSupport.InMemoryStore();
            SourceRegistryService svc = new SourceRegistryServiceImpl(
                    SourceRegistryTestSupport.mapper(fresh),
                    SourceRegistryTestSupport.bindingMapper(fresh),
                    new SourceProfileValidator(profileRoot.toString(), objectMapper));
            String label = missing == null ? "null" : "[" + missing + "]";
            // 空白 ⇒ PARAM_INVALID（"必填"，不是"形状错"）：形状规则只管非空值，
            // 两者都不允许落到缺省 dw（契约 blankIsDefault 只对**读**视图生效，写入口必须显式给值）
            assertThatThrownBy(() -> svc.create(createReq(missing)))
                    .as("create(warehousePrefix=%s) 必须拒绝", label)
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID))
                    .hasMessageContaining("warehouse_prefix");
            assertThat(fresh.sourceCodes()).as("缺前缀的 create 不得有行（%s）", label).isEmpty();
        }
    }

    @Test
    @DisplayName("update 传 null = 保持原值；传空白 = 拒绝（不是改成缺省）")
    void updateNullKeepsBlankRejects() {
        Long id = store.insert(rowOf(SourceRegistry.STATUS_DRAFT, "dw_b")).getId();
        SourceRegistryService svc = service();

        assertThat(svc.update(id, updateReq(null)).source().warehousePrefix())
                .as("null = 不修改").isEqualTo("dw_b");
        assertThat(store.selectById(id).getWarehousePrefix()).isEqualTo("dw_b");

        for (String blank : new String[] {"", "   "}) {
            assertThatThrownBy(() -> svc.update(id, updateReq(blank)))
                    .as("update 传空白 [%s] 必须拒绝（显式清空 = 拒绝，不是回落缺省）", blank)
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID))
                    .hasMessageContaining("warehouse_prefix");
        }
        assertThat(store.selectById(id).getWarehousePrefix()).as("被拒的 update 不得写库").isEqualTo("dw_b");
    }

    // ── 合法值：原样存取（不 trim、不小写化） ──────────────────────────────────

    @ParameterizedTest(name = "合法前缀 [{0}] 原样入库")
    @ValueSource(strings = {"dw", "dw_b", "a", "src1", "dw_2026", "abcdefghijklmnopqrstuvwx"})
    void legalPrefixesAreStoredVerbatim(String prefix) {
        SourceRegistryTestSupport.InMemoryStore fresh = new SourceRegistryTestSupport.InMemoryStore();
        SourceRegistryService svc = new SourceRegistryServiceImpl(
                SourceRegistryTestSupport.mapper(fresh),
                SourceRegistryTestSupport.bindingMapper(fresh),
                new SourceProfileValidator(profileRoot.toString(), objectMapper));

        svc.create(createReq(prefix));

        assertThat(fresh.rowByCode("p2-07-probe-1").getWarehousePrefix())
                .as("入库值必须与请求逐字相同（唯一所有者不做归一化）").isEqualTo(prefix);
        assertThat(fresh.rowByCode("p2-07-probe-1").getWarehousePrefix().length()).isLessThanOrEqualTo(24);
    }

    @Test
    @DisplayName("不做 trim：' dw' 不是合法前缀（拒绝），且不会因 trim 后合法而放行")
    void whitespaceIsNotTrimmed() {
        SourceRegistryTestSupport.InMemoryStore fresh = new SourceRegistryTestSupport.InMemoryStore();
        SourceRegistryService svc = new SourceRegistryServiceImpl(
                SourceRegistryTestSupport.mapper(fresh),
                SourceRegistryTestSupport.bindingMapper(fresh),
                new SourceProfileValidator(profileRoot.toString(), objectMapper));

        assertThatThrownBy(() -> svc.create(createReq(" dw")))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("WAREHOUSE_PREFIX_PATTERN"));
        assertThat(fresh.sourceCodes()).isEmpty();
    }

    // ── ③ 零迁移等价性（规格 v2 的 compatibility 段明文要求"由 E2 用例逐字断言"） ──

    @Test
    @DisplayName("③ 零迁移：V18 回填值 dw 派生出的五个库名与改造前逐字相同")
    void backfilledDwDerivesLegacyDatabaseNames() {
        // 改造前：源 A 的 runtime_profile.hive_database_prefix 为 NULL/空串 → 规则取缺省 dw
        WarehouseNamespace legacy = WarehouseNamespace.ofNullable(null);
        // 改造后：源 A 的 source_registry.warehouse_prefix = 'dw'（V18 回填）
        WarehouseNamespace migrated = WarehouseNamespaceProvider.requireSourcePrefix("dw");

        assertThat(migrated).isEqualTo(legacy);
        // layers() 是「层 → 库名」的有序表；逐层取值与改造前的库名逐字比对
        assertThat(migrated.layers()).containsExactly(
                Map.entry("ods", "dw_ods"), Map.entry("dwd", "dw_dwd"), Map.entry("dim", "dw_dim"),
                Map.entry("dws", "dw_dws"), Map.entry("ads", "dw_ads"));
        assertThat(migrated.layers()).isEqualTo(legacy.layers());
        assertThat(migrated.prefix()).isEqualTo("dw");
        assertThat(migrated.ods()).isEqualTo("dw_ods");
        assertThat(migrated.dwd()).isEqualTo("dw_dwd");
        assertThat(migrated.dim()).isEqualTo("dw_dim");
        assertThat(migrated.dws()).isEqualTo("dw_dws");
        assertThat(migrated.ads()).isEqualTo("dw_ads");
    }

    @Test
    @DisplayName("③ 第二个源不撞名：dw_b 派生出 dw_b_*（而非 dw_*）")
    void secondSourceDoesNotCollideWithSourceA() {
        WarehouseNamespace other = WarehouseNamespaceProvider.requireSourcePrefix("dw_b");
        assertThat(other.ods()).isEqualTo("dw_b_ods");
        assertThat(other.ads()).isEqualTo("dw_b_ads");
        assertThat(other.layers().values())
                .doesNotContainAnyElementsOf(
                        WarehouseNamespaceProvider.requireSourcePrefix("dw").layers().values());
    }
}
