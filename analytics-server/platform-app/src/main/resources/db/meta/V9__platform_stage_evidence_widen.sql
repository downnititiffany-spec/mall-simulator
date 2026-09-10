-- =====================================================================
-- R6-11 修复：阶段证据列加宽（V2.0 §15.3 R6-12 证据可溯源）
-- 背景：V8 建 evidence VARCHAR(500)，只够 WAIT_LANDING 的批次摘要；
-- R6-11 起每个 Spark 阶段的证据含逐作业明细（jobCode/externalJobId/输入输出/日志位置），
-- 3 作业阶段 JSON 超过 500 字节 → MysqlDataTruncation → 阶段落库失败（实测 run 12）。
-- 处理：加宽到 4000（同时代码侧保留截断兜底，避免超长 JSON 把阶段写成 FAILED）。
-- =====================================================================

ALTER TABLE pipeline_stage_run
    MODIFY COLUMN evidence VARCHAR(4000) NULL COMMENT '阶段证据 JSON（批次/作业明细/契约口径等）';
