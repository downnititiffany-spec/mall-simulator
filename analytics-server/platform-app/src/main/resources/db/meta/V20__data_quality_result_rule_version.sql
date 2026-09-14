-- =====================================================================
-- V20: 质量结果行携带「本条结果是按哪一版规则算出来的」（F-88 / V25-Q01；§7.3.1 line 520）
--
-- 改什么：`data_quality_result` 新增 4 列 —— 把「规则版本 / 生效档位 / 兼容策略版本 /
--   规则集指纹」写到**结果行自己身上**。
--
-- 为什么必须写在结果行上（而不是只写在 V19 的定义表里）：
--   已登记缺陷 F-93 —— `qualityStatus` 在**读时**用当前代码重算历史 run 的结论，
--   于是「历史 run 当时是怎么判的」会随代码改动被追溯改写，无法复算。
--   要能回答「run 47 的 EVENT_ID_UNIQUE 当时按哪一版、什么档位判的」，
--   版本信息必须与结果行同行落库，不能只靠「去定义表里猜当时哪一版生效」。
--
-- 四列各自的语义：
--   `rule_version`           本行结果所用规则的版本号（对应 V19 `quality_rule_definition.version`）
--   `effective_severity`     本行结果**实际生效**的档位。与既有 `severity` 列**不是一回事**：
--                            既有 `severity` 是规则**声明**的档位；
--                            `effective_severity` 是经 `RuleSeverity.resolve` 条件判定后的档位
--                            （例：`EVENT_ID_UNIQUE` 声明 WARN，超阈值时**生效** BLOCKING）。
--                            两列并存才能事后区分「声明 WARN 但生效阻断」与「本来就是阻断」。
--   `compat_policy_version`  本次判定所用的兼容策略版本（目录常量 `compat-v1`）。
--                            存在的意义：将来若放宽兼容策略，历史行仍能指出「当初按哪版策略判的」。
--   `rule_fingerprint`       本次 run 冻结的**整个规则集**的指纹（64 位十六进制小写）。
--                            粒度是「run 冻结的那一组规则」，不是单条规则 —— 单条规则的指纹在
--                            V19 的 `checksum` 列。指纹相同 ⇒ 两次判定用的是同一套口径。
--
-- 【最关键的一条约束，不得被后续修改破坏】
--   四列**全部可空、默认 NULL，且本迁移不回填任何既有行**。
--   本表在版本化引入之前产出的历史行（本机实测 467 行，其中 48 行连 `severity` 都是 NULL）
--   **本来就没有版本信息**。任何回填都只能是猜测，而猜测会被下游读成事实。
--   ⇒ 按总控裁决：**严禁回填任何猜测值**；历史行为 NULL，NULL 的正确含义是
--      「本列为版本化引入前记录，无版本信息，不得解读为 WARN/PASS」。
--   ⇒ 也**不设 DEFAULT**：若设 `DEFAULT 1`，新写入若是漏填，会被静默伪装成「v1 算的」，
--      正是本列要防的「无法区分漏填与真值」。缺省必须由写侧显式给出。
--
-- 为什么是 4 列一次加、而不是拆成 4 个迁移：§7.3.1 line 520 把这四项列为**同一件事**
--   （「冻结完整规则版本与指纹」）的四个字段，且都只服务于同一个查询需求；
--   拆开会产生三类「加了一部分列」的中间态，每个中间态都让结果行处于半可解释状态。
--   （总控「一次迁移一件事」的要求针对的是**不同表/不同变更**：V19 建定义表与 V20 增结果列
--    是两件事，故分两个文件；本文件内 4 列是同一件事。）
--
-- 本迁移只 **加 4 个可空列**，不建表、不改既有列定义、不删列、不删行、不改任何索引。
--
-- 【本迁移在真库上的执行状态：未执行】
--   按本泳道硬约束（DB 冻结：不起停服务、不对 3306 执行任何 DDL/DML），本脚本
--   **只在仓库存档**，将在下次平台（8091）启动时由 `MetaFlywayInitializer`
--   （`classpath:db/meta`）对真实 `analytics_meta` 执行。本轮**未在 3306 上执行**。
--   3306 只做过只读 `SELECT` 取证（含 `information_schema.COLUMNS` 确认这 4 列当前不存在）。
--
-- 前向效应（必须记账）：本迁移落地后，写侧必须开始填这 4 列；在写侧改造完成**之前**，
--   新产出的行同样是 NULL —— 那些 NULL 的含义是「写侧尚未接入版本化」，与历史 NULL
--   含义不同。**该写侧接入不在本迁移范围内，属未完成项，不得声称「版本化已闭合」。**
--
-- 回退方式（仅供人工按需执行，本迁移不执行）：
--   `ALTER TABLE data_quality_result DROP COLUMN rule_fingerprint, DROP COLUMN compat_policy_version,
--    DROP COLUMN effective_severity, DROP COLUMN rule_version;`
--   —— 只删本轮新增列，不含业务事实数据。
-- =====================================================================

ALTER TABLE data_quality_result
    ADD COLUMN rule_version INT NULL
        COMMENT '本行结果所用规则版本（对应 quality_rule_definition.version）。本列为版本化引入前记录，无版本信息，不得解读为 WARN/PASS；不设 DEFAULT —— 漏填必须显式可见',
    ADD COLUMN effective_severity VARCHAR(16) NULL
        COMMENT '本行结果实际生效档位（经 RuleSeverity.resolve 条件判定后）。与既有 severity（规则声明档位）不同：声明 WARN 超阈值时生效 BLOCKING。本列为版本化引入前记录，无版本信息，不得解读为 WARN/PASS',
    ADD COLUMN compat_policy_version VARCHAR(32) NULL
        COMMENT '本次判定所用兼容策略版本（目录常量 compat-v1）。本列为版本化引入前记录，无版本信息，不得解读为 WARN/PASS',
    ADD COLUMN rule_fingerprint CHAR(64) NULL
        COMMENT '本次 run 冻结的整个规则集指纹（SHA-256 小写十六进制）。单条规则指纹见 quality_rule_definition.checksum。本列为版本化引入前记录，无版本信息，不得解读为 WARN/PASS';
