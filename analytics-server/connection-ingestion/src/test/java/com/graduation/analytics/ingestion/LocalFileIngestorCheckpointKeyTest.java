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
    private final Map<String, FileCheckpoint> store = new HashMap<>();

    private LocalFileIngestor ingestor() {
        when(checkpointMapper.insert(any(FileCheckpoint.class))).thenAnswer(inv -> {
            FileCheckpoint c = inv.getArgument(0);
            store.put(c.getFilePath(), c);
            return 1;
        });
        // 键是什么就存什么，键不同就查不到——这正是重复采集的真实机制
        when(checkpointMapper.selectOne(any())).thenAnswer(inv -> store.get(queriedFilePath(inv.getArgument(0))));
        when(validator.check(anyString(), anyInt())).thenReturn(null);   // 本测试只关心断点键，视为整行合法
        return new LocalFileIngestor(checkpointMapper, mock(QuarantineRecordMapper.class), validator, new ObjectMapper());
    }

    /**
     * 从查询条件里取出 {@code file_path} 实参（测试替身的"表索引"）。
     *
     * <p>{@code getTargetSql()} 不是装饰：MyBatis-Plus 惰性解析参数，不先渲染 SQL 片段，
     * {@code paramNameValuePairs} 就是空的。若将来升级 MP 改变了这个实现，这里会**显式报错**，
     * 而不是静默退化成"永远查不到"（那样测试会假绿）。</p>
     */
    private static String queriedFilePath(LambdaQueryWrapper<FileCheckpoint> wrapper) {
        wrapper.getTargetSql();
        return wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String)
                .map(String::valueOf)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "查询条件里取不到 file_path：MyBatis-Plus 实现变了，请同步修这个测试替身"));
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
        LocalFileIngestor.FileResult result = ingestor.ingestFile(dottedPath, 7L, 1L,
                dir.resolve("accepted"), dir.resolve("quarantine"), TraceContext.create(), new CRC32());

        assertThat(result.collected()).isEqualTo(2);
        assertThat(store).hasSize(1);
        assertThat(store.keySet().iterator().next())
                .as("落库的键必须是规范形式（不含 \\.\\ 冗余片段）")
                .doesNotContain("." + File.separator + ".");

        // 修复前：clean 写法查不到 dotted 写法写的断点 → 判为"从未采集" → 重读 + 重复行
        assertThat(ingestor.hasConsumableData(clean, 1L))
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
        legacy.setFilePath(dotted(dir, "events", "a.jsonl").toString());
        legacy.setFileIdentity(identity);
        legacy.setNextOffset(19L);

        FileCheckpoint current = new FileCheckpoint();                      // 现写法写入的行
        current.setRuntimeProfileId(1L);
        current.setFilePath(clean.toAbsolutePath().toString());
        current.setFileIdentity(identity);
        current.setNextOffset(19L);

        when(checkpointMapper.selectList(any())).thenReturn(List.of(legacy, current));

        assertThat(ingestor().checkpointKeys(1L))
                .as("两种写法是同一个物理文件 → 集合里只有一条键")
                .containsExactly(LocalFileIngestor.checkpointKey(clean));
    }
}
