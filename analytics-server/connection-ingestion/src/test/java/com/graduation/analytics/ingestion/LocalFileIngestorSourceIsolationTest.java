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
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-05 / D-037 裁决 1：「断点按源隔离」的行为门禁（L0，不连库）。
 *
 * <p>要关的洞（D-037 触发段，实读代码与 DDL 后确认，不是推测）：{@code uk_ckpt}
 * 是 {@code (runtime_profile_id, file_path, file_identity)}，读写两端也只按 {@code runtime_profile_id}
 * 过滤；而「当前激活源」= {@code runtime_profile(ACTIVE).source_id}（D-035 裁决 ②），**切换源改的是同一行
 * profile 的 {@code source_id}** ⇒ 同一个 {@code runtime_profile_id} 会在不同时刻服务不同源。
 * 后果是**静默少采**（比重复采集更危险）：源 B 采集后切回源 A，A 会认为该文件「已采到 offset N」而跳过数据。</p>
 *
 * <p>本测试用**内存表**替身（键是什么就存什么，键不同就查不到）钉住两件事：</p>
 * <ol>
 *   <li>同一 {@code runtime_profile_id} 下，源 1 与源 2 对**同一路径**各自持有独立 offset（互不推进）；</li>
 *   <li>源内仍然唯一：同 profile + 同源 + 同路径 + 同 identity 只推进**一行**，不会因为加了源维度就放宽去重。</li>
 * </ol>
 *
 * <p><b>取证边界</b>：本类是 L0 内存替身，证明的是「键与读写条件确实带上了源维度」这一**代码事实**；
 * 真库上的唯一键 {@code uk_ckpt_source} 与两行共存由 E3（真实例 + 真 MySQL 副本库，含 {@code information_schema}
 * 原始输出）取证，两者不可互相替代。</p>
 */
class LocalFileIngestorSourceIsolationTest {

    /** MyBatis-Plus 的 lambda→列名缓存平时由 SqlSessionFactory 建立；L0 测试手动建一次。 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
    }

    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final EventContractValidator validator = mock(EventContractValidator.class);

    /**
     * 最小内存表：**复合键**（runtime_profile_id, source_id, file_path）→ 断点行。
     * 键是什么就存什么、键不同就查不到——这正是"两源共用一套断点"的真实机制。
     */
    private final Map<CheckpointKey, FileCheckpoint> store = new HashMap<>();

    /** 单实例：桩只打一次，避免每次 ingestor() 都往 thenAnswer 上叠一层。 */
    private LocalFileIngestor ingestor;

    private record CheckpointKey(long runtimeProfileId, long sourceId, String filePath) {
    }

    private LocalFileIngestor ingestor() {
        if (ingestor == null) {
            when(validator.check(any(), anyInt())).thenReturn(null);   // 全部视为干净行
            when(checkpointMapper.insert(any(FileCheckpoint.class))).thenAnswer(inv -> {
                FileCheckpoint c = inv.getArgument(0);
                store.put(keyOf(c.getRuntimeProfileId(), c.getSourceId(), c.getFilePath()), c);
                return 1;
            });
            when(checkpointMapper.selectOne(any())).thenAnswer(inv -> {
                Filter f = queriedFilter(inv.getArgument(0));
                return store.get(new CheckpointKey(f.profileId(), f.sourceId(), f.filePath()));
            });
            when(checkpointMapper.selectList(any())).thenAnswer(inv -> {
                if (inv.getArgument(0) == null) {
                    // any() 对泛型参数返回 null；此时"按源过滤"必然失效。显式失败而不是返回全表：
                    // 返回全表会让"源 2 名下为空"这种断言因为没有测试数据而碰巧通过（假绿）。
                    throw new IllegalStateException("selectList 的 wrapper 是 null：参数匹配器没对上，请同步修这个测试替身");
                }
                return rowsMatching(inv.getArgument(0));
            });
            ingestor = new LocalFileIngestor(checkpointMapper, mock(QuarantineRecordMapper.class),
                    validator, new ObjectMapper(), mock(SourceMapper.class));
        }
        return ingestor;
    }

