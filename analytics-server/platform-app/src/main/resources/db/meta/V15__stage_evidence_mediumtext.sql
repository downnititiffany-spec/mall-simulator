-- =====================================================================
-- R8 修复：阶段证据列再加宽（4000 → MEDIUMTEXT，2026-09-11 真机事故）
-- 背景：V9 把 evidence 从 VARCHAR(500) 加宽到 VARCHAR(4000)，并把「超长截断」写进
-- 代码兜底。但真机跑真实链路时 BUILD_ADS 证据（逐作业 × 逐输出分区：表名/dt/快照/行数/
-- 路径）轻易超过 4000 字符，截断发生在**字符串中间** → 存进去的是**非法 JSON**；
-- 重试/恢复路径 stageEvidence() 解析失败后静默退化成空 Map →
-- PUBLISH_METRIC 报「缺少 BUILD_ADS 真实作业证据，拒绝发布」（实测 pipeline run 22）。
-- 处理：列型改为 MEDIUMTEXT（16MB，本项目证据量级 10^4 字符，实际不会再截断）；
-- 代码侧同时把「超长兜底」改成**保持 JSON 合法**的结构化缩减（PipelineService.evidenceJson）。
-- =====================================================================

ALTER TABLE pipeline_stage_run
    MODIFY COLUMN evidence MEDIUMTEXT NULL COMMENT '阶段证据 JSON（批次/作业明细/输出分区/契约口径等；超长时结构化缩减保持 JSON 合法）';
