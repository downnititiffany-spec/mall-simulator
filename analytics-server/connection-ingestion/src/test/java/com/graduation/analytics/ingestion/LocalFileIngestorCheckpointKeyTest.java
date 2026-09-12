package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DEF-13 / B-11：采集断点（{@code file_checkpoint}）**物理键的规范形式**。
 *
 * <p>实测缺陷：同一物理文件在库里占了**两行**，两种写法只差一个 {@code \.\}
 * （{@code D:\...\GraduationProject\.\landing\events\x.jsonl} 与 {@code D:\...\GraduationProject\landing\events\x.jsonl}），
 * 而 {@code file_identity} 与 {@code next_offset} 完全相同：50 个文件 → 100 行，
 * {@code GET /ingestion/status} 的 {@code checkpointFiles=101} 对 {@code pendingFiles=51} 就是这么来的。</p>
 *
 * <p>后果不只是数字难看：唯一键是 {@code runtime_profile_id + file_path + file_identity}，
 * 写法一变，断点就查不到 → 采集端按"从未采集"从头读（目录里最大单文件 19.4MB）并重复写 ODS 分区
 * （最终靠 DWD 的 {@code event_id} 去重兜底，代价是白读磁盘）。</p>
 *
 * <p>根因（M1-12 定位）：键由调用方传入的 {@link Path} 拼出，而"键的规范形式"当时**没有所有者**——
 * 读写两处各写了一遍 {@code file.toAbsolutePath().toString()}（{@code LocalFileIngestor} L65 与 L297），
 * 谁都没做 normalize。M1-1 之前 landing 根来自 {@code mall.landing.path} 的裸相对路径
 * （{@code Paths.get("./landing")}，不 normalize）→ 那个生产者已随 AE-01 删除，但键的所有权没有收口，
 * 第二个调用方随时能把同一个缺陷重新打开。</p>
 *
 * <p>本测试钉住两件事：① 键的规范形式只有一处所有者（{@link LocalFileIngestor#checkpointKey}）；
 * ② 换一种写法**读得到**另一种写法写的断点（读数端同样归一，历史残留不再污染计数）。</p>
 */
class LocalFileIngestorCheckpointKeyTest {

    /**
     * MyBatis-Plus 的 {@code LambdaQueryWrapper} 靠 TableInfo 的 lambda 缓存把方法引用翻译成列名，
     * 这个缓存平时由 SqlSessionFactory 建立；L0 测试不连库，因此在这里手动建一次，
     * 让测试替身能读到查询条件里的 {@code file_path} 实参。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
    }

    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final EventContractValidator validator = mock(EventContractValidator.class);

    /** 最小内存表：键是什么就存什么，键不同就查不到——这正是重复采集的真实机制。 */
    private final Map<Key, FileCheckpoint> store = new HashMap<>();

    /** P1-05 后的断点行键：V17 把 source_id 并进了唯一键。 */
    private record Key(long runtimeProfileId, long sourceId, String filePath) {
    }

    private LocalFileIngestor ingestor;

    /**
     * 每个用例只**建一次**被测对象与其桩。
     *
     * <p>实测教训：同一个 mock 上重复 {@code when(...)} 不是"覆盖"，Mockito 先匹配先生效；
     * 更糟的是第二次 {@code when(mapper.selectOne(any()))} 时，先前装好的 {@code thenAnswer}
     * 会带着匹配器的 {@code null} 参数被执行一次——于是"包装器为 null"的显式失败会被误报成
     * 被测代码的问题。这里改成惰性单次构建，从根上不出现重复桩。</p>
     */
    private LocalFileIngestor ingestor() {
        if (ingestor != null) {
            return ingestor;
        }
        when(checkpointMapper.insert(any(FileCheckpoint.class))).thenAnswer(inv -> {
            FileCheckpoint c = inv.getArgument(0);
            store.put(new Key(c.getRuntimeProfileId(), c.getSourceId(), c.getFilePath()), c);
            return 1;
        });
        // 键是什么就存什么，键不同就查不到——这正是重复采集的真实机制
        when(checkpointMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<FileCheckpoint> wrapper = inv.getArgument(0);
            if (wrapper == null) {
                throw new IllegalStateException("selectOne 的 wrapper 是 null：参数匹配器没对上，请同步修这个测试替身");
            }
            return store.get(queriedKey(wrapper));
        });
        when(validator.check(anyString(), anyInt())).thenReturn(null);   // 本测试只关心断点键，视为整行合法
        ingestor = new LocalFileIngestor(checkpointMapper, mock(QuarantineRecordMapper.class),
                validator, new ObjectMapper());
        return ingestor;
    }

    /**
     * 给 {@code selectList} 装上"按查询条件真过滤"的桩。
     *
     * <p><b>为什么不写在 {@link #ingestor()} 里靠后写覆盖</b>：同一 mock 上对同一方法多次
     * {@code when(...)}，Mockito 是**先匹配先生效**，后写的桩不会覆盖先写的。若把通用桩塞进
     * {@code ingestor()}，用例里再写的专用桩就永远不生效——于是"源 2 名下为空"会因为通用桩
     * 直接抛异常（或返回全表）而变红/假绿，考的根本不是被测代码。</p>
     *
     * <p>{@code requireSourceInQuery} 为 true 时，查询条件里没有 {@code source_id} 就显式失败：
     * 那正是"源隔离没落地"的信号，必须报出来而不是退化成"不过滤"。</p>
     */
    private void stubSelectListFiltering(boolean requireSourceInQuery) {
        when(checkpointMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<FileCheckpoint> wrapper = inv.getArgument(0);
            if (wrapper == null) {
                throw new IllegalStateException("selectList 的 wrapper 是 null：参数匹配器没对上，请同步修这个测试替身");
            }
            if (requireSourceInQuery && queriedKey(wrapper).sourceId() == 0L) {
                throw new IllegalStateException("checkpointKeys 的查询条件里没有 source_id——源隔离就没落地");
            }
            return rowsMatching(wrapper);
        });
    }

    /** 内存表的"查询"：按 profile 必选，source/file_path 可选（与真实查询条件的可选性一致）。 */
    private List<FileCheckpoint> rowsMatching(LambdaQueryWrapper<FileCheckpoint> wrapper) {
        Key k = queriedKey(wrapper);
        return store.entrySet().stream()
                .filter(e -> e.getKey().runtimeProfileId() == k.runtimeProfileId())
                .filter(e -> k.sourceId() == 0L || e.getKey().sourceId() == k.sourceId())
                .filter(e -> k.filePath() == null || e.getKey().filePath().equals(k.filePath()))
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * 把查询条件里的 {@code (列 = ? AND 列 = ? ...)} 与参数值按**列顺序**配对。
     *
     * <p>实测（MP 3.5.7）三个视图的分工：</p>
     * <pre>
     * getTargetSql()   = (runtime_profile_id = ? AND source_id = ? AND file_path = ?)
     * getSqlSegment()  = (runtime_profile_id = #{ew.paramNameValuePairs.MPGENVAL1} AND source_id = #{...MPGENVAL2} AND ...)
     * getParamNameValuePairs() = {MPGENVAL3=..., MPGENVAL2=2, MPGENVAL1=1}   ← HashMap，顺序与列**无关**
     * </pre>
     *
     * <p>所以取 order 的唯一正确来源是 {@code sqlSegment} 里的 {@code MPGENVAL<序号>}，
     * 按序号排序后与 {@code getTargetSql()} 里的列名逐位配对。**不能**直接迭代
     * {@code paramNameValuePairs.values()}——HashMap 顺序会把 {@code source_id} 与
     * {@code file_path} 对调，查的是 (profile, null, path)，表现为随机假绿。</p>
     *
     * <p>条件数与参数数不一致时**显式失败**：那意味着 MP 换了渲染方式，
     * 静默降级会让"按源隔离"的断言失去意义。</p>
     */
    private static Map<String, Object> queriedColumns(LambdaQueryWrapper<FileCheckpoint> wrapper) {
        String sql = wrapper.getTargetSql();
        String segment = wrapper.getSqlSegment();
        java.util.List<String> columns = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([a-z_]{3,})\\s*=").matcher(sql);
        while (m.find()) {
            columns.add(m.group(1).toLowerCase());
        }
        java.util.List<String> valueKeys = new java.util.ArrayList<>();
        java.util.regex.Matcher k = java.util.regex.Pattern
                .compile("MPGENVAL(\\d+)").matcher(segment);
        while (k.find()) {
            valueKeys.add(k.group(0));
        }
        valueKeys.sort(java.util.Comparator.comparingInt(
                key -> Integer.parseInt(key.substring("MPGENVAL".length()))));
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        if (columns.size() != valueKeys.size() || columns.size() != params.size()) {
            throw new IllegalStateException("查询条件与参数个数不一致（本替身只支持等值条件）：sql=" + sql
                    + "，columns=" + columns + "，valueKeys=" + valueKeys + "，params=" + params);
        }
        Map<String, Object> byColumn = new java.util.LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            byColumn.put(columns.get(i), params.get(valueKeys.get(i)));
        }
        return byColumn;
    }

    private static Key queriedKey(LambdaQueryWrapper<FileCheckpoint> wrapper) {
        if (wrapper == null) {
            throw new IllegalStateException("wrapper 是 null：参数匹配器没对上，请同步修这个测试替身");
        }
        Map<String, Object> byColumn = queriedColumns(wrapper);
        Object profileId = byColumn.get("runtime_profile_id");
        if (profileId == null) {
            throw new IllegalStateException(
                    "查询条件里取不到 runtime_profile_id：MyBatis-Plus 实现变了，请同步修这个测试替身。"
                            + "sql=" + wrapper.getTargetSql());
        }
        Object sourceId = byColumn.get("source_id");
        return new Key((Long) profileId, sourceId == null ? 0L : (Long) sourceId,
                (String) byColumn.get("file_path"));
    }

    /** 拼一个带 {@code \.\} 冗余片段的写法（DEF-13 里历史上真实出现过的那种） */
    private static Path dotted(Path dir, String... more) {
        String[] parts = new String[more.length + 1];
        parts[0] = ".";
        System.arraycopy(more, 0, parts, 1, more.length);
        return Paths.get(dir.toString(), parts);
    }

    @Test
    @DisplayName("checkpointKey：带冗余片段（\\.\\）的写法与干净写法归一到同一个键")
    void checkpointKeyIgnoresRedundantSegments(@TempDir Path dir) {
        Path clean = dir.resolve("events").resolve("a.jsonl");
        Path dottedPath = dotted(dir, "events", "a.jsonl");
        // 前提检查：这个写法确实带冗余片段，否则本测试证明不了任何事
        assertThat(dottedPath.toString()).contains("." + File.separator);

        assertThat(LocalFileIngestor.checkpointKey(dottedPath))
                .isEqualTo(LocalFileIngestor.checkpointKey(clean))
                .isEqualTo(clean.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("DEF-13：一种写法写的断点，另一种写法必须读得到（否则从头重读并重复写 ODS）")
    void checkpointWrittenUnderOneSpellingIsVisibleToTheOther(@TempDir Path dir) throws IOException {
        Path events = Files.createDirectories(dir.resolve("events"));
        Path clean = events.resolve("a.jsonl");
        Files.writeString(clean, "{\"a\":1}\n{\"b\":2}\n", StandardCharsets.UTF_8);
        Path dottedPath = dotted(dir, "events", "a.jsonl");

        LocalFileIngestor ingestor = ingestor();
        LocalFileIngestor.FileResult result = ingestor.ingestFile(dottedPath, 7L, 1L, 1L,
                dir.resolve("accepted"), dir.resolve("quarantine"), TraceContext.create(), new CRC32());

        assertThat(result.collected()).isEqualTo(2);
        assertThat(store).hasSize(1);
        assertThat(store.keySet().iterator().next().filePath())
                .as("落库的键必须是规范形式（不含 \\.\\ 冗余片段）")
                .isEqualTo(LocalFileIngestor.checkpointKey(clean))
                .doesNotContain("." + File.separator + ".");

        // 修复前：clean 写法查不到 dotted 写法写的断点 → 判为"从未采集" → 重读 + 重复行
        assertThat(ingestor.hasConsumableData(clean, 1L, 1L))
                .as("同一物理文件已采完（断点在完整行边界）")
                .isFalse();
    }

    @Test
    @DisplayName("DEF-13：读数端把历史遗留写法归一到同一条键——同一物理文件不会算两次")
    void checkpointKeysCollapsesLegacySpelling(@TempDir Path dir) {
        Path clean = dir.resolve("events").resolve("a.jsonl");
        String identity = "1757500000000";

        FileCheckpoint legacy = new FileCheckpoint();                       // M1-1 之前写入的历史行
        legacy.setRuntimeProfileId(1L);
        legacy.setSourceId(1L);
        legacy.setFilePath(dotted(dir, "events", "a.jsonl").toString());
        legacy.setFileIdentity(identity);
        legacy.setNextOffset(19L);

        FileCheckpoint current = new FileCheckpoint();                      // 现写法写入的行
        current.setRuntimeProfileId(1L);
        current.setSourceId(1L);
        current.setFilePath(clean.toAbsolutePath().toString());
        current.setFileIdentity(identity);
        current.setNextOffset(19L);

        // 注意：同一 mock 上对同一方法只能**写一次**桩（Mockito 先匹配先生效），
        // 因此这里直接返回两行历史写法，而不是"先装通用桩再装专用桩"。
        when(checkpointMapper.selectList(any())).thenReturn(List.of(legacy, current));

        assertThat(ingestor().checkpointKeys(1L, 1L))
                .as("两种写法是同一个物理文件 → 集合里只有一条键")
                .containsExactly(LocalFileIngestor.checkpointKey(clean));
    }

    @Test
    @DisplayName("P1-05：读数端按源过滤——另一源的断点不混入本源的键集合")
    void checkpointKeysAreScopedToSource(@TempDir Path dir) {
        Path clean = dir.resolve("events").resolve("a.jsonl");

        FileCheckpoint sourceOne = new FileCheckpoint();
        sourceOne.setRuntimeProfileId(1L);
        sourceOne.setSourceId(1L);
        sourceOne.setFilePath(clean.toAbsolutePath().toString());
        sourceOne.setFileIdentity("1757500000000");
        sourceOne.setNextOffset(19L);

        // 同一 mock 上对同一方法只能写一次桩（Mockito 先匹配先生效），故这里直接用
        // "按查询条件真过滤"的桩，而不是先装通用桩再装专用桩。
        stubSelectListFiltering(true);
        store.put(new Key(1L, 1L, LocalFileIngestor.checkpointKey(clean)), sourceOne);

        assertThat(ingestor().checkpointKeys(1L, 1L))
                .as("源 1 名下确实有该断点（前提校验：否则下面的 isEmpty 证明不了任何事）")
                .containsExactly(LocalFileIngestor.checkpointKey(clean));
        assertThat(ingestor().checkpointKeys(1L, 2L))
                .as("源 2 名下没有：不得把源 1 的断点算到源 2 头上")
                .isEmpty();
    }
}
