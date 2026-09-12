-- MySQL dump 10.13  Distrib 8.0.41, for Win64 (x86_64)
--
-- Host: localhost    Database: analytics_meta
-- ------------------------------------------------------
-- Server version	8.0.41

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `ai_call_log`
--

DROP TABLE IF EXISTS `ai_call_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `use_case` varchar(32) NOT NULL COMMENT 'sql_generation/sql_repair/explanation/analysis',
  `provider` varchar(64) NOT NULL COMMENT 'openai-compat/mock/rule-based',
  `model` varchar(64) DEFAULT NULL,
  `prompt_version` varchar(32) DEFAULT NULL,
  `input_tokens` int DEFAULT NULL,
  `output_tokens` int DEFAULT NULL,
  `elapsed_ms` bigint NOT NULL,
  `status` varchar(16) NOT NULL,
  `error` varchar(512) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 模型调用日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_query_history`
--

DROP TABLE IF EXISTS `ai_query_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_query_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL DEFAULT 'demo',
  `question` varchar(1024) NOT NULL,
  `sql_text` text,
  `tables` varchar(255) DEFAULT NULL,
  `snapshot_id` varchar(64) DEFAULT NULL COMMENT 'R8-2 本次问数钉住的快照（§19.5 禁止 SQL 内 MAX(snapshot_id)）',
  `status` varchar(32) NOT NULL COMMENT 'GENERATED/SAFE/REPAIRED/EXECUTED/FAILED',
  `rows_returned` int DEFAULT NULL,
  `elapsed_ms` bigint DEFAULT NULL,
  `feedback` varchar(16) DEFAULT NULL COMMENT 'GOOD/BAD',
  `errors` varchar(512) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `scope_min_date` date DEFAULT NULL COMMENT 'R8-2 允许的最早日期（业务日-89，扫描上限 90 天）',
  `scope_max_date` date DEFAULT NULL COMMENT 'R8-2 允许的最晚日期（快照业务日）',
  `explain_rows` bigint DEFAULT NULL COMMENT 'R8-2 EXPLAIN 估算扫描行数（超阈值拒绝执行）',
  `scope_row_limit` int DEFAULT NULL COMMENT 'R8-2 单次查询最大返回行数（200）',
  PRIMARY KEY (`id`),
  KEY `idx_ai_history_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=55 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 自然语言查询历史';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `data_quality_result`
--

DROP TABLE IF EXISTS `data_quality_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `data_quality_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `rule_code` varchar(64) NOT NULL,
  `layer` varchar(32) DEFAULT NULL COMMENT 'R6-13 规则层次：LANDING/DWD/DWS/ADS_STAGING/PUBLISH',
  `severity` varchar(16) DEFAULT NULL COMMENT 'R6-13 严重度：BLOCKING/ERROR/INFO',
  `target_table` varchar(500) DEFAULT NULL COMMENT 'R6-13 规则作用对象（表名/分区范围，多表时列出）',
  `snapshot_id` varchar(64) DEFAULT NULL COMMENT 'R6-13 本次快照号',
  `check_count` bigint NOT NULL,
  `error_count` bigint NOT NULL,
  `error_rate` decimal(10,6) NOT NULL,
  `threshold` varchar(64) NOT NULL,
  `passed` int NOT NULL COMMENT '1=通过 0=失败',
  `detail` varchar(2000) DEFAULT NULL COMMENT 'R6-13 规则明细（失败原因/实测计数）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_quality_run` (`run_id`)
) ENGINE=InnoDB AUTO_INCREMENT=380 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据质量结果';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `decision_evaluation`
--

