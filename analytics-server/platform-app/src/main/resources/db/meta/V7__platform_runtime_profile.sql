-- =====================================================================
-- R2 运行环境档案与远程作业记录（整改书 §8/§13.3）
-- runtime_profile：LOCAL/SINGLE_NODE/REMOTE_CLUSTER 环境定义，凭据只存引用
-- spark_job_run：远程 Spark 作业运行记录（外部任务 ID/输入输出/日志 URI）
-- =====================================================================

-- 运行环境（§8.1 字段全集：数据库只保存凭据引用，不保存明文密码）
CREATE TABLE runtime_profile (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    profile_code           VARCHAR(64)  NOT NULL COMMENT '环境编码（唯一）',
    profile_name           VARCHAR(128) NOT NULL COMMENT '环境名称',
    type                   VARCHAR(24)  NOT NULL COMMENT 'LOCAL/SINGLE_NODE/REMOTE_CLUSTER',
    status                 VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/TESTING/ACTIVE/DISABLED',
    landing_uri            VARCHAR(255) NULL COMMENT 'file:/// 或 hdfs:// 落地基础路径',
    hdfs_uri               VARCHAR(255) NULL COMMENT 'HDFS NameNode URI（REMOTE_CLUSTER）',
    hive_jdbc_url          VARCHAR(255) NULL COMMENT 'Hive JDBC（SELECT 1 连通测试）',
    hive_database_prefix   VARCHAR(64)  NULL COMMENT '数仓库名前缀，如 dw_',
    spark_master           VARCHAR(128) NULL,
    deploy_mode            VARCHAR(32)  NULL,
    yarn_queue             VARCHAR(64)  NULL,
    ssh_host               VARCHAR(128) NULL COMMENT 'REMOTE_CLUSTER 必需的 SSH 字段',
    ssh_port               INT          NULL,
    ssh_user               VARCHAR(64)  NULL,
    spark_submit_path      VARCHAR(255) NULL COMMENT 'spark-submit 脚本路径',
    spark_job_jar_uri      VARCHAR(255) NULL COMMENT 'spark-jobs jar 位置',
    metric_store_type      VARCHAR(24)  NOT NULL DEFAULT 'MYSQL' COMMENT 'MYSQL；第二阶段 DORIS',
    metric_store_config_ref VARCHAR(255) NULL COMMENT '指标库配置引用（凭据引用）',
    credential_ref         VARCHAR(255) NULL COMMENT '凭据引用（密钥库 Key，不存明文）',
    timezone               VARCHAR(32)  NOT NULL DEFAULT 'Asia/Shanghai',
    version                INT          NOT NULL DEFAULT 1 COMMENT '环境配置版本（§8.3 激活递增）',
    created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_profile_code (profile_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行环境档案';

-- 远程/本地 Spark 作业运行记录（§13.3：external_job_id/输入输出/日志 URI）
CREATE TABLE spark_job_run (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    runtime_profile_id     BIGINT   NOT NULL COMMENT '实际运行环境（§8.1）',
    runtime_profile_version INT      NOT NULL DEFAULT 1 COMMENT '实际环境配置版本（§8.1）',
    pipeline_run_id   BIGINT       NOT NULL,
    stage_code        VARCHAR(32)  NOT NULL COMMENT 'LOAD_ODS/BUILD_DWD/...',
    job_code          VARCHAR(32)  NOT NULL COMMENT 'odl/bdw/usw/fna 等（spark-jobs 契约）',
    external_job_id   VARCHAR(128) NULL COMMENT '提交后返回的任务 ID（非空即提交成功）',
    submitter_type    VARCHAR(32)  NOT NULL COMMENT 'LOCAL_PROCESS/SSH',
    arguments_json    VARCHAR(2000) NULL COMMENT '提交参数快照（可复现）',
    status            VARCHAR(24)  NOT NULL COMMENT 'SUBMITTED/RUNNING/SUCCESS/FAILED/CANCELLED',
    input_records     BIGINT       NOT NULL DEFAULT 0,
    output_records    BIGINT       NOT NULL DEFAULT 0,
    rejected_records  BIGINT       NOT NULL DEFAULT 0,
    log_uri           VARCHAR(500) NULL COMMENT 'stdout/stderr 日志位置',
    started_at        DATETIME(3)  NULL,
    finished_at       DATETIME(3)  NULL,
    error_code        VARCHAR(64)  NULL,
    error_message     VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_spark_job_pipeline (pipeline_run_id, stage_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Spark 作业运行记录';

-- pipeline_run 补列（§13.3：任务实体完整字段；profile 版本/输入批次/目标快照/当前阶段）
ALTER TABLE pipeline_run
    ADD COLUMN runtime_profile_version INT      NOT NULL DEFAULT 1 AFTER runtime_profile_id,
    ADD COLUMN input_batch_id          BIGINT   NULL     AFTER source_data_version,
    ADD COLUMN target_snapshot_id      VARCHAR(64) NULL   AFTER input_batch_id,
    ADD COLUMN current_stage           VARCHAR(32) NULL   AFTER status,
    ADD COLUMN error_message           VARCHAR(1000) NULL AFTER error_code,
    ADD COLUMN created_by              VARCHAR(64) NULL   AFTER trace_id,
    ADD COLUMN started_at              DATETIME(3) NULL  AFTER created_at,
    ADD COLUMN finished_at             DATETIME(3) NULL  AFTER started_at;

-- metric_snapshot 补 profile 版本（§8.3：快照必须记录实际使用的环境版本）
ALTER TABLE metric_snapshot
    ADD COLUMN runtime_profile_version INT NOT NULL DEFAULT 1 AFTER runtime_profile_id;

-- 种子：LOCAL 开发环境（DRAFT，激活流程通过后才 ACTIVE，§8.3）
INSERT INTO runtime_profile
    (profile_code, profile_name, type, status, landing_uri, metric_store_type, timezone, version)
VALUES
    ('local-dev', 'LOCAL 开发环境（本机 Win）', 'LOCAL', 'DRAFT',
     'file://./landing', 'MYSQL', 'Asia/Shanghai', 1);