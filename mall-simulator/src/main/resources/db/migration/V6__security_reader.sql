-- V6: 数据库只读账号（§8.6 第四层防线）· AI 执行器专用
-- 密码经 Flyway placeholder 注入（spring.flyway.placeholders.mall.reader.password），
-- 生产环境必须通过环境变量 MALL_READER_PASSWORD 覆盖默认值。
CREATE USER IF NOT EXISTS '${mall.reader.user}'@'localhost' IDENTIFIED BY '${mall.reader.password}';
GRANT SELECT ON mall_simulator.* TO '${mall.reader.user}'@'localhost';
GRANT SELECT ON mall_simulator_test.* TO '${mall.reader.user}'@'localhost';