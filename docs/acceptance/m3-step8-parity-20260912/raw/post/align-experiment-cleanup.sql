-- P2-03-m2 收尾：确认一次性实验库/表已清除（恢复原状）
SHOW DATABASES LIKE 'dw_exp';
DROP TABLE IF EXISTS dw_exp.t_align_bad;
DROP DATABASE IF EXISTS dw_exp;
SHOW DATABASES LIKE 'dw_exp';