DROP TABLE IF EXISTS `decision_evaluation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `decision_evaluation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `decision_id` bigint NOT NULL,
  `baseline_value` decimal(18,4) NOT NULL,
  `actual_value` decimal(18,4) DEFAULT NULL,
  `improvement_rate` decimal(10,4) DEFAULT NULL,
  `baseline_snapshot_id` varchar(64) DEFAULT NULL COMMENT 'R8-3 前快照：批准时钉住的基线快照号',
  `result` varchar(24) NOT NULL COMMENT 'EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA',
  `note` varchar(512) DEFAULT NULL,
  `evaluated_by` varchar(64) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `actual_snapshot_id` varchar(64) DEFAULT NULL COMMENT 'R8-3 后快照：评价时读取的最新已发布快照号',
  `window_start` date DEFAULT NULL COMMENT 'R8-3 评价窗口起（= completedDate+1）',
  `window_end` date DEFAULT NULL COMMENT 'R8-3 评价窗口止（= completedDate+N，与 baseline 窗口等长）',
  `baseline_period_value` decimal(18,4) DEFAULT NULL COMMENT 'R8-3 基线窗口观测值（快照粒度观测，见 DecisionService 口径说明）',
  `actual_period_value` decimal(18,4) DEFAULT NULL COMMENT 'R8-3 评价窗口观测值',
  `sample_count` int DEFAULT NULL COMMENT 'R8-3 参与对比的观测样本数（前后快照目标指标行数之和）',
  `definition_version` varchar(32) DEFAULT NULL COMMENT 'R8-3 评价口径版本（含可配置阈值版本，§20.4）',
  `eval_window_days` int DEFAULT NULL COMMENT 'R8-3 评价窗口天数（前端 tables.js 展示「窗口 N 天」）',
  PRIMARY KEY (`id`),
  KEY `idx_eval_decision` (`decision_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='决策效果评价（前后对比，非因果推断，§21.10）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `decision_task`
--

DROP TABLE IF EXISTS `decision_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `decision_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `decision_no` varchar(64) NOT NULL,
  `source` varchar(16) NOT NULL COMMENT 'ai=AI草稿(只能DRAFT) / human=人工创建',
  `suggestion_snapshot_id` varchar(64) DEFAULT NULL COMMENT 'AI 建议对应的证据快照',
  `title` varchar(255) NOT NULL,
  `action` varchar(1024) NOT NULL,
  `target_metric_code` varchar(64) DEFAULT NULL COMMENT '目标指标（无目标指标只能作为一般提示，§22.3）',
  `target_direction` varchar(8) DEFAULT NULL COMMENT 'UP=越高越好 / DOWN=越低越好',
  `baseline_value` decimal(18,4) DEFAULT NULL COMMENT '批准时从当前 ACTIVE 快照锁定的基线值',
  `baseline_snapshot_id` varchar(64) DEFAULT NULL COMMENT 'R8-3 批准时钉住的基线快照（§20.4 前快照，评价时不再漂移）',
  `target_value` decimal(18,4) DEFAULT NULL,
  `eval_window_days` int NOT NULL DEFAULT '3',
  `owner` varchar(64) DEFAULT NULL,
  `due_date` date DEFAULT NULL,
  `status` varchar(24) NOT NULL COMMENT '12 态状态机（§22.6）',
  `risk` varchar(255) DEFAULT NULL,
  `reject_reason` varchar(255) DEFAULT NULL,
  `cancel_reason` varchar(255) DEFAULT NULL,
  `created_by` varchar(64) NOT NULL,
  `approved_by` varchar(64) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `started_at` datetime(3) DEFAULT NULL,
  `completed_at` datetime(3) DEFAULT NULL,
  `evaluated_at` datetime(3) DEFAULT NULL,
  `definition_version` varchar(32) DEFAULT NULL COMMENT 'R8-3 批准时目标指标口径版本（口径变更后可追溯）',
  `evidence_package_id` varchar(64) DEFAULT NULL COMMENT 'R8-3 AI 建议证据包 id（§20.3 提交审批前必须齐备）',
  `approval_note` varchar(512) DEFAULT NULL COMMENT 'R8-3 审批备注（含基线快照/窗口溯源信息）',
  `execution_note` varchar(512) DEFAULT NULL COMMENT 'R8-3 执行备注（执行过程记录）',
  `approved_at` datetime(3) DEFAULT NULL COMMENT 'R8-3 批准时间：§20.4 基线窗口 [approvedDate-N+1, approvedDate] 的唯一依据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_decision_no` (`decision_no`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='决策任务（AI草稿/人工审核/执行/评价）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `file_checkpoint`
--

DROP TABLE IF EXISTS `file_checkpoint`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `file_path` varchar(500) NOT NULL COMMENT '相对 landing 根的文件路径',
  `next_offset` bigint NOT NULL DEFAULT '0' COMMENT '下一个待读字节偏移',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `runtime_profile_id` bigint NOT NULL DEFAULT '1' COMMENT '运行环境（§9.2）',
  `source_id` bigint NOT NULL COMMENT '源登记（source_registry.id，D-037）：断点归属的源，切换源不共享断点',
  `file_identity` varchar(64) NOT NULL DEFAULT '' COMMENT '文件身份（创建时间戳，变化视为新文件）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ckpt_source` (`runtime_profile_id`,`source_id`,`file_path`,`file_identity`),
  KEY `idx_file_checkpoint_source` (`source_id`),
  CONSTRAINT `fk_file_checkpoint_source` FOREIGN KEY (`source_id`) REFERENCES `source_registry` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=103 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采集断点';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ingestion_batch`
--

DROP TABLE IF EXISTS `ingestion_batch`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ingestion_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_no` varchar(64) NOT NULL COMMENT '批次号（时间戳生成）',
  `source` varchar(32) NOT NULL DEFAULT 'local-file' COMMENT 'local-file/flume/datax',
  `runtime_profile_id` bigint NOT NULL DEFAULT '1' COMMENT '采集归属的运行环境',
  `source_id` bigint DEFAULT NULL COMMENT '源登记（source_registry.id，D-037）：批次归属的源；历史行可空',
  `status` varchar(24) NOT NULL COMMENT '批次状态机',
  `record_count` bigint NOT NULL DEFAULT '0' COMMENT '采集并落 landing 的干净行数',
  `error_count` bigint NOT NULL DEFAULT '0' COMMENT '解析/IO 错误行数',
  `quarantine_count` bigint NOT NULL DEFAULT '0' COMMENT '隔离行数（契约校验失败）',
  `landing_dir` varchar(255) DEFAULT NULL COMMENT '本轮 landed 目录',
  `start_time` datetime(3) NOT NULL,
  `end_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`),
  KEY `idx_ingestion_batch_source` (`source_id`),
  CONSTRAINT `fk_ingestion_batch_source` FOREIGN KEY (`source_id`) REFERENCES `source_registry` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=40 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采集批次';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ingestion_batch_file`
--

DROP TABLE IF EXISTS `ingestion_batch_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ingestion_batch_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `file_path` varchar(500) NOT NULL COMMENT '相对 landing 根的文件路径',
  `start_offset` bigint NOT NULL DEFAULT '0',
  `end_offset` bigint NOT NULL DEFAULT '0',
  `record_count` bigint NOT NULL DEFAULT '0',
  `status` varchar(24) NOT NULL COMMENT 'COLLECTED/LANDED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_file` (`batch_id`,`file_path`)
) ENGINE=InnoDB AUTO_INCREMENT=110 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次文件明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `metric_definition`
--

DROP TABLE IF EXISTS `metric_definition`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `metric_definition` (
  `metric_code` varchar(64) NOT NULL,
  `metric_name` varchar(128) NOT NULL,
  `formula` varchar(512) NOT NULL COMMENT '口径公式',
  `grain` varchar(32) NOT NULL COMMENT 'day/hour/user×period',
  `default_time_field` varchar(32) NOT NULL COMMENT 'event_time/paid_at',
  `unit` varchar(16) NOT NULL DEFAULT '',
  `definition_version` varchar(16) NOT NULL,
  PRIMARY KEY (`metric_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标字典';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `metric_snapshot`
--

DROP TABLE IF EXISTS `metric_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `metric_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `snapshot_id` varchar(64) NOT NULL,
  `runtime_profile_id` bigint NOT NULL DEFAULT '1',
  `runtime_profile_version` int NOT NULL DEFAULT '1',
  `business_time` datetime(3) NOT NULL COMMENT '快照业务时间',
  `pipeline_run_id` bigint DEFAULT NULL,
  `status` varchar(24) NOT NULL COMMENT 'BUILDING/VERIFYING/ACTIVE/ARCHIVED/FAILED',
  `version` int NOT NULL DEFAULT '1',
  `data_updated_at` datetime(3) NOT NULL,
  `published_at` datetime(3) DEFAULT NULL,
  `source` varchar(32) NOT NULL DEFAULT 'local-calculator' COMMENT 'spark-job / local-calculator',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
  KEY `idx_snapshot_status` (`runtime_profile_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标快照';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `metric_value`
--

DROP TABLE IF EXISTS `metric_value`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `metric_value` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `snapshot_id` varchar(64) NOT NULL,
  `metric_code` varchar(64) NOT NULL,
  `metric_value` decimal(18,4) NOT NULL,
  `unit` varchar(16) NOT NULL DEFAULT '',
  `period` varchar(32) NOT NULL COMMENT 'day:2026-09-01',
  `definition_version` varchar(16) NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_metric` (`snapshot_id`,`metric_code`)
) ENGINE=InnoDB AUTO_INCREMENT=133 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快照指标值';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `operation_audit_log`
--

DROP TABLE IF EXISTS `operation_audit_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trace_id` varchar(64) DEFAULT NULL COMMENT '链路 trace id（与响应信封一致）',
  `user_id` varchar(64) NOT NULL COMMENT '操作者（取自登录会话，禁止请求头伪造）',
  `role` varchar(32) DEFAULT NULL COMMENT '操作者角色',
  `action` varchar(64) NOT NULL COMMENT '动作码：DECISION_CREATE/SUBMIT/APPROVE/REJECT/START/COMPLETE/CANCEL/EVALUATE、USER_CREATE/USER_TOGGLE/USER_RESET_PASSWORD、AI_QUERY',
  `resource_type` varchar(32) NOT NULL COMMENT '资源类型：DECISION_TASK/SYS_USER/AI_QUERY',
  `resource_id` varchar(64) DEFAULT NULL COMMENT '资源 id',
  `before_digest` varchar(512) DEFAULT NULL COMMENT '变更前摘要（仅状态与关键字段，禁止密码/token）',
  `after_digest` varchar(512) DEFAULT NULL COMMENT '变更后摘要',
  `reason` varchar(512) DEFAULT NULL COMMENT '原因/说明（驳回、取消、评价结论等）',
  `ip` varchar(64) DEFAULT NULL COMMENT '客户端 ip',
  `result` varchar(16) NOT NULL COMMENT 'SUCCESS/FAILED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_audit_action_time` (`action`,`created_at`),
  KEY `idx_audit_user_time` (`user_id`,`created_at`),
  KEY `idx_audit_resource` (`resource_type`,`resource_id`)
) ENGINE=InnoDB AUTO_INCREMENT=93 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志（R8-3 §21.4：决策全流程/用户管理/AI 查询）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pipeline_run`
--

DROP TABLE IF EXISTS `pipeline_run`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `idempotency_key` varchar(128) NOT NULL,
  `runtime_profile_id` bigint NOT NULL,
  `runtime_profile_version` int NOT NULL DEFAULT '1',
  `pipeline_code` varchar(64) NOT NULL COMMENT 'DAILY_CORE/HOURLY_CORE',
  `business_time` datetime(3) NOT NULL,
  `source_data_version` varchar(64) DEFAULT NULL,
  `input_batch_id` bigint DEFAULT NULL,
  `target_snapshot_id` varchar(64) DEFAULT NULL,
  `attempt_no` int NOT NULL DEFAULT '1',
  `status` varchar(24) NOT NULL,
  `current_stage` varchar(32) DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `trace_id` varchar(64) NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency` (`idempotency_key`)
) ENGINE=InnoDB AUTO_INCREMENT=40 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流水线实例';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pipeline_stage_run`
--

DROP TABLE IF EXISTS `pipeline_stage_run`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_stage_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `stage_code` varchar(32) NOT NULL,
  `status` varchar(24) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
  `external_job_id` varchar(64) DEFAULT NULL COMMENT '集群模式 Spark 任务 ID',
  `records` bigint NOT NULL DEFAULT '0',
  `error_code` varchar(64) DEFAULT NULL,
  `evidence` mediumtext COMMENT '阶段证据 JSON（批次/作业明细/输出分区/契约口径等；超长时结构化缩减保持 JSON 合法）',
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_stage_run` (`run_id`)
) ENGINE=InnoDB AUTO_INCREMENT=257 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流水线阶段实例';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `quarantine_record`
--

DROP TABLE IF EXISTS `quarantine_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `quarantine_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `event_id` varchar(64) DEFAULT NULL COMMENT '能解析出则填，否则 null',
  `schema_version` varchar(16) DEFAULT NULL,
  `reason` varchar(255) NOT NULL COMMENT '隔离原因（契约校验描述）',
  `raw_path` varchar(500) NOT NULL COMMENT '隔离文件路径',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_quarantine_batch` (`batch_id`)
) ENGINE=InnoDB AUTO_INCREMENT=105 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='隔离数据索引';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `runtime_profile`
--

