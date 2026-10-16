package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.mapping.MappingHash;
import com.graduation.analytics.mapping.activation.ActiveMappingPointer;
import com.graduation.analytics.mapping.activation.JdbcActiveMappingPointerStore;
import com.graduation.analytics.mapping.activation.MappingActivationOutcome;
import com.graduation.analytics.mapping.activation.MappingActivationService;
import com.graduation.analytics.mapping.activation.entity.SourceMappingActiveEntity;
import com.graduation.analytics.mapping.activation.mapper.SourceMappingActiveMapper;
import com.graduation.analytics.mapping.dryrun.InMemoryMappingDryRunReportRepository;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReport;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReportRepository;
import com.graduation.analytics.mapping.dryrun.MappingDryRunService;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.RuntimeProfileServiceImpl;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.source.SourceProfileValidator;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.SourceRegistryServiceImpl;
import com.graduation.analytics.source.entity.SourceRegistry;
import com.graduation.analytics.source.mapper.ActiveSourceBindingMapper;
import com.graduation.analytics.source.mapper.SourceRegistryMapper;
import com.graduation.analytics.testsupport.RepoRoot;
import com.graduation.analytics.testsupport.TestIsolationGuard;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-N1A 交付物 1：两源采集真链的测试端激活与环境自动化装配器。
 *
 * <p>按 R-N1-4 硬约束：
 * <ol>
 *   <li>目标库必须是按 runId 派生的真实 MySQL 3307 隔离元数据库（{@code analytics_meta}）；</li>
 *   <li>连接前严格经过 {@link TestIsolationGuard#verifyBeforeWrite}；</li>
 *   <li>通过 Flyway 加载项目自身元数据迁移（{@code classpath:db/meta} / V1–V34）；</li>
 *   <li>无 Mockito 替身：{@link SourceMapper}、{@link MappingActivationService}、
 *       {@link JdbcActiveMappingPointerStore}、{@link FileCheckpointMapper}、
 *       {@link IngestionBatchMapper}、{@link IngestionBatchFileMapper}、
 *       {@link QuarantineRecordMapper} 全部为直连真实 DB 的生产实现；</li>
 *   <li>提供源 B 的真实注册、真实 dry-run、真实 activation、真实指针落库以及负向控制。</li>
 * </ol>
 * </p>
 */
public class RealIngestionTestSupport {

    public static final String SOURCE_B_CODE = "fixture-shop-b";
    public static final String SOURCE_B_PROFILE_PATH = "analytics-server/source-profiles/fixture-shop-b.v2.json";
    public static final String SOURCE_B_PREFIX = "fxsb";
    public static final String SOURCE_B_SAMPLE_REF = "fixtures/source-b/fixture-shop-b/normal.jsonl";

    public static final String SOURCE_A_CODE = "mock-mall";
    public static final String SOURCE_A_PROFILE_PATH = "analytics-server/source-profiles/mock-mall.v1.json";
    public static final String SOURCE_A_PREFIX = "dw";

    public static final String CONTRACT_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";

    private TestIsolationGuard.TestRunContext context;
    private DataSource metaDs;
    private JdbcTemplate metaJdbc;
    private ObjectMapper objectMapper;
    private EventClock eventClock;

    private SqlSessionFactory sessionFactory;
    private SqlSessionTemplate sqlSessionTemplate;

    private SourceRegistryMapper sourceRegistryMapper;
    private ActiveSourceBindingMapper activeSourceBindingMapper;
    private SourceMappingActiveMapper sourceMappingActiveMapper;
    private RuntimeProfileMapper runtimeProfileMapper;
    private FileCheckpointMapper fileCheckpointMapper;
    private IngestionBatchMapper ingestionBatchMapper;
    private IngestionBatchFileMapper ingestionBatchFileMapper;
    private QuarantineRecordMapper quarantineRecordMapper;

    private SourceRegistryService sourceRegistryService;
    private RuntimeProfileService runtimeProfileService;
    private JdbcActiveMappingPointerStore activeMappingPointerStore;
    private MappingDryRunReportRepository dryRunReportRepo;
    private MappingDryRunService dryRunService;
    private MappingActivationService activationService;
    private SourceMapper sourceMapper;
    private LocalFileIngestor fileIngestor;
    private IngestionService ingestionService;

    private SourceRegistry sourceA;
    private SourceRegistry sourceB;
    private RuntimeProfile activeProfile;

    /**
     * 针对指定 landingRoot 初始化真实隔离元数据库与完整采集服务拓扑。
     */
    public void setUp(Path landingRoot) {
        this.context = TestIsolationGuard.loadContext();
        this.objectMapper = new ObjectMapper();
        this.eventClock = new EventClock(Clock.system(ZoneId.of("Asia/Shanghai")));

        // 1. 数据源与写前隔离门禁核对
        this.metaDs = createDataSource(context.metaDb(), "meta.username", "meta.password");
        TestIsolationGuard.LiveFacts facts = TestIsolationGuard.verifyBeforeWrite(context, metaDs, context.metaDb());
        System.out.println("[RealIngestionTestSupport] 隔离门禁放行: " + facts.redactedSummary());

        // 2. 真实元数据 Flyway 迁移（V1 - V34）
        Path metaMigrationPath = RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");
        Flyway.configure()
                .dataSource(metaDs)
                .locations("filesystem:" + metaMigrationPath.toString())
                .load()
                .migrate();

        this.metaJdbc = new JdbcTemplate(metaDs);

        // 3. MyBatis-Plus Mapper 与 SqlSession 装配
        initMappers();

        // 4. 装配真实领域服务
        initServices();

        // 5. 确保源 A / 源 B 登记行就绪
        ensureSourceARegistered();
        ensureSourceBRegistered();

        // 6. 确保唯一 ACTIVE runtime_profile 行就绪，指向本次 temp landing
        ensureActiveRuntimeProfile(landingRoot, sourceB.getId());
    }

    private void initMappers() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        // 注册全部涉及真实落库的 mapper
        configuration.addMapper(SourceRegistryMapper.class);
        configuration.addMapper(ActiveSourceBindingMapper.class);
        configuration.addMapper(SourceMappingActiveMapper.class);
        configuration.addMapper(RuntimeProfileMapper.class);
        configuration.addMapper(FileCheckpointMapper.class);
        configuration.addMapper(IngestionBatchMapper.class);
        configuration.addMapper(IngestionBatchFileMapper.class);
        configuration.addMapper(QuarantineRecordMapper.class);

        // 初始化 TableInfo 缓存（支持 lambda 列名提取）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entityClass : List.of(
                SourceRegistry.class,
                RuntimeProfile.class,
                SourceMappingActiveEntity.class,
                FileCheckpoint.class,
                IngestionBatch.class,
                IngestionBatchFile.class,
                QuarantineRecord.class)) {
            TableInfoHelper.initTableInfo(assistant, entityClass);
        }

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(metaDs);
        factoryBean.setConfiguration(configuration);
        try {
            this.sessionFactory = factoryBean.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("构建 SqlSessionFactory 失败: " + e.getMessage(), e);
        }
        this.sqlSessionTemplate = new SqlSessionTemplate(sessionFactory);

        this.sourceRegistryMapper = sqlSessionTemplate.getMapper(SourceRegistryMapper.class);
        this.activeSourceBindingMapper = sqlSessionTemplate.getMapper(ActiveSourceBindingMapper.class);
        this.sourceMappingActiveMapper = sqlSessionTemplate.getMapper(SourceMappingActiveMapper.class);
        this.runtimeProfileMapper = sqlSessionTemplate.getMapper(RuntimeProfileMapper.class);
        this.fileCheckpointMapper = sqlSessionTemplate.getMapper(FileCheckpointMapper.class);
        this.ingestionBatchMapper = sqlSessionTemplate.getMapper(IngestionBatchMapper.class);
        this.ingestionBatchFileMapper = sqlSessionTemplate.getMapper(IngestionBatchFileMapper.class);
        this.quarantineRecordMapper = sqlSessionTemplate.getMapper(QuarantineRecordMapper.class);
    }

    private void initServices() {
        Path repoRoot = RepoRoot.path();
        SourceProfileValidator validator = new SourceProfileValidator(repoRoot.toString(), objectMapper);
        this.sourceRegistryService = new SourceRegistryServiceImpl(sourceRegistryMapper, activeSourceBindingMapper, validator);
        this.runtimeProfileService = new RuntimeProfileServiceImpl(runtimeProfileMapper, null, null);

        this.activeMappingPointerStore = new JdbcActiveMappingPointerStore(sourceMappingActiveMapper);

        this.dryRunReportRepo = new InMemoryMappingDryRunReportRepository();
        this.dryRunService = new MappingDryRunService(
                dryRunReportRepo,
                eventClock,
                repoRoot.resolve(CONTRACT_PATH).toString(),
                repoRoot.toString()
        );

        this.activationService = new MappingActivationService(
                dryRunReportRepo,
                sourceRegistryService,
                activeMappingPointerStore,
                eventClock,
                repoRoot.toString(),
                repoRoot.resolve(CONTRACT_PATH).toString()
        );

        this.sourceMapper = new SourceMapper(
                repoRoot.toString(),
                repoRoot.resolve(CONTRACT_PATH).toString(),
                eventClock,
                objectMapper,
                activeMappingPointerStore
        );

        this.fileIngestor = new LocalFileIngestor(
                fileCheckpointMapper,
                quarantineRecordMapper,
                new EventContractValidator(objectMapper),
                objectMapper,
                sourceMapper
        );

        this.ingestionService = new IngestionService(
                ingestionBatchMapper,
                ingestionBatchFileMapper,
                fileIngestor,
                eventClock,
                runtimeProfileService,
                sourceRegistryService,
                objectMapper,
                sourceMapper
        );
    }

    public void ensureSourceBRegistered() {
        SourceRegistry existing = sourceRegistryMapper.selectOne(
                new LambdaQueryWrapper<SourceRegistry>().eq(SourceRegistry::getSourceCode, SOURCE_B_CODE));
        if (existing == null) {
            SourceRegistry row = new SourceRegistry();
            row.setSourceCode(SOURCE_B_CODE);
            row.setDisplayName("Fixture Shop B");
            row.setIngestMode(SourceRegistry.INGEST_MODE_FILE);
            row.setProfilePath(SOURCE_B_PROFILE_PATH);
            row.setTimezone("Asia/Shanghai");
            row.setCurrency("CNY");
            row.setStatus(SourceRegistry.STATUS_ACTIVE);
            row.setProfileVersion("2.0");
            row.setWarehousePrefix(SOURCE_B_PREFIX);
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            sourceRegistryMapper.insert(row);
            this.sourceB = row;
        } else {
            existing.setProfilePath(SOURCE_B_PROFILE_PATH);
            existing.setWarehousePrefix(SOURCE_B_PREFIX);
            existing.setStatus(SourceRegistry.STATUS_ACTIVE);
            existing.setProfileVersion("2.0");
            existing.setUpdatedAt(LocalDateTime.now());
            sourceRegistryMapper.updateById(existing);
            this.sourceB = existing;
        }
    }

    public void ensureSourceARegistered() {
        SourceRegistry existing = sourceRegistryMapper.selectOne(
                new LambdaQueryWrapper<SourceRegistry>().eq(SourceRegistry::getSourceCode, SOURCE_A_CODE));
        if (existing == null) {
            SourceRegistry row = new SourceRegistry();
            row.setSourceCode(SOURCE_A_CODE);
            row.setDisplayName("参考商城（源 A）");
            row.setIngestMode(SourceRegistry.INGEST_MODE_FILE);
            row.setProfilePath(SOURCE_A_PROFILE_PATH);
            row.setTimezone("Asia/Shanghai");
            row.setCurrency("CNY");
            row.setStatus(SourceRegistry.STATUS_ACTIVE);
            row.setProfileVersion("1.0");
            row.setWarehousePrefix(SOURCE_A_PREFIX);
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            sourceRegistryMapper.insert(row);
            this.sourceA = row;
        } else {
            if (existing.getWarehousePrefix() == null || existing.getWarehousePrefix().isBlank()) {
                existing.setWarehousePrefix(SOURCE_A_PREFIX);
            }
            existing.setStatus(SourceRegistry.STATUS_ACTIVE);
            existing.setProfilePath(SOURCE_A_PROFILE_PATH);
            existing.setProfileVersion("1.0");
            existing.setUpdatedAt(LocalDateTime.now());
            sourceRegistryMapper.updateById(existing);
            this.sourceA = existing;
        }
    }

    public void ensureActiveRuntimeProfile(Path landingRoot, Long boundSourceId) {
        List<RuntimeProfile> profiles = runtimeProfileMapper.selectList(null);
        if (profiles.isEmpty()) {
            activeProfile = new RuntimeProfile();
            activeProfile.setProfileCode("v25it-profile-" + context.testRunId());
            activeProfile.setProfileName("IT Profile " + context.testRunId());
            activeProfile.setType(RuntimeProfile.TYPE_LOCAL);
            activeProfile.setStatus(RuntimeProfile.STATUS_ACTIVE);
            activeProfile.setLandingUri(landingRoot.toUri().toString());
            activeProfile.setLandingLayout("ROLLING_LOG");
            activeProfile.setSourceId(boundSourceId);
            activeProfile.setVersion(1);
            activeProfile.setCreatedAt(LocalDateTime.now());
            activeProfile.setUpdatedAt(LocalDateTime.now());
            runtimeProfileMapper.insert(activeProfile);
        } else {
            activeProfile = profiles.get(0);
            activeProfile.setStatus(RuntimeProfile.STATUS_ACTIVE);
            activeProfile.setLandingUri(landingRoot.toUri().toString());
            activeProfile.setLandingLayout("ROLLING_LOG");
            activeProfile.setSourceId(boundSourceId);
            activeProfile.setUpdatedAt(LocalDateTime.now());
            runtimeProfileMapper.updateById(activeProfile);
            for (int i = 1; i < profiles.size(); i++) {
                RuntimeProfile other = profiles.get(i);
                if (RuntimeProfile.STATUS_ACTIVE.equals(other.getStatus())) {
                    other.setStatus(RuntimeProfile.STATUS_DISABLED);
                    runtimeProfileMapper.updateById(other);
                }
            }
        }
        if (boundSourceId != null) {
            activeSourceBindingMapper.bind(activeProfile.getId(), boundSourceId);
        }
    }

    public void switchToSourceB(Path landingRoot) {
        ensureActiveRuntimeProfile(landingRoot, sourceB.getId());
    }

    public void switchToSourceA(Path landingRoot) {
        ensureActiveRuntimeProfile(landingRoot, sourceA.getId());
    }

    /**
     * Deliverable 1.2: 执行真实激活流程。
     * dryRunService.run(...) -> activationService.activate(...)
     * 校验 pointer 真实落入 source_mapping_active 表。
     */
    public MappingActivationOutcome activateSourceB() throws IOException {
        String profileText = Files.readString(RepoRoot.path(SOURCE_B_PROFILE_PATH), StandardCharsets.UTF_8);
        String sourceIdToken = String.valueOf(sourceB.getId());

        MappingDryRunReport report = dryRunService.run(sourceIdToken, profileText, SOURCE_B_SAMPLE_REF, 100);
        assertThat(report.profileAccepted()).as("Dry-run 报告必须成功通过").isTrue();
        assertThat(report.activationEligible()).as("Dry-run 报告必须满足激活准入条件").isTrue();

        MappingActivationOutcome outcome = activationService.activate(
                sourceIdToken,
                report.reportId(),
                report.profileChecksum(),
                "test-operator"
        );
        assertThat(outcome).as("激活结果不能为 null").isNotNull();

        Optional<ActiveMappingPointer> pointerOpt = activeMappingPointerStore.find(sourceB.getId());
        assertThat(pointerOpt).as("source_mapping_active 表必须存在激活行").isPresent();
        assertThat(pointerOpt.get().profileChecksum()).isEqualTo(report.profileChecksum());
        assertThat(pointerOpt.get().profilePath()).isEqualTo(SOURCE_B_PROFILE_PATH);
        assertThat(pointerOpt.get().profileVersion()).isEqualTo("2.0");

        return outcome;
    }

    /**
     * 清理指定源在 source_mapping_active 表中的激活指针（供负向测试使用）。
     */
    public void clearActiveMapping(long sourceId) {
        metaJdbc.update("DELETE FROM source_mapping_active WHERE source_id = ?", sourceId);
    }

    /**
     * Deliverable 1.3: 负向控制断言。
     * 指针缺失时，对源 B 发起的一轮采集被 MAPPING_NOT_ACTIVE 拒绝，
     * 且在任何写入之前拒绝：批次行、accepted/quarantine 落地文件、检查点都不产生。
     */
    public void assertNegativeControlRejection(Path landingRoot) {
        clearActiveMapping(sourceB.getId());
        switchToSourceB(landingRoot);

        long batchCountBefore = ingestionBatchMapper.selectCount(
                new LambdaQueryWrapper<IngestionBatch>().eq(IngestionBatch::getSourceId, sourceB.getId()));
        long checkpointCountBefore = fileCheckpointMapper.selectCount(
                new LambdaQueryWrapper<FileCheckpoint>().eq(FileCheckpoint::getSourceId, sourceB.getId()));
        long quarantineCountBefore = quarantineRecordMapper.selectCount(null);

        assertThatThrownBy(() -> ingestionService.runOne(TraceContext.create()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.MAPPING_NOT_ACTIVE));

        long batchCountAfter = ingestionBatchMapper.selectCount(
                new LambdaQueryWrapper<IngestionBatch>().eq(IngestionBatch::getSourceId, sourceB.getId()));
        long checkpointCountAfter = fileCheckpointMapper.selectCount(
                new LambdaQueryWrapper<FileCheckpoint>().eq(FileCheckpoint::getSourceId, sourceB.getId()));
        long quarantineCountAfter = quarantineRecordMapper.selectCount(null);

        assertThat(batchCountAfter).as("负向控制下批次表不得产生新行").isEqualTo(batchCountBefore);
        assertThat(checkpointCountAfter).as("负向控制下检查点表不得产生新行").isEqualTo(checkpointCountBefore);
        assertThat(quarantineCountAfter).as("负向控制下隔离表不得产生新行").isEqualTo(quarantineCountBefore);

        assertThat(Files.exists(landingRoot.resolve("accepted")))
                .as("负向控制下 accepted 目录不得产生").isFalse();
        assertThat(Files.exists(landingRoot.resolve("quarantine")))
                .as("负向控制下 quarantine 目录不得产生").isFalse();
        assertThat(Files.exists(landingRoot.resolve("manifests")))
                .as("负向控制下 manifests 目录不得产生").isFalse();
    }

    public void cleanup() {
        if (activeProfile != null && activeProfile.getId() != null) {
            long profileId = activeProfile.getId();
            List<IngestionBatch> batches = ingestionBatchMapper.selectList(
                    new LambdaQueryWrapper<IngestionBatch>().eq(IngestionBatch::getRuntimeProfileId, profileId));
            for (IngestionBatch b : batches) {
                ingestionBatchFileMapper.delete(
                        new LambdaQueryWrapper<IngestionBatchFile>().eq(IngestionBatchFile::getBatchId, b.getId()));
                quarantineRecordMapper.delete(
                        new LambdaQueryWrapper<QuarantineRecord>().eq(QuarantineRecord::getBatchId, b.getId()));
                ingestionBatchMapper.deleteById(b.getId());
            }
            fileCheckpointMapper.delete(
                    new LambdaQueryWrapper<FileCheckpoint>().eq(FileCheckpoint::getRuntimeProfileId, profileId));
        }
    }

    private static DataSource createDataSource(String database, String userProperty, String passwordProperty) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + TestIsolationGuard.requiredProperty("mysql.host") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(TestIsolationGuard.requiredProperty(userProperty));
        ds.setPassword(TestIsolationGuard.requiredProperty(passwordProperty));
        return ds;
    }

    // Getters
    public TestIsolationGuard.TestRunContext getContext() { return context; }
    public DataSource getMetaDs() { return metaDs; }
    public JdbcTemplate getMetaJdbc() { return metaJdbc; }
    public ObjectMapper getObjectMapper() { return objectMapper; }
    public EventClock getEventClock() { return eventClock; }
    public SourceRegistryMapper getSourceRegistryMapper() { return sourceRegistryMapper; }
    public ActiveSourceBindingMapper getActiveSourceBindingMapper() { return activeSourceBindingMapper; }
    public SourceMappingActiveMapper getSourceMappingActiveMapper() { return sourceMappingActiveMapper; }
    public RuntimeProfileMapper getRuntimeProfileMapper() { return runtimeProfileMapper; }
    public FileCheckpointMapper getFileCheckpointMapper() { return fileCheckpointMapper; }
    public IngestionBatchMapper getIngestionBatchMapper() { return ingestionBatchMapper; }
    public IngestionBatchFileMapper getIngestionBatchFileMapper() { return ingestionBatchFileMapper; }
    public QuarantineRecordMapper getQuarantineRecordMapper() { return quarantineRecordMapper; }
    public SourceRegistryService getSourceRegistryService() { return sourceRegistryService; }
    public RuntimeProfileService getRuntimeProfileService() { return runtimeProfileService; }
    public JdbcActiveMappingPointerStore getActiveMappingPointerStore() { return activeMappingPointerStore; }
    public MappingDryRunService getDryRunService() { return dryRunService; }
    public MappingActivationService getActivationService() { return activationService; }
    public SourceMapper getSourceMapper() { return sourceMapper; }
    public LocalFileIngestor getFileIngestor() { return fileIngestor; }
    public IngestionService getIngestionService() { return ingestionService; }
    public SourceRegistry getSourceA() { return sourceA; }
    public SourceRegistry getSourceB() { return sourceB; }
    public RuntimeProfile getActiveProfile() { return activeProfile; }
}
