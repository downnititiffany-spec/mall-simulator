-- =====================================================================
-- V2: 采集链路元数据（§5.2.7 批次状态机、§5.2.8 断点/重放、§5.2.5 隔离）
-- 状态：GENERATED -> COLLECTING -> LANDED -> VALIDATING -> SUCCESS
--                                              └-> QUARANTINED（存在隔离行）
-- =====================================================================

-- 采集批次（一次对 events 目录的扫描 = 一个批次）
CREATE TABLE ingestion_batch (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no        VARCHAR(64)  NOT NULL COMMENT '批次号（时间戳生成）',
    source          VARCHAR(32)  NOT NULL DEFAULT 'local-file' COMMENT 'local-file/flume/datax',
    status          VARCHAR(24)  NOT NULL COMMENT '批次状态机',
    record_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '采集并落 landing 的干净行数',
    error_count     BIGINT       NOT NULL DEFAULT 0 COMMENT '解析/IO 错误行数',
    quarantine_count BIGINT      NOT NULL DEFAULT 0 COMMENT '隔离行数（契约校验失败）',
    landing_dir     VARCHAR(255) NULL COMMENT '本轮 landed 目录',
    start_time      DATETIME(3)  NOT NULL,
    end_time        DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集批次';

-- 批次内文件级明细（start_offset/end_offset 是重放与对账依据，§5.2.8）
CREATE TABLE ingestion_batch_file (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id     BIGINT       NOT NULL,
    file_path    VARCHAR(500) NOT NULL COMMENT '相对 landing 根的文件路径',
    start_offset BIGINT       NOT NULL DEFAULT 0,
    end_offset   BIGINT       NOT NULL DEFAULT 0,
    record_count BIGINT       NOT NULL DEFAULT 0,
    status       VARCHAR(24)  NOT NULL COMMENT 'COLLECTED/LANDED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_file (batch_id, file_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批次文件明细';

-- 文件断点（Taildir position 语义：下次从 next_offset 继续，暂停恢复不丢不重）
CREATE TABLE file_checkpoint (
    file_path    VARCHAR(500) NOT NULL COMMENT '相对 landing 根的文件路径',
    next_offset  BIGINT       NOT NULL DEFAULT 0 COMMENT '下一个待读字节偏移',
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (file_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集断点';

-- 隔离记录（坏行索引：未知版本/非法枚举/缺字段等，§5.2.5/§6.2 unknown schema -> quarantine）
CREATE TABLE quarantine_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id       BIGINT       NOT NULL,
    event_id       VARCHAR(64)  NULL COMMENT '能解析出则填，否则 null',
    schema_version VARCHAR(16)  NULL,
    reason         VARCHAR(255) NOT NULL COMMENT '隔离原因（契约校验描述）',
    raw_path       VARCHAR(500) NOT NULL COMMENT '隔离文件路径',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_quarantine_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隔离数据索引';