-- =====================================================================
-- M2 S2-04B Landing 输入布局（设计 §8.2 规则 1/3/4、§8.3/§8.4）
--
-- runtime_profile.landing_layout：采集端到 landing 根下的**哪一个子目录、以什么方式枚举输入**。
--   ROLLING_LOG：<landing>/events 下的一层滚动日志（*.jsonl + 断点续读 + 残行等待，V2 起的既有语义）
--   FLUME_RAW  ：<landing>/raw 下按源级 ingest 时间分区的**已完成文件**（递归，dt=/hour= 是目录层级）
--
-- 为什么是 runtime_profile 的新列而不是新表：
--   "落在哪"（landing_uri）与"目录/文件怎么摆"是同一件事的两半，都属于**运行环境参数**
--   （设计 §9.1/§8.3：landing 与采集参数登记在运行档案上），消费者只有采集端一个读口
--   （IngestionService）与状态口（status）。单开一张"布局表"会为一列造一个 join 与一个所有者。
--
-- 与 landing_uri 的分工（不得合并）：landing_uri 回答"存储位置"（file:/// 或 hdfs://），
--   本列回答"枚举语义"。同一个 landing 根下既可能有滚动日志区，也可能有 Flume 目标区。
--
-- 本迁移是**加性**的：只加一列，可空、无默认值、不插入任何行、不改任何已有列的定义与语义。
--   · 可空是必须的：存量行在 V22 之前没有"布局"这个事实，回填成 ROLLING_LOG 会把
--     "从未配置"与"显式配置成默认"混成一种表示；空值在读取侧等价于 ROLLING_LOG
--     （LandingLayout.effective），写入侧空值归一为 NULL（RuntimeProfileServiceImpl）。
--   · 无 DEFAULT：默认值属于代码侧的"未配置⇒默认布局"口径，写进 DDL 会让两者有了两个所有者。
--   · 值域由代码侧唯一所有者 LandingLayout 校验（未登记值 PARAM_INVALID 拒绝，不回落默认），
--     故此处不加 CHECK 约束：加 CHECK 会让"新增第三种布局"必须动一次已发布迁移（真决策门）。
--   回退方式（仅供人工按需执行，本迁移不执行）：ALTER TABLE runtime_profile DROP COLUMN landing_layout;
--     该列只是采集枚举方式的选择，删列不损失任何业务数据（批次账/清单/断点都在别处）。
-- =====================================================================

ALTER TABLE runtime_profile
    ADD COLUMN landing_layout VARCHAR(32) NULL
        COMMENT 'Landing 输入布局：ROLLING_LOG(默认,空值等价)/FLUME_RAW；值域由 LandingLayout 校验'
        AFTER landing_uri;
