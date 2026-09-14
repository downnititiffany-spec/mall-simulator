package com.graduation.mall.auth;

import com.graduation.mall.auth.AuthService.LoginResult;
import com.graduation.mall.common.MallBizException;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证服务测试（§21.5 会话）：正确密码登录取 token、错误密码拒绝、
 * 登出失效、随机 token 无效、三种子角色均可登录。
 */
// 分类标记（V25-S02 / K-02）：本类需要真实数据库隔离实例（3307）。
//   * 默认纯测试套件（mvn test）按 pom 的 <excludedGroups>it</excludedGroups> 不选中本类；
//   * 显式集成套件（mvn test -Pisolated-tests）选中本类，缺隔离档案时**硬拒（红）而非 skip**。
@Tag("it")
class AuthServiceTest extends MallTestSupport {

    @Autowired
    private AuthService authService;

    @Test
    @DisplayName("正确密码登录成功并返回 token 与角色")
    void loginSuccess() {
        LoginResult r = authService.login("admin", "admin123", "127.0.0.1");
        assertNotNull(r.token());
        assertTrue(r.token().length() >= 32, "UUID token 应存在");
        assertEquals("admin", r.user().role());
        CurrentUser current = authService.validate(r.token());
        assertNotNull(current, "token 应可解析");
        assertEquals("admin", current.role());
    }

    @Test
    @DisplayName("错误密码与不存在用户均被拒绝（BAD_CREDENTIALS）")
    void loginRejected() {
        MallBizException wrongPwd = assertThrows(MallBizException.class,
                () -> authService.login("admin", "wrong-password", "127.0.0.1"));
        assertEquals(AuthService.BAD_CREDENTIALS, wrongPwd.getCode());
        MallBizException noUser = assertThrows(MallBizException.class,
                () -> authService.login("nobody", "x", "127.0.0.1"));
        assertEquals(AuthService.BAD_CREDENTIALS, noUser.getCode());
    }

    @Test
    @DisplayName("登出后 token 立即失效")
    void logoutInvalidatesToken() {
        LoginResult r = authService.login("operator", "operator123", "127.0.0.1");
        authService.logout(r.token());
        assertNull(authService.validate(r.token()), "登出后 token 必须失效");
    }

    @Test
    @DisplayName("随机会话 token 无效")
    void randomTokenInvalid() {
        assertNull(authService.validate("totally-random-token"));
    }

    @Test
    @DisplayName("三种种子角色均可登录（BCrypt 匹配）")
    void allSeedRolesLogin() {
        for (String role : new String[]{"admin", "operator", "analyst"}) {
            LoginResult r = authService.login(role, role + "123", "127.0.0.1");
            assertEquals(role, r.user().role());
            assertNotNull(authService.validate(r.token()));
        }
    }
}
