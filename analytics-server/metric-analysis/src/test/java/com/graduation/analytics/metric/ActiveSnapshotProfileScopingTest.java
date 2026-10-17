package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.runtime.ActiveRuntimeProfileIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-F8b / F88-INV-01：MySqlMetricStore 与 MetricAdsReader profile 隔离与委托解析测试。
 *
 * <p>规约要求：
 * <ol>
 *   <li>现状事实：{@link MySqlMetricStore} 与 {@link MetricAdsReader} 均有带 {@code runtimeProfileId} 的重载方法，
 *       无参 {@code activeSnapshotId()} 经 {@code activeProfileId} 上下文委托重载。</li>
 *   <li>定向测试 1（独立作用域互不串扰）：两个 profile（如 101L 与 202L）各有 ACTIVE 快照时，
 *       带 profile 的重载方法各得本 profile 的快照，严格隔离、互不串扰。</li>
 *   <li>定向测试 2（上下文委托解析）：无参方法在上下文给定 profile 时，经 provider 解析到该 profile 对应的快照。</li>
 *   <li>空态与异常边界：上下文无 profile 或对应 profile 无 ACTIVE 快照时安全返回 null。</li>
 * </ol>
 * 本测试完全采用 Mockito 内存替身基建，不连接任何真实数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class ActiveSnapshotProfileScopingTest {

    private static final String SQL_SELECT_ACTIVE_BY_PROFILE =
            "SELECT snapshot_id FROM metric_snapshot WHERE status = ? AND runtime_profile_id = ? "
                    + "ORDER BY id DESC LIMIT 1";

    private static final long PROFILE_ALPHA = 101L;
    private static final long PROFILE_BETA = 202L;
    private static final String SNAPSHOT_ALPHA = "S_ALPHA_ACTIVE_101";
    private static final String SNAPSHOT_BETA = "S_BETA_ACTIVE_202";

    @Mock
    private JdbcTemplate publishJdbc;

    @Mock
    private JdbcTemplate readJdbc;

    @Mock
    private ActiveRuntimeProfileIdProvider profileIdProvider;

    private MySqlMetricStore metricStore;
    private MetricAdsReader adsReader;

    @BeforeEach
    void setUp() {
        metricStore = new MySqlMetricStore(publishJdbc, readJdbc, profileIdProvider);
        adsReader = new MetricAdsReader(readJdbc, profileIdProvider);
    }

    // ── 1. MySqlMetricStore profile 作用域重载与非干扰测试 ─────────────────────

    @Test
    @DisplayName("MySqlMetricStore: 带 profileId 重载精确返回各自 profile 快照且互不串扰")
    void mySqlMetricStoreOverloadReturnsRespectiveSnapshotWithoutInterference() {
        // 配置两个 profile 各有独立的 ACTIVE 快照
        stubSnapshotForProfile(PROFILE_ALPHA, SNAPSHOT_ALPHA);
        stubSnapshotForProfile(PROFILE_BETA, SNAPSHOT_BETA);

        // 调用重载方法查询 Profile ALPHA
        String actualAlpha = metricStore.activeSnapshotId(PROFILE_ALPHA);
        assertThat(actualAlpha)
                .as("Profile 101 必须获得自己的快照 S_ALPHA_ACTIVE_101")
                .isEqualTo(SNAPSHOT_ALPHA);
        assertThat(actualAlpha)
                .as("Profile 101 绝不能串扰得到 Profile 202 的快照")
                .isNotEqualTo(SNAPSHOT_BETA);

        // 调用重载方法查询 Profile BETA
        String actualBeta = metricStore.activeSnapshotId(PROFILE_BETA);
        assertThat(actualBeta)
                .as("Profile 202 必须获得自己的快照 S_BETA_ACTIVE_202")
                .isEqualTo(SNAPSHOT_BETA);
        assertThat(actualBeta)
                .as("Profile 202 绝不能串扰得到 Profile 101 的快照")
                .isNotEqualTo(SNAPSHOT_ALPHA);
    }

    @Test
    @DisplayName("MySqlMetricStore: 无参 activeSnapshotId() 委托当前上下文 profile 获得对应快照")
    void mySqlMetricStoreParameterlessMethodResolvesFromContextProvider() {
        stubSnapshotForProfile(PROFILE_ALPHA, SNAPSHOT_ALPHA);
        stubSnapshotForProfile(PROFILE_BETA, SNAPSHOT_BETA);

        // 场景 A: 上下文切到 Profile ALPHA
        when(profileIdProvider.activeRuntimeProfileId()).thenReturn(Optional.of(PROFILE_ALPHA));
        assertThat(metricStore.activeSnapshotId())
                .as("上下文为 Profile 101 时，无参方法必须解析到 Profile 101 的快照")
                .isEqualTo(SNAPSHOT_ALPHA);

        // 场景 B: 上下文切到 Profile BETA
        when(profileIdProvider.activeRuntimeProfileId()).thenReturn(Optional.of(PROFILE_BETA));
        assertThat(metricStore.activeSnapshotId())
                .as("上下文为 Profile 202 时，无参方法必须解析到 Profile 202 的快照")
                .isEqualTo(SNAPSHOT_BETA);
    }

    @Test
    @DisplayName("MySqlMetricStore: 上下文无 profile 或 profile 无快照时安全返回 null")
    void mySqlMetricStoreReturnsNullWhenNoActiveProfileOrNoSnapshot() {
        // 上下文返回 Optional.empty()
        when(profileIdProvider.activeRuntimeProfileId()).thenReturn(Optional.empty());
        assertThat(metricStore.activeSnapshotId()).isNull();

        // 指定一个库中无 ACTIVE 快照的 profile
        long emptyProfileId = 999L;
        when(readJdbc.query(eq(SQL_SELECT_ACTIVE_BY_PROFILE), any(RowMapper.class),
                eq(MetricSnapshot.STATUS_ACTIVE), eq(emptyProfileId)))
                .thenReturn(List.of());
        assertThat(metricStore.activeSnapshotId(emptyProfileId)).isNull();

        // 边界保护：非法 profileId <= 0 直接返回 null，不发 SQL
        assertThat(metricStore.activeSnapshotId(0L)).isNull();
        assertThat(metricStore.activeSnapshotId(-100L)).isNull();
    }

    @Test
    @DisplayName("MySqlMetricStore: 缺省构造器（provider=null）时无参方法安全返回 null，重载仍可传参查询")
    void mySqlMetricStoreWithNullProviderFailsClosedForParameterlessAndWorksForOverload() {
        MySqlMetricStore storeWithoutProvider = new MySqlMetricStore(publishJdbc, readJdbc);
        stubSnapshotForProfile(PROFILE_ALPHA, SNAPSHOT_ALPHA);

        // 无参方法安全返回 null（fail-closed），不抛 NPE
        assertThat(storeWithoutProvider.activeSnapshotId()).isNull();

        // 显式指定 profile 的重载仍然正常可用
        assertThat(storeWithoutProvider.activeSnapshotId(PROFILE_ALPHA)).isEqualTo(SNAPSHOT_ALPHA);
    }

    // ── 2. MetricAdsReader profile 作用域重载与非干扰测试 ───────────────────────

    @Test
    @DisplayName("MetricAdsReader: 带 profileId 重载精确返回各自 profile 快照且互不串扰")
    void metricAdsReaderOverloadReturnsRespectiveSnapshotWithoutInterference() {
        stubSnapshotForProfile(PROFILE_ALPHA, SNAPSHOT_ALPHA);
        stubSnapshotForProfile(PROFILE_BETA, SNAPSHOT_BETA);

        String actualAlpha = adsReader.activeSnapshotId(PROFILE_ALPHA);
        assertThat(actualAlpha)
                .as("MetricAdsReader 查 Profile 101 必须精确获得 S_ALPHA_ACTIVE_101")
                .isEqualTo(SNAPSHOT_ALPHA);
        assertThat(actualAlpha)
                .as("MetricAdsReader 查 Profile 101 不得混入 Profile 202 快照")
                .isNotEqualTo(SNAPSHOT_BETA);

        String actualBeta = adsReader.activeSnapshotId(PROFILE_BETA);
        assertThat(actualBeta)
                .as("MetricAdsReader 查 Profile 202 必须精确获得 S_BETA_ACTIVE_202")
                .isEqualTo(SNAPSHOT_BETA);
        assertThat(actualBeta)
                .as("MetricAdsReader 查 Profile 202 不得混入 Profile 101 快照")
                .isNotEqualTo(SNAPSHOT_ALPHA);
    }

    @Test
    @DisplayName("MetricAdsReader: 无参 activeSnapshotId() 委托当前上下文 profile 获得对应快照")
    void metricAdsReaderParameterlessMethodResolvesFromContextProvider() {
        stubSnapshotForProfile(PROFILE_ALPHA, SNAPSHOT_ALPHA);
        stubSnapshotForProfile(PROFILE_BETA, SNAPSHOT_BETA);

        when(profileIdProvider.activeRuntimeProfileId()).thenReturn(Optional.of(PROFILE_ALPHA));
        assertThat(adsReader.activeSnapshotId())
                .as("上下文为 Profile 101 时，MetricAdsReader 无参方法必须解析为 S_ALPHA_ACTIVE_101")
                .isEqualTo(SNAPSHOT_ALPHA);

        when(profileIdProvider.activeRuntimeProfileId()).thenReturn(Optional.of(PROFILE_BETA));
        assertThat(adsReader.activeSnapshotId())
                .as("上下文为 Profile 202 时，MetricAdsReader 无参方法必须解析为 S_BETA_ACTIVE_202")
                .isEqualTo(SNAPSHOT_BETA);
    }

    @Test
    @DisplayName("MetricAdsReader: 上下文无 profile 或 provider 为 null 时安全返回 null")
    void metricAdsReaderReturnsNullWhenNoActiveProfileOrNullProvider() {
        when(profileIdProvider.activeRuntimeProfileId()).thenReturn(Optional.empty());
        assertThat(adsReader.activeSnapshotId()).isNull();

        MetricAdsReader readerWithoutProvider = new MetricAdsReader(readJdbc, null);
        assertThat(readerWithoutProvider.activeSnapshotId()).isNull();
    }

    // ── 辅助桩方法 ────────────────────────────────────────────────────────────

    private void stubSnapshotForProfile(long runtimeProfileId, String snapshotId) {
        when(readJdbc.query(eq(SQL_SELECT_ACTIVE_BY_PROFILE), any(RowMapper.class),
                eq(MetricSnapshot.STATUS_ACTIVE), eq(runtimeProfileId)))
                .thenReturn(List.of(snapshotId));
    }
}