    private static CheckpointKey keyOf(Long runtimeProfileId, Long sourceId, String filePath) {
        return new CheckpointKey(runtimeProfileId, sourceId, filePath);
    }

    private record Filter(long profileId, Long sourceId, String filePath) {
    }

    /**
     * 从查询条件里取出等值条件（测试替身的"表索引"）。
     *
     * <p>{@code getTargetSql()} 返回的是带 {@code ?} 占位符的片段（实测：
     * {@code (runtime_profile_id = ? AND source_id = ? AND file_path = ?)}），
     * 而 {@code getParamNameValuePairs()} 在**未**先渲染时是空的；但两者的顺序严格对应
     * （{@code MPGENVAL1/2/3} 按生成顺序编号），因此这里按**列名出现顺序**与**参数值顺序**配对。</p>
     *
     * <p><b>为什么不按"第几个 Long/String"猜</b>：{@code paramNameValuePairs} 是 {@code HashMap}，
     * 直接迭代 {@code values()} 的顺序与占位符顺序无关。早先的写法在三个条件并存时会把
     * {@code source_id} 与 {@code file_path} 对调，于是查的是 (profile, null, path)，
     * 表现为随机假绿/假红。这里用 SQL 文本里的列名顺序当索引，列名可选（取不到就不放进 map）。</p>
     */
    private static Filter queriedFilter(LambdaQueryWrapper<FileCheckpoint> wrapper) {
        Map<String, Object> byColumn = queriedColumns(wrapper);
        Long profileId = (Long) byColumn.get("runtime_profile_id");
        if (profileId == null) {
            throw new IllegalStateException(
                    "查询条件里取不到 runtime_profile_id：MyBatis-Plus 实现变了，请同步修这个测试替身。"
                            + "sql=" + wrapper.getTargetSql());
        }
        return new Filter(profileId, (Long) byColumn.get("source_id"), (String) byColumn.get("file_path"));
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
     * <p>取 order 的唯一正确来源是 {@code sqlSegment} 里的 {@code MPGENVAL<序号>}，按序号排序后与
     * {@code getTargetSql()} 里的列名逐位配对。**不能**直接迭代 {@code paramNameValuePairs.values()}——
     * HashMap 顺序会把 {@code source_id} 与 {@code file_path} 对调，于是查的是 (profile, null, path)，
     * 表现为随机假绿/假红（本类早先的写法就踩了这个坑，红/绿都不可信）。</p>
     */
    private static Map<String, Object> queriedColumns(LambdaQueryWrapper<FileCheckpoint> wrapper) {
        if (wrapper == null) {
            throw new IllegalStateException("wrapper 是 null：参数匹配器没对上，请同步修这个测试替身");
        }
        String sql = wrapper.getTargetSql();
        String segment = wrapper.getSqlSegment();
        List<String> columns = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([a-z_]{3,})\\s*=").matcher(sql);
        while (m.find()) {
            columns.add(m.group(1).toLowerCase());
        }
        List<String> valueKeys = new java.util.ArrayList<>();
        java.util.regex.Matcher k = java.util.regex.Pattern.compile("MPGENVAL(\\d+)").matcher(segment);
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
        Map<String, Object> byColumn = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            byColumn.put(columns.get(i), params.get(valueKeys.get(i)));
        }
        return byColumn;
    }

    private static final java.util.regex.Pattern EQUALITY =
            java.util.regex.Pattern.compile("\\b([a-z_]{3,})\\s*=\\s*\\?");

