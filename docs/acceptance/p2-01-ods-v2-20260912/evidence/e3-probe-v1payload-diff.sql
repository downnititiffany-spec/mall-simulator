-- E3 探针（只读）：定位 C2_v1_payload_mismatch=4 的真实原因
-- 用法（实测命令见 e3-probe-v1payload-diff.log 首行）：
--   spark-sql --master local[2] --conf ...<run3 的临时 derby/warehouse>... -f evidence\e3-probe-v1payload-diff.sql
-- 说明：只做 SELECT + 会话级 TEMPORARY VIEW，不写任何库表。
CREATE OR REPLACE TEMPORARY VIEW raw_text USING text OPTIONS (path 'file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events');

SELECT 'P_user' AS chk, o.event_id AS id,
       o.payload_user_id AS col_user_id, get_json_object(t.value, '$.payload.user_id') AS ora_user_id,
       o.payload_age_group AS col_age, get_json_object(t.value, '$.payload.age_group') AS ora_age,
       o.payload_city_level AS col_city, get_json_object(t.value, '$.payload.city_level') AS ora_city,
       o.payload_member_level AS col_member, get_json_object(t.value, '$.payload.member_level') AS ora_member,
       o.payload_register_time AS col_reg, get_json_object(t.value, '$.payload.register_time') AS ora_reg
  FROM p201v2_ods.ods_user_event o
  JOIN raw_text t ON get_json_object(t.value, '$.event_id') = o.event_id
 ORDER BY o.event_id;

SELECT 'P_payload' AS chk, event_id AS id, payload_json AS pj
  FROM p201v2_ods.ods_user_event ORDER BY event_id;
