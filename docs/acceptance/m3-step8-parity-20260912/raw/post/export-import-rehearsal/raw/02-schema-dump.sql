-- MySQL dump 10.13  Distrib 8.0.41, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: analytics_metric
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
-- Table structure for table `ads_operation_overview_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_operation_overview_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `pv` bigint NOT NULL DEFAULT '0',
  `uv` bigint NOT NULL DEFAULT '0',
  `dau` bigint NOT NULL DEFAULT '0',
  `order_count` bigint NOT NULL DEFAULT '0',
  `sale_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `net_sale_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `avg_order_value` decimal(18,2) DEFAULT NULL,
  `refund_rate` decimal(8,4) DEFAULT NULL,
  `full_refund_rate` decimal(8,4) DEFAULT NULL,
  PRIMARY KEY (`snapshot_id`,`dt`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 运营大盘（快照发布后写入）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ads_sale_trend_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_sale_trend_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `order_count` bigint NOT NULL DEFAULT '0',
  `buyer_count` bigint NOT NULL DEFAULT '0',
  `sale_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `avg_order_value` decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`snapshot_id`,`dt`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 销售趋势';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ads_behavior_funnel_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_behavior_funnel_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `stage` varchar(16) NOT NULL COMMENT 'view/intent/order/pay',
  `user_count` bigint NOT NULL DEFAULT '0',
  `conversion_rate` decimal(8,4) DEFAULT NULL COMMENT '相对上一阶段转化率',
  `overall_buy_rate` decimal(8,4) DEFAULT NULL COMMENT '相对首阶段累计购买率',
  PRIMARY KEY (`snapshot_id`,`dt`,`stage`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 行为转化漏斗';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ads_active_trend_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_active_trend_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `dau` bigint NOT NULL DEFAULT '0',
  `behavior_count` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`snapshot_id`,`dt`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 日活跃趋势';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ads_hot_product_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_hot_product_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `product_id` bigint NOT NULL,
  `product_name` varchar(200) NOT NULL DEFAULT '',
  `heat_score` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `pv` bigint NOT NULL DEFAULT '0',
  `fav` bigint NOT NULL DEFAULT '0',
  `cart` bigint NOT NULL DEFAULT '0',
  `buy` bigint NOT NULL DEFAULT '0',
  `rank_no` int NOT NULL,
  PRIMARY KEY (`snapshot_id`,`dt`,`rank_no`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 热门商品热度榜';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ads_product_conversion_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_product_conversion_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `product_id` bigint NOT NULL,
  `pv_users` bigint NOT NULL DEFAULT '0',
  `buy_users` bigint NOT NULL DEFAULT '0',
  `conversion_rate` decimal(8,4) DEFAULT NULL,
  PRIMARY KEY (`snapshot_id`,`dt`,`product_id`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 商品转化';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ads_user_profile_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_user_profile_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `user_id` bigint NOT NULL,
  `r` int NOT NULL DEFAULT '0' COMMENT 'R 分（1..5）',
  `f` int NOT NULL DEFAULT '0' COMMENT 'F 分（1..5）',
  `m` int NOT NULL DEFAULT '0' COMMENT 'M 分（1..5）',
  `value_group` varchar(32) NOT NULL DEFAULT '' COMMENT 'RFM 八类价值分组',
  `active_level` varchar(32) NOT NULL DEFAULT '',
  `favorite_category` bigint NOT NULL DEFAULT '0' COMMENT '偏好分类 id',
  `last_active_date` varchar(32) NOT NULL DEFAULT '',
  `last_buy_date` varchar(32) NOT NULL DEFAULT '',
  `lifecycle_state` varchar(32) NOT NULL DEFAULT '' COMMENT '活跃/沉默/流失风险',
  `rule_version` varchar(32) NOT NULL DEFAULT '',
  `calc_date` varchar(32) NOT NULL DEFAULT '',
  PRIMARY KEY (`snapshot_id`,`dt`,`user_id`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 用户画像（RFM 分层）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ads_data_quality_m`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ads_data_quality_m` (
  `snapshot_id` varchar(64) NOT NULL,
  `dt` varchar(16) NOT NULL,
  `rule_code` varchar(64) NOT NULL,
  `check_count` bigint NOT NULL DEFAULT '0',
  `error_count` bigint NOT NULL DEFAULT '0',
  `error_rate` decimal(12,6) NOT NULL DEFAULT '0.000000',
  `passed` int NOT NULL DEFAULT '0' COMMENT '1=通过 0=未通过',
  `threshold` varchar(200) NOT NULL DEFAULT '',
  PRIMARY KEY (`snapshot_id`,`dt`,`rule_code`),
  KEY `idx_dt` (`dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 数据质量结果';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `metric_snapshot`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `metric_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `snapshot_id` varchar(64) NOT NULL COMMENT '快照发布号（唯一）',
  `runtime_profile_id` bigint NOT NULL DEFAULT '1' COMMENT '运行环境；同 profile 最多一个 ACTIVE',
  `runtime_profile_version` int DEFAULT NULL COMMENT '§8.1 实际 profile 版本',
  `business_time` datetime(3) NOT NULL COMMENT '快照业务时间',
  `pipeline_run_id` bigint DEFAULT NULL COMMENT '来源流水线实例',
  `status` varchar(24) NOT NULL COMMENT 'BUILDING/VERIFYING/ACTIVE/ARCHIVED/FAILED',
  `version` int NOT NULL DEFAULT '1' COMMENT '同一业务时间的第 N 次发布',
  `definition_version` varchar(16) NOT NULL DEFAULT '' COMMENT '本次快照使用的指标口径版本',
  `data_updated_at` datetime(3) NOT NULL COMMENT 'ADS 数据时间',
  `published_at` datetime(3) DEFAULT NULL COMMENT '切换为 ACTIVE 的时间',
  `source` varchar(32) NOT NULL DEFAULT 'spark-ads' COMMENT '§17.6 成功快照只接受 spark-ads',
  `failure_reason` varchar(512) DEFAULT NULL COMMENT 'FAILED 原因（§17.5 第 6 步）',
  `active_flag` tinyint DEFAULT NULL COMMENT 'ACTIVE=1，其它状态=NULL：可空唯一列实现同 profile 唯一 ACTIVE',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
  UNIQUE KEY `uk_active_profile` (`runtime_profile_id`,`active_flag`),
  KEY `idx_snapshot_status` (`runtime_profile_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标快照（唯一所有者 analytics_metric）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `metric_value`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `metric_value` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `snapshot_id` varchar(64) NOT NULL,
  `metric_code` varchar(64) NOT NULL,
  `metric_value` decimal(18,4) NOT NULL,
  `unit` varchar(16) NOT NULL DEFAULT '',
  `period` varchar(32) NOT NULL DEFAULT '' COMMENT 'day:2026-09-01',
  `dimension_json` varchar(512) DEFAULT NULL COMMENT '维度原始 JSON（可空）',
  `dimension_key` varchar(255) DEFAULT NULL COMMENT '规范化维度串（无维度写空串，参与唯一键；NULL 不参与唯一约束）',
  `definition_version` varchar(16) NOT NULL DEFAULT '',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_metric_period_dim` (`snapshot_id`,`metric_code`,`period`,`dimension_key`)
) ENGINE=InnoDB AUTO_INCREMENT=151 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='快照指标值';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-12 22:23:53