DROP TABLE IF EXISTS `runtime_profile`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `runtime_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `profile_code` varchar(64) NOT NULL COMMENT '环境编码（唯一）',
  `profile_name` varchar(128) NOT NULL COMMENT '环境名称',
  `type` varchar(24) NOT NULL COMMENT 'LOCAL/SINGLE_NODE/REMOTE_CLUSTER',
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/TESTING/ACTIVE/DISABLED',
  `landing_uri` varchar(255) DEFAULT NULL COMMENT 'file:/// 或 hdfs:// 落地基础路径',
  `hdfs_uri` varchar(255) DEFAULT NULL COMMENT 'HDFS NameNode URI（REMOTE_CLUSTER）',
  `hive_jdbc_url` varchar(255) DEFAULT NULL COMMENT 'Hive JDBC（SELECT 1 连通测试）',
  `hive_database_prefix` varchar(64) DEFAULT NULL COMMENT '数仓库名前缀，如 dw_',
  `spark_master` varchar(128) DEFAULT NULL,
  `deploy_mode` varchar(32) DEFAULT NULL,
  `yarn_queue` varchar(64) DEFAULT NULL,
  `ssh_host` varchar(128) DEFAULT NULL COMMENT 'REMOTE_CLUSTER 必需的 SSH 字段',
  `ssh_port` int DEFAULT NULL,
  `ssh_user` varchar(64) DEFAULT NULL,
  `spark_submit_path` varchar(255) DEFAULT NULL COMMENT 'spark-submit 脚本路径',
  `spark_job_jar_uri` varchar(255) DEFAULT NULL COMMENT 'spark-jobs jar 位置',
  `metric_store_type` varchar(24) NOT NULL DEFAULT 'MYSQL' COMMENT 'MYSQL；第二阶段 DORIS',
  `metric_store_config_ref` varchar(255) DEFAULT NULL COMMENT '指标库配置引用（凭据引用）',
  `credential_ref` varchar(255) DEFAULT NULL COMMENT '凭据引用（密钥库 Key，不存明文）',
  `timezone` varchar(32) NOT NULL DEFAULT 'Asia/Shanghai',
  `version` int NOT NULL DEFAULT '1' COMMENT '环境配置版本（§8.3 激活递增）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `source_id` bigint DEFAULT NULL COMMENT '所属数据源（source_registry.id）；兼容期可空，启动流水线时必须非空',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profile_code` (`profile_code`),
  KEY `idx_runtime_profile_source` (`source_id`),
  CONSTRAINT `fk_runtime_profile_source` FOREIGN KEY (`source_id`) REFERENCES `source_registry` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运行环境档案';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `source_registry`
