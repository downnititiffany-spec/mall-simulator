package com.graduation.analytics.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R7-2 L0 单测：不连库，验证 ADS 读写 DAO 的**白名单校验、参数绑定与 SQL 形状**
 * （主键必须含 snapshot_id、单行读取要 LIMIT 1、无快照号禁止查询）。
 */
class MetricAdsDaoTest {

    private final JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
    private final JdbcTemplate readJdbc = mock(JdbcTemplate.class);
    private final MetricAdsWriter writer = new MetricAdsWriter(publishJdbc);
    private final MetricAdsReader reader = new MetricAdsReader(readJdbc);

    @Test
    @DisplayName("8 张 ADS 白名单，且不包含无 Hive 来源的 category/region 表")
    void catalogContainsExactlyEightAdsTables() {
        assertThat(MetricAdsCatalog.ALL).hasSize(8);
        assertThat(MetricAdsCatalog.ALL).extracting(MetricAdsCatalog::name)
                .containsExactly("ads_operation_overview_m", "ads_sale_trend_m", "ads_behavior_funnel_m",
                        "ads_active_trend_m", "ads_hot_product_m", "ads_product_conversion_m",
                        "ads_user_profile_m", "ads_data_quality_m");
        assertThatThrownBy(() -> MetricAdsCatalog.require("ads_category_sale_m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricAdsCatalog.require("metric_value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非法表名/列名直接拒绝，且不产生任何 SQL")
    void rejectsUnknownTableAndColumn() {
        assertThatThrownBy(() -> writer.insertRows("ads_region_sale_m", "snap-1", "2026-09-04",
                List.of(Map.of("pv", 1L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.insertRows("ads_operation_overview_m", "snap-1", "2026-09-04",
                List.of(Map.of("pv; DROP TABLE metric_snapshot", 1L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.selectBySnapshot("ads_operation_overview_m` ; --", "snap-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(publishJdbc);
        verifyNoInteractions(readJdbc);
    }

    @Test
    @DisplayName("写入强制带 snapshot_id/dt，缺主键列拒绝")
    void insertRequiresSnapshotAndKeyColumns() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", "view");
        row.put("user_count", 3L);

        assertThatThrownBy(() -> writer.insertRows("ads_behavior_funnel_m", " ", "2026-09-04", List.of(row)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("snapshot_id");
        assertThatThrownBy(() -> writer.insertRows("ads_behavior_funnel_m", "snap-1", "", List.of(row)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dt");
        // ads_hot_product_m 主键含 rank_no，缺 rank_no 必须拒绝
        assertThatThrownBy(() -> writer.insertRows("ads_hot_product_m", "snap-1", "2026-09-04",
                List.of(Map.of("product_id", 1L, "heat_score", 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rank_no");
        // 同一批行列不一致必须拒绝
        assertThatThrownBy(() -> writer.insertRows("ads_behavior_funnel_m", "snap-1", "2026-09-04",
                List.of(Map.of("stage", "view", "user_count", 1L), Map.of("stage", "pay"))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(publishJdbc);
    }

    @Test
    @DisplayName("批量写入 SQL 只含提供的列并参数化绑定 snapshot_id/dt")
    void insertBuildsParameterizedBatch() {
        when(publishJdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[]{1, 1});

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(10L));
        rows.add(row(20L));

        int written = writer.insertRows("ads_operation_overview_m", "snap-r7", "2026-09-04", rows);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        verify(publishJdbc).batchUpdate(sql.capture(), batch.capture());

        assertThat(written).isEqualTo(2);
        assertThat(sql.getValue())
                .startsWith("INSERT INTO `ads_operation_overview_m` (")
                .contains("`snapshot_id`")
                .contains("`dt`")
                .contains("`pv`")
                .doesNotContain("refund_rate"); // 未提供的列交给 DDL 默认值，不写显式 NULL
        assertThat(batch.getValue()).hasSize(2);
        assertThat(batch.getValue().get(0)[0]).isEqualTo("snap-r7");
        assertThat(batch.getValue().get(0)[1]).isEqualTo("2026-09-04");
    }

    @Test
    @DisplayName("deleteSnapshot 覆盖全部 8 张表并按 snapshot_id 过滤")
    void deleteSnapshotCoversAllTables() {
        when(publishJdbc.update(anyString(), any(Object[].class))).thenReturn(2);

        int deleted = writer.deleteSnapshot("snap-r7");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(publishJdbc, times(8)).update(sql.capture(), any(Object[].class));
        assertThat(deleted).isEqualTo(16);
        assertThat(sql.getAllValues()).allSatisfy(s -> assertThat(s)
                .startsWith("DELETE FROM `ads_")
                .contains("WHERE `snapshot_id` = ?"));
    }

    @Test
    @DisplayName("读取固定 ACTIVE 快照：先解析快照号，再按 snapshot_id 查表；无 ACTIVE 返回空且不查表")
    void selectActivePinsOneSnapshot() {
        stubQuery("snap-active", Map.of("snapshot_id", "snap-active", "dt", "2026-09-04", "pv", 9L));

        List<Map<String, Object>> rows = reader.selectActive("ads_operation_overview_m", "2026-09-04");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("pv", 9L);
        assertThat(reader.activeSnapshotId()).isEqualTo("snap-active");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readJdbc, times(3)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues().get(1))
                .contains("FROM `ads_operation_overview_m`")
                .contains("`snapshot_id` = ?")
                .contains("AND `dt` = ?");
    }

    @Test
    @DisplayName("无 ACTIVE 快照时不回退任何数据源，直接返回空")
    void selectActiveWithoutActiveSnapshotReturnsEmpty() {
        stubQuery(null, Map.of());

        assertThat(reader.selectActive("ads_operation_overview_m", "2026-09-04")).isEmpty();
        assertThat(reader.selectOneActive("ads_operation_overview_m", "2026-09-04")).isNull();
        verify(readJdbc, times(2)).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("单行读取对含额外主键列的表加 ORDER BY + LIMIT 1，dt 为空时不加 dt 条件")
    void selectOneActiveUsesStableOrderAndLimit() {
        stubQuery("snap-active", Map.of("snapshot_id", "snap-active", "rank_no", 1));

        Map<String, Object> one = reader.selectOneActive("ads_hot_product_m", null);
        assertThat(one).containsEntry("rank_no", 1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readJdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues().get(1))
                .contains("FROM `ads_hot_product_m`")
                .contains("ORDER BY `rank_no`")
                .contains("LIMIT 1")
                .doesNotContain("`dt` = ?");
    }

    @Test
    @DisplayName("缺少 snapshot_id 的读取一律拒绝；countRows 按快照计数")
    void readRequiresSnapshotId() {
        assertThatThrownBy(() -> reader.selectBySnapshot("ads_operation_overview_m", "", "2026-09-04"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.countRows("ads_operation_overview_m", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(readJdbc);

        when(readJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
        assertThat(reader.countRows("ads_data_quality_m", "snap-1")).isEqualTo(7L);
    }

    private static Map<String, Object> row(long pv) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pv", pv);
        return row;
    }

    @SuppressWarnings("unchecked")
    private void stubQuery(String activeSnapshotId, Map<String, Object> tableRow) {
        when(readJdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.<String>getArgument(0);
            if (sql.startsWith("SELECT snapshot_id")) {
                return activeSnapshotId == null ? List.of() : List.of(activeSnapshotId);
            }
            return tableRow.isEmpty() ? List.of() : List.of(tableRow);
        });
    }
}
