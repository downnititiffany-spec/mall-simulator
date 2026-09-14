-- V25-E3 隔离准备（唯一写入目标：127.0.0.1:3307 的隔离 meta 库）
-- 目的：迁移 V7 播下的 local-dev profile 是 DRAFT 且 spark_* 为空；采集侧 source 解析要求
--       存在 ACTIVE 的 runtime_profile（RuntimeProfileServiceImpl.findActive），否则 fail-closed。
-- 做法：把宿主 3306 上 **ACTIVE** 那个 profile 的编排字段**镜像**到隔离库（只读 3306，写 3307）。
-- 说明：这不是"改小应用"，只是把隔离实例的种子行补成与正式库等价的运行环境配置；
--       jar 路径指向本仓库 target 下的新构建产物（sha256 见 README）。

UPDATE analytics_meta_v25it_20260914_1358_l4e3.runtime_profile
   SET status             = 'ACTIVE',
       spark_master       = 'local[2]',
       spark_submit_path  = 'D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd',
       spark_job_jar_uri  = 'D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar',
       source_id          = 1,
       version            = 3,
       updated_at         = NOW(6)
 WHERE id = 1
   AND profile_code = 'local-dev';

SELECT id, profile_code, status, landing_uri, spark_master, spark_submit_path, spark_job_jar_uri, source_id, version
  FROM analytics_meta_v25it_20260914_1358_l4e3.runtime_profile;

SELECT COUNT(*) AS active_profile_rows
  FROM analytics_meta_v25it_20260914_1358_l4e3.runtime_profile WHERE status = 'ACTIVE';
