-- R6-12（V2.0 §15.3）：spark_job_run 增加输出分区证据列。
-- 每个 Spark 作业必须能回答"写了哪些表/dt 分区、多少行、落在哪个路径"，
-- 证据数组由 spark-jobs JobResult.outputPartitions 提供（真实 COUNT(*) + Hive 元数据 Location），
-- 缺失该证据的作业不得视为完成。列宽取 LONGTEXT（单作业多表多分区，远超行内其它字段）。
ALTER TABLE spark_job_run
    ADD COLUMN output_partitions_json LONGTEXT NULL
        COMMENT 'R6-12 输出分区证据 [{table,dt,snapshotId,rowCount,path}]'
        AFTER rejected_records;
