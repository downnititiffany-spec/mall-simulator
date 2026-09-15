package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.mapping.activation.entity.SourceMappingActiveEntity;
import com.graduation.analytics.mapping.activation.mapper.SourceMappingActiveMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcActiveMappingPointerStore} 的 L0 行为（S2-03.1）：往返保真 + 走的是哪条读写路径。
 *
 * <p><b>本类证不了什么</b>（必须如实登记）：SQL 是否真能在 MySQL 上执行、{@code FOR UPDATE}
 * 是否真能挡住并发、{@code ON DUPLICATE KEY UPDATE} 是否真覆盖同一行——这些是**真库行为**，
 * 只能由 MySqlIT 取证。本类能证的是"实体 ↔ 指针的映射一个字段都不掉"，
 * 以及"读走 find、判走 lock、写走 upsert"这三条调用路径没有被写反。</p>
 *
 * <p>替身用动态代理而不是"实现接口的假类"：{@code SourceMappingActiveMapper} 继承
 * MyBatis-Plus 的 {@code BaseMapper}（几十个默认方法），假类实现它的成本远大于收益，
 * 且会让本测试变成"假类与真接口的同步维护"。代理只接管本类真正会调用的三个方法，
 * 其余方法一律抛错（**不是**静默返回 null：被调用到就说明测试覆盖假设已过期）。</p>
 */
class JdbcActiveMappingPointerStoreTest {

    private static final long SOURCE_ID = 7L;
    private static final String SOURCE_CODE = "s2-031-jdbc";

    @Test
    @DisplayName("往返保真：写进去的每个字段都能读回来（source_code 来自源登记 JOIN，不是本表列）")
    void roundTripKeepsEveryField() {
        FakeTable table = new FakeTable();
        JdbcActiveMappingPointerStore store = new JdbcActiveMappingPointerStore(table.mapper());
        ActiveMappingPointer pointer = pointer("a".repeat(64), "b".repeat(64));

        store.save(pointer);
        ActiveMappingPointer read = store.find(SOURCE_ID).orElseThrow();

        assertThat(read)
                .as("指针跨存储往返后必须逐字段相等（少一个字段就意味着重启后判据缺一块）")
                .isEqualTo(pointer);
    }

    @Test
    @DisplayName("读路径分工：find 走不加锁读，lockBySourceId 走锁定读 —— 不许调换")
    void readPathsAreNotSwapped() {
        FakeTable table = new FakeTable();
        JdbcActiveMappingPointerStore store = new JdbcActiveMappingPointerStore(table.mapper());

        store.find(SOURCE_ID);
        assertThat(table.calls).as("find 只允许走 findById").containsExactly("findById");

        table.calls.clear();
        store.lockBySourceId(SOURCE_ID);
        assertThat(table.calls)
                .as("事务内的'读完就决定写不写'必须走 FOR UPDATE 的 lockById，否则并发会各写一次")
                .containsExactly("lockById");
    }

    @Test
    @DisplayName("没有激活过 ⇒ 空（不回落最新画像，也不抛异常）")
    void missingRowIsEmpty() {
        JdbcActiveMappingPointerStore store =
                new JdbcActiveMappingPointerStore(new FakeTable().mapper());

        assertThat(store.find(SOURCE_ID)).isEmpty();
        assertThat(store.lockBySourceId(SOURCE_ID)).isEmpty();
    }

    @Test
    @DisplayName("同源再次写入 ⇒ 覆盖同一行（一源一行，不新增行、同源只留一个指针）")
    void secondSaveReplacesTheSameRow() {
        FakeTable table = new FakeTable();
        JdbcActiveMappingPointerStore store = new JdbcActiveMappingPointerStore(table.mapper());

        store.save(pointer("a".repeat(64), "b".repeat(64)));
        store.save(pointer("c".repeat(64), "d".repeat(64)));

        assertThat(table.rows).as("同源不得出现第二行").hasSize(1);
        assertThat(store.find(SOURCE_ID).orElseThrow().profileChecksum()).isEqualTo("c".repeat(64));
    }

    private static ActiveMappingPointer pointer(String profileChecksum, String contractChecksum) {
        return new ActiveMappingPointer(SOURCE_ID, SOURCE_CODE, "profiles/raw-a.v2.json", "2.0",
                profileChecksum, "1.0", contractChecksum, "dr-s2031-0001",
                LocalDateTime.parse("2026-09-21T10:20:30"), "dev1");
    }

    /**
     * 进程内"表"替身：主键 Map 模拟"一个源一行"（同源二次 upsert 覆盖而非新增），
     * 并记录调用路径（{@code findById} / {@code lockById} / {@code upsert}）供断言。
     */
    private static final class FakeTable {

        private final Map<Long, SourceMappingActiveEntity> rows = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        SourceMappingActiveMapper mapper() {
            return (SourceMappingActiveMapper) Proxy.newProxyInstance(
                    SourceMappingActiveMapper.class.getClassLoader(),
                    new Class<?>[]{SourceMappingActiveMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findById" -> read("findById", (Long) args[0]);
                        case "lockById" -> read("lockById", (Long) args[0]);
                        case "upsert" -> upsert((SourceMappingActiveEntity) args[0]);
                        case "toString" -> "FakeTable.mapper";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "本替身只接管 findById/lockById/upsert，被调用到的方法=" + method.getName());
                    });
        }

        private SourceMappingActiveEntity read(String call, Long sourceId) {
            calls.add(call);
            SourceMappingActiveEntity row = rows.get(sourceId);
            return row == null ? null : copy(row);
        }

        private int upsert(SourceMappingActiveEntity row) {
            calls.add("upsert");
            rows.put(row.getSourceId(), copy(row));
            return 1;
        }

        /** 模拟"source_code 由 JOIN source_registry 取回"：本表不存它，取回时按登记值补齐。 */
        private SourceMappingActiveEntity copy(SourceMappingActiveEntity row) {
            SourceMappingActiveEntity copy = new SourceMappingActiveEntity();
            copy.setSourceId(row.getSourceId());
            copy.setSourceCode(SOURCE_CODE);
            copy.setProfileRef(row.getProfileRef());
            copy.setProfileVersion(row.getProfileVersion());
            copy.setProfileChecksum(row.getProfileChecksum());
            copy.setContractVersion(row.getContractVersion());
            copy.setContractChecksum(row.getContractChecksum());
            copy.setReportId(row.getReportId());
            copy.setActivatedAt(row.getActivatedAt());
            copy.setActivatedBy(row.getActivatedBy());
            copy.setCreatedAt(row.getCreatedAt());
            copy.setUpdatedAt(row.getUpdatedAt());
            return copy;
        }
    }
}
