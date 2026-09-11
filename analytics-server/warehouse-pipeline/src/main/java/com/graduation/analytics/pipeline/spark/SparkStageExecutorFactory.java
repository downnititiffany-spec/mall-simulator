package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.submit.JobSubmitter;
import com.graduation.analytics.runtime.submit.JobSubmitterFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R6-11（V2.0 §15.3）：阶段执行器工厂——生产 {@code PipelineService} 的唯一注入点。
 *
 * 职责：把"不可变环境快照"翻译成"可执行的真实 Spark 阶段执行器"：
 *   快照 → {@link JobSubmitterFactory} 选提交器（LOCAL 进程 / SSH）→ {@link SparkStageExecutor}
 *
 * 这样 PipelineService 只依赖本工厂（可注入 Fake 做 L1 编排测试），既不关心提交器选择，
 * 也不关心轮询/超时细节；同时保证同一次 run 用同一份快照（R6-10 不可变输入）。
 *
 * 还负责给出每次提交的 Spark --conf（warehouse/metastore）：LOCAL 的嵌入式 Hive 元数据库与
 * 仓位置必须由平台显式指定，不能让 spark-submit 按自己的 CWD 决定（否则同一份数据因启动
 * 目录不同而分裂成多个 warehouse，历史 R6-7 已因此踩坑）。
 */
@Slf4j
public class SparkStageExecutorFactory {

    /** 本地档案钉死的 driver 地址（见 confsFor 中 DEF-01 说明） */
    private static final String LOOPBACK = "127.0.0.1";

    private final JobSubmitterFactory submitterFactory;
    private final SparkJobRunMapper jobRunMapper;
    private final long maxWaitMs;
    private final long pollIntervalMs;
    private final String warehouseDir;
    private final String metastoreDir;

    public SparkStageExecutorFactory(JobSubmitterFactory submitterFactory,
                                     SparkJobRunMapper jobRunMapper,
                                     long maxWaitMs, long pollIntervalMs,
                                     String warehouseDir, String metastoreDir) {
        this.submitterFactory = submitterFactory;
        this.jobRunMapper = jobRunMapper;
        this.maxWaitMs = maxWaitMs;
        this.pollIntervalMs = pollIntervalMs;
        this.warehouseDir = warehouseDir;
        this.metastoreDir = metastoreDir;
    }

    /** 按环境快照构造执行器；每次调用新实例（无跨 run 状态） */
    public SparkStageExecutor create(RuntimeProfileSnapshot profile) {
        JobSubmitter submitter = submitterFactory.create(profile);
        log.debug("SparkStageExecutorFactory: profile={} v{} submitter={} maxWaitMs={} pollIntervalMs={}",
                profile.id(), profile.version(), submitter.type(), maxWaitMs, pollIntervalMs);
        return new SparkStageExecutor(submitter, jobRunMapper, maxWaitMs, pollIntervalMs);
    }

    /**
     * 本次提交的 Spark 配置（空表 = 用 spark-submit 默认；REMOTE_CLUSTER 交给集群侧 Hive）。
     * LOCAL/SINGLE_NODE：显式 warehouse + 嵌入式 Derby 元数据库（可复现、不随 CWD 漂移）。
     */
    public Map<String, String> confsFor(RuntimeProfileSnapshot profile) {
        if (profile.isRemoteCluster()) {
            Map<String, String> confs = new LinkedHashMap<>();
            if (profile.hiveJdbcUrl() != null && !profile.hiveJdbcUrl().isBlank()) {
                confs.put("hive.metastore.uris", profile.hiveJdbcUrl());
            }
            return confs;
        }
        Map<String, String> confs = new LinkedHashMap<>();
        confs.put("spark.sql.warehouse.dir", fileUri(warehouseDir));
        confs.put("spark.sql.session.timeZone", "Asia/Shanghai");
        // DEF-01：本机 hosts 把本机 IP(172.16.208.83) 反解为 host.docker.internal，Spark 自动探测的
        // driver host 随之变成该名字；local 模式下 executor 需从 spark://host.docker.internal:<port>/jars/
        // 拉作业 jar，连接不通即无限阻塞在 SparkContext 初始化（driver 僵死，平台只能等满超时）。
        // 本地档案显式钉死回环地址，与 hosts/DNS 现状解耦（集群档案不受影响）。
        confs.put("spark.driver.host", LOOPBACK);
        confs.put("spark.driver.bindAddress", LOOPBACK);
        // 嵌入式 Derby 元数据库固定路径：跨 run / 跨进程一致（sci 建表与 odl 装载看到同一套表）
        confs.put("spark.hadoop.javax.jdo.option.ConnectionURL",
                "jdbc:derby:" + absolute(metastoreDir).replace('\\', '/') + ";create=true");
        confs.put("spark.hadoop.javax.jdo.option.ConnectionDriverName",
                "org.apache.derby.jdbc.EmbeddedDriver");
        confs.put("spark.sql.hive.metastore.jars", "builtin");
        confs.put("spark.hadoop.datanucleus.schema.autoCreateTables", "true");
        return confs;
    }

    private static String fileUri(String dir) {
        String p = absolute(dir);
        if (p.startsWith("file:")) {
            return p;
        }
        return "file:///" + p.replace('\\', '/');
    }

    private static String absolute(String dir) {
        String d = (dir == null || dir.isBlank()) ? "./spark-warehouse" : dir;
        Path path = Paths.get(d);
        return (path.isAbsolute() ? path : path.toAbsolutePath()).toString();
    }

    public long maxWaitMs() {
        return maxWaitMs;
    }

    public long pollIntervalMs() {
        return pollIntervalMs;
    }
}
