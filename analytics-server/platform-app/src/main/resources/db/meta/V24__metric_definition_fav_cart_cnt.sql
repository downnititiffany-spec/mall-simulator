-- S3-08（设计 §11.2 L425；字典 `docs/contracts/metric-dictionary.md:21/:22`）
-- 本迁移只往指标字典 `metric_definition` **追加两行**收藏/加购次数，不 DROP、不改既有行、不动其它表。
--
-- 背景：这两枚码从字典建立之初（V2 的 15 行种子）就登记在 `docs/contracts/metric-dictionary.md` 里，
-- 但**没有对应的 analytics_meta 种子行**，因此发布侧 `MP_METRIC_DICT_VERSION` 会直接拒绝它们
-- （该规则要求每个待写指标码都能在 `request.definitionVersions()` 里找到定义版本，
-- 而后者由 `metric_definition` 读出）—— 也就是说：只把列加进 ADS 而不补字典行，指标永远发不出去。
-- 这与 V13 追加 `full_refund_rate` 字典行是同一类补齐动作（新码 → 新加性迁移）。
--
-- 口径逐字取自字典（不得在此处另写一套）：count(favorite 事件) / count(cart_add 事件)，
-- 粒度 day、时间字段 event_time、单位「次」、版本 v1（首次落地即 v1，此后任何口径变更必须升版本）。
--
-- 幂等：用 INSERT ... WHERE NOT EXISTS（与 V13 同型）；若该码已存在（例如手工补过），本迁移什么都不做，
-- 绝不覆盖既有 formula/definition_version —— 覆盖等于在迁移里悄悄改口径，属治理门 ②。
INSERT INTO metric_definition (metric_code, metric_name, formula, grain, default_time_field, unit, definition_version)
SELECT 'fav_cnt', '收藏次数', 'count(favorite 事件)', 'day', 'event_time', '次', 'v1'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE metric_code = 'fav_cnt');

INSERT INTO metric_definition (metric_code, metric_name, formula, grain, default_time_field, unit, definition_version)
SELECT 'cart_add_cnt', '加购次数', 'count(cart_add 事件)', 'day', 'event_time', '次', 'v1'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE metric_code = 'cart_add_cnt');
