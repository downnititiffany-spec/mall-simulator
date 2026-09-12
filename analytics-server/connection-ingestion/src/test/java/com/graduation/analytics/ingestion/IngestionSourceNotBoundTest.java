package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.source.SourceRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P1-05「未绑定源必须 fail-closed」（L0，不连库）。
 *
 * <p>口径：{@code runtime_profile(ACTIVE).source_id} 为空 ⇒ 采集**拒绝执行**并抛
 * {@link PlatformBizException#SOURCE_NOT_BOUND}，错误信息必须说清"运行环境未绑定源，先激活源再采集"。</p>
 *
 * <p><b>为什么必须 fail-closed 而不是兜底</b>（D-037 裁决 2 的同一条理由，从迁移侧延伸到运行侧）：
 * 回落到 {@code 1}/{@code mock-mall}、或给列加 {@code DEFAULT 1}，都是把"来源不明"伪装成
 * "来自 mock-mall"——断点会挂到错误的源名下，之后切源就会出现**静默少采**
 * （比重复采集更危险，见 {@code LocalFileIngestorSourceIsolationTest} 的说明）。</p>
 *
 * <p><b>另一条同样重要的断言：拒绝要发生在**任何写入之前**。</b>本类用
 * {@code verify(batchMapper, never()).insert(any())} 钉住"不留下无源归属的批次行"——
 * 否则库里会积攒 source_id 为空的新行，与 V17 回填过的 39 行历史行混在一起，
 * 事后无法区分"历史未标注"与"新代码没写"。</p>
 */
class IngestionSourceNotBoundTest {

    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final IngestionBatchFileMapper batchFileMapper = mock(IngestionBatchFileMapper.class);
    private final LocalFileIngestor ingestor = mock(LocalFileIngestor.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);
    private final EventClock clock = new EventClock(
            Clock.fixed(Instant.parse("2026-09-12T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

    private IngestionService service() {
        return new IngestionService(batchMapper, batchFileMapper, ingestor, clock,
                runtimeProfileService, sourceRegistryService, new ObjectMapper());
    }

    private Path activeProfileLanding(Path landingRoot) throws IOException {
        Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(landingRoot.resolve("events").resolve("events-001.jsonl"),
                "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(profile);
        return landingRoot;
    }

    @Test
    @DisplayName("未绑定源：抛 SOURCE_NOT_BOUND，信息写明「未绑定源，先激活源再采集」")
    void unboundSourceThrowsSourceNotBound(@TempDir Path landingRoot) throws IOException {
        activeProfileLanding(landingRoot);
        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().runOne(TraceContext.create()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .as("新错误码必须是加法式新增的 SOURCE_NOT_BOUND")
                        .isEqualTo(PlatformBizException.SOURCE_NOT_BOUND))
                .hasMessageContaining("未绑定源")
                .as("错误信息必须给出下一步动作，而不是只报一个码")
                .hasMessageContaining("激活源");
    }

    @Test
    @DisplayName("未绑定源时不写任何东西：批次 0 行、采集器 0 次调用（拒绝必须发生在写入之前）")
    void unboundSourceWritesNothing(@TempDir Path landingRoot) throws IOException {
        activeProfileLanding(landingRoot);
        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().runOne(TraceContext.create()))
                .isInstanceOf(PlatformBizException.class);

        verify(batchMapper, never()).insert(any(IngestionBatch.class));
        verifyNoInteractions(ingestor);
        assertThat(landingRoot.resolve("manifests"))
                .as("拒绝发生在写入之前：清单目录不该被创建（否则库里会积攒无源归属的批次行）")
                .doesNotExist();
    }

    @Test
    @DisplayName("错误码本身是加性的：新码逐字为 SOURCE_NOT_BOUND，既有源域四个码逐字不变")
    void errorCodeIsAdditive() {
        assertThat(PlatformBizException.SOURCE_NOT_BOUND).isEqualTo("SOURCE_NOT_BOUND");
        assertThat(PlatformBizException.SOURCE_NOT_FOUND).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(PlatformBizException.SOURCE_CODE_IMMUTABLE).isEqualTo("SOURCE_CODE_IMMUTABLE");
        assertThat(PlatformBizException.SOURCE_PROFILE_INVALID).isEqualTo("SOURCE_PROFILE_INVALID");
        assertThat(PlatformBizException.SOURCE_IN_USE).isEqualTo("SOURCE_IN_USE");
        // 既有通用码不许被顺手改掉
        assertThat(PlatformBizException.PARAM_INVALID).isEqualTo("PARAM_INVALID");
        assertThat(PlatformBizException.INTERNAL).isEqualTo("INTERNAL");
    }

    @Test
    @DisplayName("已绑定源时不抛：正常进入采集，且批次行带上该源（本用例只证明「没被误拦 + 写对源」）")
    void boundSourceProceeds(@TempDir Path landingRoot) throws IOException {
        activeProfileLanding(landingRoot);
        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.of(1L));
        when(sourceRegistryService.get(1L)).thenReturn(com.graduation.analytics.source.dto.SourceRegistryView.of(
                sourceRow(1L, "mock-mall", "1.0"), 1L));
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(200L);
            return 1;
        });
        // 采集器本身在别处取证（LocalFileIngestorSourceIsolationTest / IngestionSourceManifestTest），
        // 这里必须显式给一个非 null 结果：否则会走"采集失败"分支（记录 FAILED、files=0），
        // 而 noNewData=true 会**碰巧**成立，断言就变成了假绿。
        when(ingestor.ingestFile(any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new LocalFileIngestor.FileResult("events-001.jsonl", 0L, 16L,
                        "identity-1", 1, 0, 16L, java.util.Set.of("1.0")));

        IngestionService.RunResult result = service().runOne(TraceContext.create());

        assertThat(result.batchId()).isEqualTo(200L);
        assertThat(result.status()).as("绑定正常时应当是 SUCCESS（不是被误拦、也不是采集失败）")
                .isEqualTo(IngestionBatch.STATUS_SUCCESS);
        assertThat(result.fileCount()).as("真的采了一个文件").isEqualTo(1);
        assertThat(result.noNewData()).isFalse();

        ArgumentCaptor<IngestionBatch> captor = ArgumentCaptor.forClass(IngestionBatch.class);
        verify(batchMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceId())
                .as("已绑定时批次行必须带上该源，不能留空")
                .isEqualTo(1L);
    }

    static com.graduation.analytics.source.entity.SourceRegistry sourceRow(Long id, String code, String version) {
        com.graduation.analytics.source.entity.SourceRegistry row =
                new com.graduation.analytics.source.entity.SourceRegistry();
        row.setId(id);
        row.setSourceCode(code);
        row.setDisplayName(code);
        row.setIngestMode("FILE");
        row.setProfilePath("analytics-server/source-profiles/" + code + ".v1.json");
        row.setTimezone("Asia/Shanghai");
        row.setCurrency("CNY");
        row.setStatus("ACTIVE");
        row.setProfileVersion(version);
        return row;
    }
}
