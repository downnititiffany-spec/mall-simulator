package com.graduation.analytics.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.runtime.storage.HdfsLandingStorage;
import com.graduation.analytics.runtime.storage.LandingStorage;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import com.graduation.analytics.runtime.submit.JobSubmitter;
import com.graduation.analytics.runtime.submit.LocalProcessSparkSubmitter;
import com.graduation.analytics.runtime.submit.SshSparkSubmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RuntimeProfile 服务实现（§8.2/§8.3）。
 * 测试项按环境类型适用：
 *   - landing：LOCAL→LocalLandingStorage 读写；REMOTE_CLUSTER→HdfsLandingStorage
 *   - hive：hive_jdbc_url 配置则真实 SELECT 1；未配置 → UNKNOWN（不冒充通过）
 *   - spark：LOCAL→LocalProcessSparkSubmitter（真实进程）；REMOTE→SshSparkSubmitter
 *   - metric：真实查询 MetricStore（latestActive）
 * 仅适用项全部 PASSED 才允许 activate（UNKNOWN 计为未通过，防止假激活）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeProfileServiceImpl implements RuntimeProfileService {

    private final RuntimeProfileMapper profileMapper;
    private final LocalLandingStorage localLandingStorage;
    private final MetricStore metricStore;
    private final CredentialService credentialService;

    private static final String SPARK_SUBMIT_DEFAULT = "D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd";
    private static final String SPARK_JOB_JAR_DEFAULT = "spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar";
    private static final String LOG_ROOT_DEFAULT = "landing/logs";

    @Override
    public RuntimeProfile create(RuntimeProfile p) {
        if (p.getProfileCode() == null || p.getProfileCode().isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "profile_code 必填");
        }
        Long existed = profileMapper.selectCount(new LambdaQueryWrapper<RuntimeProfile>()
                .eq(RuntimeProfile::getProfileCode, p.getProfileCode()));
        if (existed > 0) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "profile_code 已存在: " + p.getProfileCode());
        }
        p.setId(null);
        if (p.getStatus() == null || p.getStatus().isBlank()) {
            p.setStatus(RuntimeProfile.STATUS_DRAFT);
        }
        if (p.getType() == null || p.getType().isBlank()) {
            p.setType(RuntimeProfile.TYPE_LOCAL);
        }
        if (p.getVersion() == null) {
            p.setVersion(1);
        }
        if (p.getTimezone() == null || p.getTimezone().isBlank()) {
            p.setTimezone("Asia/Shanghai");
        }
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        rejectProfileHivePrefix(p);
        profileMapper.insert(p);
        return p;
    }

    @Override
    public RuntimeProfile update(RuntimeProfile p) {
        if (p.getId() == null) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "id 必填");
        }
        RuntimeProfile existed = profileMapper.selectById(p.getId());
        if (existed == null) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "profile 不存在: " + p.getId());
        }
        if (RuntimeProfile.STATUS_ACTIVE.equals(existed.getStatus())) {
            // 激活中的环境不允许直接改关键配置（§8.3：切换环境不能影响运行中批次）
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "ACTIVE 环境禁止修改，请先 disable");
        }
        p.setProfileCode(existed.getProfileCode()); // code 不可改
        p.setStatus(existed.getStatus());
        p.setVersion(existed.getVersion());
        p.setCreatedAt(existed.getCreatedAt());
        p.setUpdatedAt(LocalDateTime.now());
        rejectProfileHivePrefix(p);
        profileMapper.updateById(p);
        return profileMapper.selectById(p.getId());
    }

    /**
     * P2-07（D-070/D-073）：数仓命名空间前缀已下沉到源级，本档案的
     * {@code hive_database_prefix} 不再是任何生产路径的读取点。
     *
     * <p>为什么非空值**拒绝**而不是忽略：静默丢掉调用方以为生效的配置，会留下
     * "我改了前缀"与"库名还跟着源走"两套认知长期并存——这类不一致最难查。
     * 空值（null/空白）放行并归一为 null：等价于"没写"，也让该列在写入侧保持空置。</p>
     */
    private static void rejectProfileHivePrefix(RuntimeProfile p) {
        String prefix = p.getHiveDatabasePrefix();
        if (prefix == null) {
            return;
        }
        if (prefix.isBlank()) {
            p.setHiveDatabasePrefix(null);
            return;
        }
        throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                "hive_database_prefix 已停用：数仓命名空间前缀自 P2-07 起登记在**源**上"
                        + "（source_registry.warehouse_prefix，对应接口字段 warehousePrefix），"
                        + "不再从运行环境档案读取（D-070/D-073）。请改源登记，档案侧的该字段不再接受非空值");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TestResult test(Long profileId) {
        RuntimeProfile p = require(profileId);
        List<CheckDetail> checks = new ArrayList<>();

        // 1. Landing 读写
        checks.add(checkLanding(p));

        // 2. Hive SELECT 1（真实 JDBC 探测）
        checks.add(checkHive(p));

        // 3. 最小 Spark 任务可执行性
        checks.add(checkSpark(p));

        // 4. MetricStore 查询（真实）
        checks.add(checkMetric(p));

        boolean allPassed = checks.stream()
                .filter(CheckDetail::applicable)
                .allMatch(CheckDetail::passed);
        p.setStatus(allPassed ? RuntimeProfile.STATUS_TESTING : p.getStatus());
        profileMapper.updateById(p);
        StringBuilder sb = new StringBuilder();
        for (CheckDetail c : checks) {
            sb.append(c.name())
                    .append(c.applicable() ? (c.passed() ? "=PASS " : "=FAIL ") : "=SKIPPED(不适用) ")
                    .append(c.detail()).append("; ");
        }
        if (!allPassed) {
            sb.append("存在适用项未通过，未置 TESTING/不激活");
        }
        return new TestResult(profileId, allPassed, checks, sb.toString());
    }

    private CheckDetail checkLanding(RuntimeProfile p) {
        try {
            if (RuntimeProfile.TYPE_REMOTE_CLUSTER.equals(p.getType())) {
                HdfsLandingStorage hdfs = new HdfsLandingStorage(p.getLandingUri());
                return wrap("landing", hdfs.healthCheck());
            }
            String profileUri = p.getLandingUri();
            if (profileUri != null && (profileUri.startsWith("hdfs://"))) {
                HdfsLandingStorage hdfs = new HdfsLandingStorage(profileUri);
                return wrap("landing", hdfs.healthCheck());
            }
            // LOCAL：用 localRoot 探针 + 失败时给出明确原因
            return wrap("landing", localLandingStorage.healthCheck());
        } catch (Exception e) {
            return new CheckDetail("landing", false, "ex: " + e.getMessage());
        }
    }

    private CheckDetail checkHive(RuntimeProfile p) {
        String url = p.getHiveJdbcUrl();
        if (url == null || url.isBlank()) {
            // LOCAL 演示无 Hive：该环境类型不适用此项（如实标注 SKIPPED，不伪装通过）
            if (RuntimeProfile.TYPE_LOCAL.equals(p.getType())) {
                return new CheckDetail("hive", false, false,
                        "不适用: LOCAL 环境无 Hive（REMOTE_CLUSTER 必须配置 hive_jdbc_url）");
            }
            return new CheckDetail("hive", false, "UNKNOWN: 未配置 hive_jdbc_url");
        }
        try (Connection conn = DriverManager.getConnection(url,
                p.getCredentialRef() != null && p.getCredentialRef().startsWith("env:")
                        ? System.getenv(p.getCredentialRef().substring(4)) : "hive",
                "")) {
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT 1");
            }
            return new CheckDetail("hive", true, "SELECT 1 ok @ " + url);
        } catch (Exception e) {
            return new CheckDetail("hive", false, "SELECT 1 failed: " + e.getMessage());
        }
    }

    private CheckDetail checkSpark(RuntimeProfile p) {
        try {
            if (RuntimeProfile.TYPE_REMOTE_CLUSTER.equals(p.getType())) {
                String credential = credentialService.resolve(p.getCredentialRef());
                SshSparkSubmitter ssh = new SshSparkSubmitter(p.getSshHost(),
                        p.getSshPort() == null ? 22 : p.getSshPort(), p.getSshUser(), credential);
                return wrap("spark", ssh.healthCheck());
            }
            String sp = p.getSparkSubmitPath() != null && !p.getSparkSubmitPath().isBlank()
                    ? p.getSparkSubmitPath() : SPARK_SUBMIT_DEFAULT;
            LocalProcessSparkSubmitter lp = new LocalProcessSparkSubmitter(sp, LOG_ROOT_DEFAULT);
            return wrap("spark", lp.healthCheck());
        } catch (Exception e) {
            return new CheckDetail("spark", false, "ex: " + e.getMessage());
        }
    }

    private CheckDetail checkMetric(RuntimeProfile p) {
        try {
            MetricStore.HealthResult h = metricStore.healthCheck();
            if (!h.ok()) {
                return new CheckDetail("metric", false, "healthCheck: " + h.detail());
            }
            return new CheckDetail("metric", true, "metricStore query ok");
        } catch (Exception e) {
            return new CheckDetail("metric", false, "ex: " + e.getMessage());
        }
    }

    private CheckDetail wrap(String name, JobSubmitter.HealthResult h) {
        return new CheckDetail(name, h.ok(), h.detail());
    }

    private CheckDetail wrap(String name, LandingStorage.HealthResult h) {
        return new CheckDetail(name, h.ok(), h.detail());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RuntimeProfile activate(Long profileId) {
        RuntimeProfile p = require(profileId);
        List<CheckDetail> checks = new ArrayList<>();
        checks.add(checkLanding(p));
        checks.add(checkHive(p));
        checks.add(checkSpark(p));
        checks.add(checkMetric(p));
        boolean allPassed = checks.stream()
                .filter(CheckDetail::applicable)
                .allMatch(CheckDetail::passed);
        if (!allPassed) {
            StringBuilder sb = new StringBuilder();
            for (CheckDetail c : checks) {
                if (c.applicable() && !c.passed()) {
                    sb.append(c.name()).append(": ").append(c.detail()).append("; ");
                }
            }
            throw new PlatformBizException("ACTIVATE_CHECK_FAILED",
                    "激活前置测试未全部通过: " + sb);
        }
        // 旧 ACTIVE → DISABLED（§8.3 切换环境不能影响已运行批次）
        List<RuntimeProfile> actives = profileMapper.selectList(new LambdaQueryWrapper<RuntimeProfile>()
                .eq(RuntimeProfile::getStatus, RuntimeProfile.STATUS_ACTIVE)
                .ne(RuntimeProfile::getId, p.getId()));
        for (RuntimeProfile a : actives) {
            a.setStatus(RuntimeProfile.STATUS_DISABLED);
            profileMapper.updateById(a);
            log.info("旧 ACTIVE {} (id={}) → DISABLED（被 {} 新激活替代）", a.getProfileCode(), a.getId(), p.getProfileCode());
        }
        p.setStatus(RuntimeProfile.STATUS_ACTIVE);
        p.setVersion(p.getVersion() == null ? 2 : p.getVersion() + 1);
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);
        return p;
    }

    @Override
    public RuntimeProfile disable(Long profileId) {
        RuntimeProfile p = require(profileId);
        p.setStatus(RuntimeProfile.STATUS_DISABLED);
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);
        return p;
    }

    @Override
    public RuntimeProfile getActive() {
        return findActive().orElseThrow(() -> new PlatformBizException(PlatformBizException.INTERNAL,
                "尚无 ACTIVE 运行环境（请先完成激活流程，§8.3）"));
    }

    @Override
    public Optional<RuntimeProfile> findActive() {
        return Optional.ofNullable(profileMapper.selectOne(new LambdaQueryWrapper<RuntimeProfile>()
                .eq(RuntimeProfile::getStatus, RuntimeProfile.STATUS_ACTIVE)
                .orderByDesc(RuntimeProfile::getId)
                .last("LIMIT 1")));
    }

    @Override
    public RuntimeProfile get(Long id) {
        return require(id);
    }

    @Override
    public List<RuntimeProfile> list() {
        return profileMapper.selectList(new LambdaQueryWrapper<RuntimeProfile>()
                .orderByDesc(RuntimeProfile::getId));
    }

    private RuntimeProfile require(Long id) {
        RuntimeProfile p = profileMapper.selectById(id);
        if (p == null) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "profile 不存在: " + id);
        }
        return p;
    }
}