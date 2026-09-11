package com.graduation.analytics.auth;

import com.graduation.analytics.auth.entity.UserEntity;
import com.graduation.analytics.auth.mapper.SysUserMapper;
import com.graduation.analytics.auth.mapper.UserSessionMapper;
import com.graduation.analytics.common.PlatformBizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号与角色白名单（R8-3 §21.1）：role 白名单必须与权限矩阵同源，data_dev 必须可创建。
 */
class AuthServiceRoleWhitelistTest {

    private SysUserMapper userMapper;
    private UserSessionMapper sessionMapper;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        sessionMapper = mock(UserSessionMapper.class);
        authService = new AuthService(userMapper, sessionMapper, new BCryptPasswordEncoder());
    }

    @Test
    @DisplayName("四种角色（含 data_dev）都能创建，密码以 BCrypt 落库")
    void allMatrixRolesCreatable() {
        for (String role : RolePermissions.ROLES) {
            setUp();
            when(userMapper.selectOne(any())).thenReturn(null);

            AuthService.UserView view = authService.createUser(role + "-user", "姓名", role, "Secret123");

            assertEquals(role, view.role());
            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userMapper).insert(captor.capture());
            UserEntity saved = captor.getValue();
            assertEquals(role, saved.getRole());
            assertEquals(1, saved.getStatus());
            assertNotNull(saved.getPasswordHash());
            assertFalse(saved.getPasswordHash().contains("Secret123"), "禁止明文落库");
            assertTrue(saved.getPasswordHash().startsWith("$2"), "应为 BCrypt 哈希");
        }
    }

    @Test
    @DisplayName("矩阵外角色（含大写/空白）一律 PARAM_INVALID，不落库")
    void unknownRoleRejected() {
        for (String role : new String[]{null, "", "guest", "ADMIN", "root", "data-dev"}) {
            setUp();
            when(userMapper.selectOne(any())).thenReturn(null);
            PlatformBizException e = assertThrows(PlatformBizException.class,
                    () -> authService.createUser("u", "n", role, "Secret123"));
            assertEquals(PlatformBizException.PARAM_INVALID, e.getCode());
            verify(userMapper, never()).insert(any(UserEntity.class));
        }
    }

    @Test
    @DisplayName("用户名重复 / 空密码 → 拒绝")
    void duplicateAndBadPasswordRejected() {
        UserEntity exist = new UserEntity();
        exist.setUsername("dup");
        when(userMapper.selectOne(any())).thenReturn(exist);
        assertThrows(PlatformBizException.class, () -> authService.createUser("dup", "n", "analyst", "Secret123"));

        setUp();
        when(userMapper.selectOne(any())).thenReturn(null);
        assertThrows(PlatformBizException.class, () -> authService.createUser("u", "n", "analyst", ""));
        assertThrows(PlatformBizException.class, () -> authService.createUser("u", "n", "analyst", null));
    }

    @Test
    @DisplayName("validate：会话过期 / 用户被禁用 / 未知 token 一律返回 null（拦截器据此 401）")
    void validateFailsClosed() {
        when(sessionMapper.selectOne(any())).thenReturn(null);
        assertEquals(null, authService.validate("nope"));
        assertEquals(null, authService.validate(null));
        assertEquals(null, authService.validate("  "));

        var expired = new com.graduation.analytics.auth.entity.UserSessionEntity();
        expired.setToken("t1");
        expired.setUserId(5L);
        expired.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));
        when(sessionMapper.selectOne(any())).thenReturn(expired);
        assertEquals(null, authService.validate("t1"), "过期会话不得返回身份");

        var disabledUser = new UserEntity();
        disabledUser.setId(5L);
        disabledUser.setUsername("alice");
        disabledUser.setRole("analyst");
        disabledUser.setStatus(0);
        var live = new com.graduation.analytics.auth.entity.UserSessionEntity();
        live.setToken("t2");
        live.setUserId(5L);
        live.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(sessionMapper.selectOne(any())).thenReturn(live);
        when(userMapper.selectById(5L)).thenReturn(disabledUser);
        assertEquals(null, authService.validate("t2"), "禁用账号不得返回身份");
    }

    @Test
    @DisplayName("validate：有效会话返回角色，且角色取自库（不来自请求）")
    void validateReturnsDbRole() {
        var live = new com.graduation.analytics.auth.entity.UserSessionEntity();
        live.setToken("t3");
        live.setUserId(7L);
        live.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(sessionMapper.selectOne(any())).thenReturn(live);
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUsername("dev1");
        user.setRole("data_dev");
        user.setStatus(1);
        when(userMapper.selectById(7L)).thenReturn(user);

        CurrentUser current = authService.validate("t3");
        assertEquals("dev1", current.username());
        assertEquals("data_dev", current.role());
    }
}
