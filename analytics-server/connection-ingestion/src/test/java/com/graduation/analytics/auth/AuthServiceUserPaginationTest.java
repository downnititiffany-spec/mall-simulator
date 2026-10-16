package com.graduation.analytics.auth;

import com.graduation.analytics.auth.entity.UserEntity;
import com.graduation.analytics.auth.mapper.SysUserMapper;
import com.graduation.analytics.auth.mapper.UserSessionMapper;
import com.graduation.analytics.common.PlatformBizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceUserPaginationTest {
    private SysUserMapper users;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(SysUserMapper.class);
        auth = new AuthService(users, mock(UserSessionMapper.class), new BCryptPasswordEncoder());
    }

    @Test
    void returnsPageMetadataAndMapsOnlyPublicUserFields() {
        when(users.selectCount(any())).thenReturn(21L);
        UserEntity user = new UserEntity();
        user.setId(12L);
        user.setUsername("analyst");
        user.setRealName("数据分析师");
        user.setRole("analyst");
        user.setPasswordHash("must-not-be-exposed");
        when(users.selectList(any())).thenReturn(List.of(user));

        var result = auth.listUsersPage(99, 10, "username,asc");

        assertEquals(3, result.page());
        assertEquals(10, result.size());
        assertEquals(21, result.total());
        assertEquals(3, result.totalPages());
        assertEquals("username,asc", result.sort());
        assertEquals(new AuthService.UserView(12L, "analyst", "数据分析师", "analyst"), result.items().get(0));
        verify(users).selectCount(any());
        verify(users).selectList(any());
    }

    @Test
    void rejectsOversizedPageAndUnknownSortWithoutDatabaseAccess() {
        assertThrows(PlatformBizException.class, () -> auth.listUsersPage(1, 101, "id,asc"));
        assertThrows(PlatformBizException.class, () -> auth.listUsersPage(1, 10, "username desc;drop table"));
        verifyNoInteractions(users);
    }
}