--

DROP TABLE IF EXISTS `source_registry`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `source_registry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_code` varchar(64) NOT NULL COMMENT '源业务键，写入 ODS 的 source_system；[a-z][a-z0-9-]{1,63}，创建后不可改名',
  `display_name` varchar(128) NOT NULL COMMENT '看板/页面展示名',
  `ingest_mode` varchar(16) NOT NULL COMMENT '接入方式：本期唯一实现 FILE；JDBC/HTTP 为能力状态（预留）',
  `profile_path` varchar(255) NOT NULL COMMENT '源画像文件仓库相对路径（设计 §4.2），禁止 .. 与绝对路径',
  `timezone` varchar(64) NOT NULL COMMENT '源所在时区（IANA）',
  `currency` char(3) NOT NULL COMMENT '源记账币种（ISO 4217）',
  `status` varchar(16) NOT NULL COMMENT 'DRAFT/ACTIVE/PAUSED/DISABLED',
  `profile_version` varchar(32) NOT NULL COMMENT '与源画像文件内的 profileVersion 一致',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_registry_code` (`source_code`),
  KEY `idx_source_registry_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='被分析源（商城）登记：长期身份与词汇表位置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `spark_job_run`
--

DROP TABLE IF EXISTS `spark_job_run`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `spark_job_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `runtime_profile_id` bigint NOT NULL COMMENT '实际运行环境（§8.1）',
  `runtime_profile_version` int NOT NULL DEFAULT '1' COMMENT '实际环境配置版本（§8.1）',
  `pipeline_run_id` bigint NOT NULL,
  `stage_code` varchar(32) NOT NULL COMMENT 'LOAD_ODS/BUILD_DWD/...',
  `job_code` varchar(32) NOT NULL COMMENT 'odl/bdw/usw/fna 等（spark-jobs 契约）',
  `external_job_id` varchar(128) DEFAULT NULL COMMENT '提交后返回的任务 ID（非空即提交成功）',
  `submitter_type` varchar(32) NOT NULL COMMENT 'LOCAL_PROCESS/SSH',
  `arguments_json` varchar(2000) DEFAULT NULL COMMENT '提交参数快照（可复现）',
  `status` varchar(24) NOT NULL COMMENT 'SUBMITTED/RUNNING/SUCCESS/FAILED/CANCELLED',
  `input_records` bigint NOT NULL DEFAULT '0',
  `output_records` bigint NOT NULL DEFAULT '0',
  `rejected_records` bigint NOT NULL DEFAULT '0',
  `output_partitions_json` longtext COMMENT 'R6-12 输出分区证据 [{table,dt,snapshotId,rowCount,path}]',
  `log_uri` varchar(500) DEFAULT NULL COMMENT 'stdout/stderr 日志位置',
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_spark_job_pipeline` (`pipeline_run_id`,`stage_code`)
) ENGINE=InnoDB AUTO_INCREMENT=206 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Spark 作业运行记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password_hash` varchar(128) NOT NULL,
  `real_name` varchar(64) NOT NULL DEFAULT '',
  `role` varchar(32) NOT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户（§21.5）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_ckpt_test`
--

DROP TABLE IF EXISTS `t_ckpt_test`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_ckpt_test` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `file_path` varchar(500) NOT NULL COMMENT '相对 landing 根的文件路径',
  `next_offset` bigint NOT NULL DEFAULT '0' COMMENT '下一个待读字节偏移',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `runtime_profile_id` bigint NOT NULL DEFAULT '1' COMMENT '运行环境',
  `file_identity` varchar(64) NOT NULL DEFAULT '' COMMENT '文件身份',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ckpt` (`runtime_profile_id`,`file_path`,`file_identity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采集断点';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_session`
--

DROP TABLE IF EXISTS `user_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `token` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `expires_at` datetime NOT NULL,
  `login_ip` varchar(64) NOT NULL DEFAULT '',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `token` (`token`),
  KEY `idx_session_user` (`user_id`),
  CONSTRAINT `fk_session_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=161 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录会话（简易 token，24h 过期）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-12  9:14:51
