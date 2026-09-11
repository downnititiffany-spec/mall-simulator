-- =====================================================================
-- V1: 模拟数据生成器元数据库（V2.1 §4.2 五张表）
--
-- 独立库：本脚本只属于 synthetic-data-generator。
--   不建商城业务表（mall_*），不建分析平台元数据表（analytics_*/ods_*/dwd_*/dws_*/ads_*），
--   也不写任何 ODS/DWD/ADS/指标库（§3.3 B、§3.4-3）。
-- 运行状态机（§4.2）：generation_run.status = PENDING -> RUNNING -> SUCCESS / FAILED / CANCELLED
-- 可复现（§4.2）：相同 plan_version + seed + time_window 应可复现；运行启动后计划不可变。
-- =====================================================================

-- 目标商城配置（§4.2）：凭据只存引用；每次运行冻结 config_version
CREATE TABLE generator_target (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(128) NOT NULL COMMENT '目标名称（如 reference-mall）',
    adapter_type     VARCHAR(64)  NOT NULL COMMENT '适配器类型：REFERENCE_MALL_HTTP/CANONICAL_EVENT_FILE',
    base_url         VARCHAR(512) NOT NULL COMMENT '目标商城公开接口根地址（只走公开 HTTP，§3.3 A）',
    credential_ref   VARCHAR(255) NULL COMMENT '凭据引用（环境变量名/密钥别名），绝不存明文口令',
    config_json      TEXT         NULL COMMENT '适配器扩展配置（JSON）',
    config_version   INT          NOT NULL DEFAULT 1 COMMENT '配置版本，运行启动时冻结',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    test_environment TINYINT      NOT NULL DEFAULT 1 COMMENT '是否测试环境（1 是 / 0 否）',
    capabilities     VARCHAR(255) NULL COMMENT '能力声明（逗号分隔：product,user,behavior,order,refund）',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_generator_target_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成器目标商城配置（凭据只存引用）';

-- 生成计划（§4.2）：不可变版本，运行只引用 (plan_id, version)
CREATE TABLE generation_plan (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    plan_id         VARCHAR(64)  NOT NULL COMMENT '计划业务标识',
    version         INT          NOT NULL COMMENT '计划版本（同 plan_id 内递增；启动后不得修改）',
    mode            VARCHAR(32)  NOT NULL COMMENT 'MALL_API/CANONICAL_EVENT_FILE（§3.3）',
    target_id       BIGINT       NULL COMMENT 'generator_target.id（文件模式可为空）',
    scenario        VARCHAR(64)  NOT NULL COMMENT '场景码（ScenarioRegistry）',
    seed            BIGINT       NOT NULL COMMENT '根随机种子（并行线程用确定性子 seed 派生，§4.3）',
    start_time      DATETIME(3)  NOT NULL COMMENT '模拟窗口起点（业务时间）',
    end_time        DATETIME(3)  NOT NULL COMMENT '模拟窗口终点（业务时间）',
    event_count     BIGINT       NOT NULL DEFAULT 0 COMMENT '目标事件总量',
    rate_per_second INT          NOT NULL DEFAULT 0 COMMENT '负载上限（事件/秒），0 表示不限',
    dirty_profile   VARCHAR(64)  NOT NULL DEFAULT 'none' COMMENT '脏数据档位（仅文件模式生效，§3.3）',
    output_uri      VARCHAR(512) NULL COMMENT '产物根目录（文件模式；独立测试目录）',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_generation_plan_version (plan_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成计划（不可变版本）';

-- 运行实例（§4.2）：异步、可查询、可取消
CREATE TABLE generation_run (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    run_id           VARCHAR(64)  NOT NULL COMMENT '运行标识（对外 API 路径中的 {id}）',
    plan_id          VARCHAR(64)  NOT NULL COMMENT '启动时冻结的计划标识',
    plan_version     INT          NOT NULL COMMENT '启动时冻结的计划版本',
    target_id        BIGINT       NULL COMMENT '启动时冻结的目标 id',
    target_version   INT          NULL COMMENT '启动时冻结的目标配置版本',
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELLED',
    started_at       DATETIME(3)  NULL,
    finished_at      DATETIME(3)  NULL,
    success_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '成功事件数',
    failed_count     BIGINT       NOT NULL DEFAULT 0 COMMENT '失败事件数（§4.3 失败必须记入运行报告）',
    checksum         VARCHAR(64)  NULL COMMENT '产物聚合校验（与 generation_artifact.checksum 对账）',
    error_code       VARCHAR(64)  NULL COMMENT '失败错误码',
    error_message    VARCHAR(500) NULL COMMENT '失败摘要',
    cancel_requested TINYINT      NOT NULL DEFAULT 0 COMMENT '取消标记（幂等取消，§4.4）',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_generation_run_id (run_id),
    KEY idx_generation_run_status (status),
    KEY idx_generation_run_plan (plan_id, plan_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成运行实例（PENDING->RUNNING->SUCCESS/FAILED/CANCELLED）';

-- 运行产物（§4.2）：用于可复现和对账
CREATE TABLE generation_artifact (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    run_id         VARCHAR(64)  NOT NULL,
    uri            VARCHAR(512) NOT NULL COMMENT '产物 URI（JSONL / 清单 / 脏样本）',
    kind           VARCHAR(32)  NOT NULL DEFAULT 'EVENT_JSONL' COMMENT 'EVENT_JSONL/MANIFEST/DIRTY_SAMPLE',
    checksum       VARCHAR(64)  NULL COMMENT '文件校验（算法与编码由契约冻结前不得默认与采集侧相同）',
    bytes          BIGINT       NOT NULL DEFAULT 0,
    record_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '记录数（含脏样本时以清单为准）',
    min_event_time DATETIME(3)  NULL COMMENT '最小 event_time（对账用）',
    max_event_time DATETIME(3)  NULL COMMENT '最大 event_time（对账用）',
    schema_version VARCHAR(16)  NULL COMMENT '契约版本（如 1.0）',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_generation_artifact (run_id, uri),
    KEY idx_generation_artifact_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成产物（JSONL/清单/脏样本）';

-- 事件类型与金额分布（§4.2）：验证各事件类型和金额分布
CREATE TABLE generation_event_stat (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    run_id      VARCHAR(64)   NOT NULL,
    event_type  VARCHAR(64)   NOT NULL COMMENT '契约事件类型（canonical-event.v1 的 12 类之一）',
    event_count BIGINT        NOT NULL DEFAULT 0 COMMENT '该类型事件数（对应 §4.2 的 count）',
    amount      DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '该类型金额合计（金额类事件才有值）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_generation_event_stat (run_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行事件类型统计';
