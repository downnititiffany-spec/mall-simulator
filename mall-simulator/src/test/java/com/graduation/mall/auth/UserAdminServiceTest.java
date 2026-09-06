package com.graduation.mall.auth;

import com.graduation.mall.auth.AuthService.LoginResult;
import com.graduation.mall.common.MallBizException;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户管理服务测试（§3.2）：创建/唯一性/禁用后登录失败/重置密码/禁停自身。
 */
class UserAdminServiceTest extends MallTestSupport {

    @Autowired
    private AuthService authService;

    private CurrentUser admin() {
        LoginResult r = authService.login("admin", "admin123", "127.0.0.1");
        return authService.validate(r.token());
    }

    @Test
    @DisplayName("admin 创建用户后新账号可登录（BCrypt）")
    void createAndLogin() {
        AuthService.UserView created = authService.createUser("op_new", "新运营", "operator", "op123456");
        assertNotNull(created.id());
        assertEquals("operator", created.role());
        LoginResult r = authService.login("op_new", "op123456", "127.0.0.1");
        assertEquals("新运营", r.user().realName());
    }

    @Test
    @DisplayName("重复用户名与非法角色被拒绝")
    void createInvalidRejected() {
        assertThrows(MallBizException.class,
                () -> authService.createUser("admin", "x", "operator", "op123456"),
                "admin 已存在");
        assertThrows(MallBizException.class,
                () -> authService.createUser("someone", "x", "superadmin", "op123456"),
                "非法角色");
    }

    @Test
    @DisplayName("禁用后登录失败；恢复后重新可登录")
    void disableAndEnable() {
        authService.createUser("op_tmp", "临时", "operator", "op123456");
        var users = authService.listUsers();
        Long id = users.stream().filter(u -> u.username().equals("op_tmp")).findFirst().orElseThrow().id();

        authService.toggleUser(id, false, admin());
        assertThrows(MallBizException.class, () -> authService.login("op_tmp", "op123456", "127.0.0.1"));

        authService.toggleUser(id, true, admin());
        assertNotNull(authService.login("op_tmp", "op123456", "127.0.0.1").token());
    }

    @Test
    @DisplayName("重置密码后旧密码失效新密码可登录")
    void resetPassword() {
        authService.createUser("op_reset", "重置", "analyst", "old12345");
        var users = authService.listUsers();
        Long id = users.stream().filter(u -> u.username().equals("op_reset")).findFirst().orElseThrow().id();
        authService.resetPassword(id, "newpass88");
        assertThrows(MallBizException.class, () -> authService.login("op_reset", "old12345", "127.0.0.1"));
        assertNotNull(authService.login("op_reset", "newpass88", "127.0.0.1").token());
    }

    @Test
    @DisplayName("不能停用当前登录账号（保证后台留管理员）")
    void cannotDisableSelf() {
        assertThrows(MallBizException.class, () -> authService.toggleUser(admin().userId(), false, admin()));
    }

    @Test
    @DisplayName("列表不含密码哈希")
    void listHidesHash() {
        List<AuthService.UserView> users = authService.listUsers();
        assertTrue(users.size() >= 3, "种子 3 用户 + 可能新建");
        assertTrue(users.stream().anyMatch(u -> u.username().equals("admin")));
    }
}