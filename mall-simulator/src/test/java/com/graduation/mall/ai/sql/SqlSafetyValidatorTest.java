package com.graduation.mall.ai.sql;

import com.graduation.mall.ai.SemanticCatalog;
import com.graduation.mall.ai.sql.SqlSafetyValidator.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 安全校验测试（§8.6/§10.5 安全测试集）：危险 SQL 100% 拦截，合法查询不被误拦截。
 */
class SqlSafetyValidatorTest {

    private final SqlSafetyValidator validator = new SqlSafetyValidator(new SemanticCatalog());

    private void assertBlocked(String sql, String hint) {
        ValidationResult r = validator.validate(sql);
        assertFalse(r.ok(), "应拦截: " + hint + " -> " + sql);
    }

    @Test
    @DisplayName("合法白名单查询通过且自动补 LIMIT")
    void validQueryPasses() {
        ValidationResult r = validator.validate(
                "SELECT dt, sale_amount, order_count FROM ads_sale_trend_m WHERE dt = '2026-09-04'");
        assertTrue(r.ok());
        assertTrue(r.sql().toLowerCase().contains("limit 500"), "无 LIMIT 时自动追加");
    }

    @Test
    @DisplayName("数据修改/删除类全部拦截")
    void dmlBlocked() {
        assertBlocked("DROP TABLE ads_sale_trend_m", "drop");
        assertBlocked("DELETE FROM ads_sale_trend_m", "delete");
        assertBlocked("UPDATE ads_sale_trend_m SET sale_amount=0", "update");
        assertBlocked("INSERT INTO ads_sale_trend_m VALUES (1)", "insert");
        assertBlocked("TRUNCATE TABLE ads_sale_trend_m", "truncate");
    }

    @Test
    @DisplayName("注入/逃逸技术全部拦截")
    void injectionBlocked() {
        assertBlocked("SELECT dt FROM ads_sale_trend_m; DROP TABLE x;", "多语句");
        assertBlocked("SELECT dt FROM ads_sale_trend_m /* comment */", "注释");
        assertBlocked("SELECT dt FROM ads_sale_trend_m -- comment", "行注释");
        assertBlocked("SELECT dt FROM ads_sale_trend_m UNION SELECT 1,2,3", "union");
    }

    @Test
    @DisplayName("越权/非白名单访问拦截")
    void privilegeBlocked() {
        assertBlocked("SELECT * FROM information_schema.tables", "系统库");
        assertBlocked("SELECT * FROM mysql.user", "mysql 库");
        assertBlocked("SELECT * FROM dw_ads.ads_sale_trend", "非白名单表");
        assertBlocked("SELECT * FROM mall_user", "业务明细表");
    }

    @Test
    @DisplayName("字段与函数白名单")
    void fieldAndFunctionBlocked() {
        assertBlocked("SELECT nonexistent_column FROM ads_sale_trend_m", "不存在字段");
        assertBlocked("SELECT dt, SLEEP(5) FROM ads_sale_trend_m", "sleep 函数");
        assertBlocked("SELECT LOAD_FILE('/etc/passwd') FROM ads_sale_trend_m", "load_file");
    }

    @Test
    @DisplayName("LIMIT 上限强制（>1000 截断；500-1000 保留）")
    void limitEnforced() {
        ValidationResult big = validator.validate(
                "SELECT dt FROM ads_sale_trend_m WHERE dt = '2026-09-04' LIMIT 5000");
        assertTrue(big.ok());
        assertTrue(big.sql().toLowerCase().contains("limit 1000"), "超限截断到 1000");

        ValidationResult ok = validator.validate(
                "SELECT dt FROM ads_sale_trend_m WHERE dt = '2026-09-04' LIMIT 500");
        assertTrue(ok.ok());
        assertTrue(ok.sql().toLowerCase().contains("limit 500"));
    }

    @Test
    @DisplayName("时间条件约束：无时间范围的查询被拒绝（口径要求）")
    void missingTimeRangeBlocked() {
        assertBlocked("SELECT dt FROM ads_sale_trend_m", "无时间条件");
    }

    @Test
    @DisplayName("SQL 解析失败拒绝")
    void parseFailureBlocked() {
        assertBlocked("SELEC dt FORM ads_sale_trend_m", "语法错误");
    }
}