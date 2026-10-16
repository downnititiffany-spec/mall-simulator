package com.graduation.analytics.runtime;

import com.graduation.analytics.landing.LandingLayout;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-04B：{@code runtime_profile.landing_layout} 的**写入侧**唯一校验点（V22 新增列）。
 *
 * <p>这条链上只有两个写入入口（{@code create} / {@code update}），两边都必须过同一个归一：
 * 空值⇒{@code null}（"没写"），未登记值⇒{@code PARAM_INVALID} **且在落库之前**拒绝。
 * 为什么"未登记值拒绝"值得单独立测：拼错一个字母（{@code flume_raw} / {@code flume-raw}）
 * 若静默回落成默认布局，产出的是一批"采集成功、0 条记录"的正常批次——事后没有任何信号
 * 提示你看错了目录。所以这里断言的是"拒绝"，不是"取默认"。</p>
 *
 * <p>为什么空值归 {@code null} 而不是写死默认名：库里同时存在"从未配置"与"显式配成默认"
 * 会让读取侧多出一个口径；归一为 null 后，{@code LandingLayout.effective} 是唯一解释者。</p>
 */
class RuntimeProfileLandingLayoutWriteTest {

    private static final Long PROFILE_ID = 7L;

    private RuntimeProfileMapper profileMapper;
    private RuntimeProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(RuntimeProfileMapper.class);
        service = new RuntimeProfileServiceImpl(
                profileMapper,
                mock(LocalLandingStorage.class),
                mock(MetricStore.class),
                mock(CredentialService.class));
    }

    @Test
    @DisplayName("create：空白/未填 ⇒ 列留空（null），不写死默认布局名")
    void createNormalizesBlankToNull() {
        when(profileMapper.selectCount(any())).thenReturn(0L);

        RuntimeProfile created = service.create(profile("p-blank", "   "));

        assertThat(created.getLandingLayout()).as("空白等于没写：落库为 null").isNull();
        verify(profileMapper).insert(created);
    }

    @Test
    @DisplayName("create：登记值两侧空白被裁掉后原样落库（不做大小写折叠）")
    void createStoresRegisteredValueTrimmed() {
        when(profileMapper.selectCount(any())).thenReturn(0L);

        RuntimeProfile created = service.create(profile("p-flume", " " + LandingLayout.FLUME_RAW.name() + " "));

        assertThat(created.getLandingLayout()).isEqualTo(LandingLayout.FLUME_RAW.name());
        verify(profileMapper).insert(created);
    }

    @Test
    @DisplayName("create：未登记布局 ⇒ PARAM_INVALID，且绝不落库（失败必须在 insert 之前）")
    void createRejectsUnknownLayoutBeforeInsert() {
        when(profileMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(profile("p-typo", "flume-raw")))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.PARAM_INVALID))
                .hasMessageContaining("landing_layout");
        verify(profileMapper, never()).insert(any(RuntimeProfile.class));
    }

    @Test
    @DisplayName("update：PROFILE 已存在但非 ACTIVE 时改布局生效，写入值与返回快照一致")
    void updateNormalizesLandingLayout() {
        AtomicReference<RuntimeProfile> stored = new AtomicReference<>(draft(PROFILE_ID));
        when(profileMapper.selectById(PROFILE_ID)).thenAnswer(invocation -> stored.get());
        when(profileMapper.updateById(any(RuntimeProfile.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });

        RuntimeProfile patch = new RuntimeProfile();
        patch.setId(PROFILE_ID);
        patch.setLandingLayout(LandingLayout.FLUME_RAW.name());
        RuntimeProfile updated = service.update(patch);

        assertThat(updated.getLandingLayout()).as("读回的就是写进去的那个值").isEqualTo(LandingLayout.FLUME_RAW.name());
        assertThat(stored.get().getProfileCode()).as("profile_code 不可改，仍取库里那份").isEqualTo("p-draft");
    }

    @Test
    @DisplayName("update：未登记布局 ⇒ PARAM_INVALID，且不触发任何 UPDATE（校验先于落库）")
    void updateRejectsUnknownLayoutBeforeWrite() {
        when(profileMapper.selectById(PROFILE_ID)).thenReturn(draft(PROFILE_ID));

        RuntimeProfile patch = new RuntimeProfile();
        patch.setId(PROFILE_ID);
        patch.setLandingLayout("ROLLING_LOGS");

        assertThatThrownBy(() -> service.update(patch))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("landing_layout");
        verify(profileMapper, never()).updateById(any(RuntimeProfile.class));
    }

    @Test
    @DisplayName("update 传 null 时对象里归为 null；能否清空库中旧值取决于 updateById 跳过 null（既有边界）")
    void updateWithNullKeepsNullInMemory() {
        AtomicReference<RuntimeProfile> stored = new AtomicReference<>(draft(PROFILE_ID));
        when(profileMapper.selectById(PROFILE_ID)).thenAnswer(invocation -> stored.get());
        when(profileMapper.updateById(any(RuntimeProfile.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });

        RuntimeProfile patch = new RuntimeProfile();
        patch.setId(PROFILE_ID);
        patch.setLandingLayout(null);
        RuntimeProfile updated = service.update(patch);

        // 本条**不**声称"清空成功"：MyBatis-Plus 的 updateById 跳过 null 字段，与既有
        // hiveDatabasePrefix 完全同一处边界（已登记为遗留项）。这里只钉住归一结果，
        // 免得把"没报错"误读成"已清空"。
        assertThat(updated.getLandingLayout()).isNull();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static RuntimeProfile profile(String code, String landingLayout) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setProfileCode(code);
        profile.setLandingLayout(landingLayout);
        return profile;
    }

    private static RuntimeProfile draft(Long id) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(id);
        profile.setProfileCode("p-draft");
        profile.setStatus(RuntimeProfile.STATUS_DRAFT);
        profile.setVersion(1);
        profile.setCreatedAt(LocalDateTime.now());
        return profile;
    }
}