    /** 内存表的"查询"：按 profile 必选，source/file_path 可选（与真实查询条件的可选性一致）。 */
    private List<FileCheckpoint> rowsMatching(LambdaQueryWrapper<FileCheckpoint> wrapper) {
        Filter f = queriedFilter(wrapper);        return store.entrySet().stream()
                .filter(e -> e.getKey().runtimeProfileId() == f.profileId())
                .filter(e -> f.sourceId() == null || e.getKey().sourceId() == f.sourceId())
                .filter(e -> f.filePath() == null || e.getKey().filePath().equals(f.filePath()))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static Path eventsFile(Path dir, String name, String content) throws IOException {
        Path events = Files.createDirectories(dir.resolve("events"));
        Path file = events.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private LocalFileIngestor.FileResult ingest(LocalFileIngestor ingestor, Path file, long sourceId, Path dir) {
        return ingestor.ingestFile(file, 7L, 1L, sourceId,
                dir.resolve("accepted"), dir.resolve("quarantine"), TraceContext.create(), new CRC32(),
                SourceMapping.legacy());
    }

    // ---------- ① 两源互不推进（D-037 要关的洞） ----------

    @Test
    @DisplayName("同一 runtime_profile_id 下：源 2 采集不推进源 1 的断点（同路径两套 offset）")
    void sourceTwoDoesNotAdvanceSourceOneCheckpoint(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n{\"b\":2}\n");
        LocalFileIngestor ingestor = ingestor();

        LocalFileIngestor.FileResult first = ingest(ingestor, file, 1L, dir);   // 源 1 采完
        assertThat(first.collected()).isEqualTo(2);
        assertThat(first.endOffset()).isEqualTo(Files.size(file));

        // 切到源 2：同一路径、同一行 identity，但断点必须自成一套 → 从头读（不是"已采完"）
        assertThat(ingestor.hasConsumableData(file, 1L, 2L))
                .as("源 2 从未采集过该文件：源 1 的断点不得被源 2 继承（继承就会静默少采）")
                .isTrue();

        LocalFileIngestor.FileResult second = ingest(ingestor, file, 2L, dir);
        assertThat(second.startOffset()).as("源 2 必须从 0 开始读，而不是从源 1 的 offset 续读").isZero();
        assertThat(second.collected()).isEqualTo(2);

        assertThat(store).as("两源各一行，共存于同一 (profile, path)").hasSize(2);
        assertThat(offsetOf(1L, 1L, file)).isEqualTo(Files.size(file));
        assertThat(offsetOf(1L, 2L, file)).isEqualTo(Files.size(file));
    }

    @Test
    @DisplayName("切回源 1：源 1 的断点仍是自己的（没有被源 2 的采集改写）")
    void switchingBackKeepsEachSourcesOwnOffset(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n");
        LocalFileIngestor ingestor = ingestor();

        ingest(ingestor, file, 1L, dir);                                        // 源 1：0 → 8
        long sourceOneOffset = offsetOf(1L, 1L, file);

        Files.writeString(file, "{\"b\":2}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ingest(ingestor, file, 2L, dir);                                        // 源 2：0 → 16

        assertThat(offsetOf(1L, 1L, file))
                .as("源 1 的断点必须原封不动（还是 8，不是 16）")
                .isEqualTo(sourceOneOffset)
                .isEqualTo("{\"a\":1}\n".length());
        assertThat(offsetOf(1L, 2L, file)).isEqualTo(Files.size(file));

        // 切回源 1：源 1 自己的断点仍是 8，故它**仍有**未采的那一行（这正是"隔离"的含义——
        // 源 2 采过不代表源 1 采过；若两源共用断点，这里会错报"已采完"而静默漏掉一行）
        assertThat(ingestor.hasConsumableData(file, 1L, 1L))
                .as("源 1 只采到第 1 行：第 2 行对它仍是新数据（共用断点时会误报 false）")
                .isTrue();
        assertThat(ingestor.hasConsumableData(file, 1L, 2L))
                .as("源 2 已采完全部 16 字节 → 无新数据")
                .isFalse();

        // 再采一轮源 1：只应读到它自己缺的那一行，且不重读第 1 行
        LocalFileIngestor.FileResult again = ingest(ingestor, file, 1L, dir);
        assertThat(again.startOffset()).as("源 1 从自己的断点 8 续读").isEqualTo(8L);
        assertThat(again.collected()).as("只补第 2 行，不重复第 1 行").isEqualTo(1);
        assertThat(offsetOf(1L, 1L, file)).isEqualTo(Files.size(file));
    }

    @Test
    @DisplayName("同一物理文件在两个源各自的断点行里是**两行**（键含源维度，不是靠应用层过滤）")
    void checkpointRowsAreDistinctPerSource(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n");
        LocalFileIngestor ingestor = ingestor();

        ingest(ingestor, file, 1L, dir);
        ingest(ingestor, file, 2L, dir);

        assertThat(store.keySet())
                .as("键 = (runtime_profile_id, source_id, file_path)")
                .containsExactlyInAnyOrder(
                        new CheckpointKey(1L, 1L, LocalFileIngestor.checkpointKey(file)),
                        new CheckpointKey(1L, 2L, LocalFileIngestor.checkpointKey(file)));
        assertThat(store.values()).extracting(FileCheckpoint::getSourceId).containsExactlyInAnyOrder(1L, 2L);
    }

    // ---------- ② 源内仍然唯一（加源维度不得放宽去重） ----------

    @Test
    @DisplayName("同源同路径同 identity 重复采集：只推进同一行，不新增行（去重未被放宽）")
    void sameSourceStaysUnique(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n");
        LocalFileIngestor ingestor = ingestor();

        ingest(ingestor, file, 1L, dir);
        Files.writeString(file, "{\"b\":2}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ingest(ingestor, file, 1L, dir);   // 同一源、同一路径、同一 identity（append 不改创建时间）

        assertThat(store).as("同一源内仍然一行").hasSize(1);
        assertThat(offsetOf(1L, 1L, file)).isEqualTo(Files.size(file));
    }

    @Test
    @DisplayName("不同 runtime_profile_id 之间同样隔离（源维度之外，profile 维度未被改坏）")
    void differentProfilesAlsoStayIsolated(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n");
        LocalFileIngestor ingestor = ingestor();

        ingest(ingestor, file, 1L, dir);
        ingestor.ingestFile(file, 7L, 2L, 1L, dir.resolve("accepted"), dir.resolve("quarantine"),
                TraceContext.create(), new CRC32(), SourceMapping.legacy());

        assertThat(store).hasSize(2);
        assertThat(store.keySet()).containsExactlyInAnyOrder(
                new CheckpointKey(1L, 1L, LocalFileIngestor.checkpointKey(file)),
                new CheckpointKey(2L, 1L, LocalFileIngestor.checkpointKey(file)));
    }

    // ---------- ③ 读数端（checkpointKeys）也按源过滤 ----------

    @Test
    @DisplayName("写入侧：源 1 采集后，源 2 名下不产生断点行（键含源维度才是隔离，不是应用层过滤）")
    void sourceOneRunCreatesNoSourceTwoRow(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n");
        ingest(ingestor(), file, 1L, dir);

        // 断言的是**内存表里存下来的行**：键是什么就存什么。
        // 桩不替 MyBatis-Plus 实现条件过滤（那等于测试自己重写一遍 SQL），
        // 读取侧条件里确实带 source_id 这件事由 queriedFilter 在使用处强制校验。
        assertThat(store.keySet())
                .as("只有源 1 的行")
                .containsExactlyInAnyOrder(new CheckpointKey(1L, 1L, LocalFileIngestor.checkpointKey(file)));
        assertThat(store.keySet())
                .as("源 2 名下不应存在该文件的断点行")
                .doesNotContain(new CheckpointKey(1L, 2L, LocalFileIngestor.checkpointKey(file)));
    }

    @Test
    @DisplayName("checkpointKeys(profile, source)：只列出该源的断点，另一源的同一文件不得混入")
    void checkpointKeysAreScopedToSource(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n");
        LocalFileIngestor ingestor = ingestor();
        ingest(ingestor, file, 1L, dir);
        ingest(ingestor, file, 2L, dir);

        assertThat(ingestor.checkpointKeys(1L, 1L))
                .as("源 1 名下有该文件的断点")
                .containsExactly(LocalFileIngestor.checkpointKey(file));
        assertThat(ingestor.checkpointKeys(1L, 2L))
                .as("源 2 名下也有——两源各一套（前提校验：否则下一个断言证明不了任何事）")
                .containsExactly(LocalFileIngestor.checkpointKey(file));
        assertThat(ingestor.checkpointKeys(1L, 3L))
                .as("源 3 从未采集：不得把源 1/2 的断点算到它头上（否则状态总览虚报 checkpointFiles）")
                .isEmpty();
    }

    // ---------- ④ 残行语义不因加源而改变（回归） ----------

    @Test
    @DisplayName("残行语义两源一致：尾部无换行的残行在任一源下都不推进偏移")
    void partialTailSemanticsHoldPerSource(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n{\"b\":2}");
        LocalFileIngestor ingestor = ingestor();

        assertThat(ingest(ingestor, file, 1L, dir).endOffset()).isEqualTo("{\"a\":1}\n".length());
        assertThat(ingest(ingestor, file, 2L, dir).endOffset()).isEqualTo("{\"a\":1}\n".length());
        assertThat(offsetOf(1L, 1L, file)).isEqualTo("{\"a\":1}\n".length());
        assertThat(offsetOf(1L, 2L, file)).isEqualTo("{\"a\":1}\n".length());
    }

    /**
     * 「源不允许缺省」这一条**由签名保证，而不是由运行时兜底**：{@code sourceId} 是原始 {@code long}，
     * 传不出 null，也不存在 {@code ingestFile(file, batchId, profileId, dirs...)} 这种旧重载
     * （若有人为了兼容把它加回来，本用例立刻失败——那才是真正的风险点）。
     */
    @Test
    @DisplayName("源不许缺省：不存在「不带 sourceId」的 ingestFile / hasConsumableData / checkpointKeys 重载")
    void noSourceLessOverloadExists() {
        List<String> ingestSignatures = Arrays.stream(LocalFileIngestor.class.getMethods())
                .filter(m -> m.getName().equals("ingestFile"))
                .map(m -> m.getName() + Arrays.toString(m.getParameterTypes()))
                .toList();
        assertThat(ingestSignatures)
                .as("只允许一个 ingestFile，且参数里有第二个 long（sourceId）")
                .hasSize(1);
        assertThat(ingestSignatures.get(0)).contains("long, long, long");

        assertThat(Arrays.stream(LocalFileIngestor.class.getMethods())
                .filter(m -> m.getName().equals("hasConsumableData"))
                .map(m -> m.getParameterCount())
                .toList())
                .as("hasConsumableData 只允许 (Path, long, long) 一种形态")
                .containsExactly(3);
        assertThat(Arrays.stream(LocalFileIngestor.class.getMethods())
                .filter(m -> m.getName().equals("checkpointKeys"))
                .map(m -> m.getParameterCount())
                .toList())
                .as("checkpointKeys 只允许 (long, long) 一种形态")
                .containsExactly(2);
    }

    private long offsetOf(long runtimeProfileId, long sourceId, Path file) {
        FileCheckpoint ckpt = store.get(new CheckpointKey(runtimeProfileId, sourceId,
                LocalFileIngestor.checkpointKey(file)));
        assertThat(ckpt).as("断点行不存在：(profile=%d, source=%d, %s)", runtimeProfileId, sourceId, file).isNotNull();
        return ckpt.getNextOffset();
    }

    /** 编译期护栏：FileResult 的字段名是本类断言的基础（改名会在此处变红，而不是静默换语义）。 */
    @Test
    @DisplayName("FileResult 契约未变：collected/startOffset/endOffset 仍是记录组件")
    void fileResultShapeUnchanged(@TempDir Path dir) throws IOException {
        Path file = eventsFile(dir, "a.jsonl", "{\"a\":1}\n");
        LocalFileIngestor.FileResult r = ingest(ingestor(), file, 1L, dir);

        // 本类注入的校验器桩把每行都判为干净行，而 schemaVersionOf 在解析不出 schema_version 时
        // 如实返回 "unknown"（这一轮测试数据里没有该字段）——所以这里就是 "unknown"，
        // 不是写错：FileResult 的字段顺序/名称才是本用例要钉的东西。
        assertThat(new LocalFileIngestor.FileResult(r.filePath(), r.startOffset(), r.endOffset(),
                r.fileIdentity(), r.collected(), r.quarantined(), r.acceptedBytes(), Set.of("unknown")))
                .isEqualTo(r);
    }
}
