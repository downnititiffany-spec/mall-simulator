package com.graduation.analytics.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.auth.entity.UserEntity;
import com.graduation.analytics.auth.entity.UserSessionEntity;
import com.graduation.analytics.auth.mapper.SysUserMapper;
import com.graduation.analytics.auth.mapper.UserSessionMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.PaginationMath;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 登录认证服务（§3.2 权限模块 / §21.5 会话）：
 * login 校验 BCrypt 密码并创建 24h 会话；logout 删除会话；
 * validate 供 AuthInterceptor 校验 token 并还原 CurrentUser。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 用户名或密码错误（响应 401） */
    public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";
    /** 账号已禁用（响应 403） */
    public static final String USER_DISABLED = "USER_DISABLED";

    private static final long SESSION_TTL_HOURS = 24;

    private final SysUserMapper userMapper;
    private final UserSessionMapper sessionMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 登录成功返回给调用方的用户信息 */
    public record UserView(Long id, String username, String realName, String role) {
    }

    /** 登录结果信封：token + 用户信息 */
    public record LoginResult(String token, UserView user) {
    }

    public record UserPage(List<UserView> items, long page, int size, long total, long totalPages, String sort) {
    }

    /**
     * 登录：账号不存在或密码错误 → BAD_CREDENTIALS（控制器映射 401）；
     * 账号禁用（status=0）→ USER_DISABLED（控制器映射 403）。
     */
    public LoginResult login(String username, String password, String loginIp) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new PlatformBizException(BAD_CREDENTIALS, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new PlatformBizException(USER_DISABLED, "账号已被禁用，请联系管理员");
        }
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString().replace("-", "");
        UserSessionEntity session = new UserSessionEntity();
        session.setToken(token);
        session.setUserId(user.getId());
        session.setExpiresAt(now.plusHours(SESSION_TTL_HOURS));
        session.setLoginIp(loginIp);
        session.setCreatedAt(now);
        sessionMapper.insert(session);
        return new LoginResult(token, new UserView(user.getId(), user.getUsername(), user.getRealName(), user.getRole()));
    }

    /** 登出：删除当前 token 对应会话（token 不存在时静默忽略）。 */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessionMapper.delete(new LambdaQueryWrapper<UserSessionEntity>()
                .eq(UserSessionEntity::getToken, token));
    }

    /**
     * 会话校验：token 存在、未过期、用户存在且启用（status=1）→ CurrentUser，
     * 否则返回 null（AuthInterceptor 据此响应 401）。
     */
    public CurrentUser validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        UserSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<UserSessionEntity>()
                .eq(UserSessionEntity::getToken, token));
        if (session == null) {
            return null;
        }
        if (session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        UserEntity user = userMapper.selectById(session.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return null;
        }
        return new CurrentUser(user.getId(), user.getUsername(), user.getRole());
    }

    /** /api/v1/auth/me：返回当前用户完整信息（含 realName）。 */
    public UserView me(CurrentUser current) {
        UserEntity user = userMapper.selectById(current.userId());
        if (user == null) {
            throw new PlatformBizException(PlatformBizException.USER_NOT_FOUND, "用户不存在");
        }
        return new UserView(user.getId(), user.getUsername(), user.getRealName(), user.getRole());
    }

    // ── 用户管理（§3.2 权限模块·admin 专属） ─────────────────────────

    /** 创建用户：username 唯一；初始密码 BCrypt；返回用户视图 */
    public UserView createUser(String username, String realName, String role, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new PlatformBizException("PARAM_INVALID", "用户名与初始密码必填");
        }
        UserEntity exist = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        if (exist != null) {
            throw new PlatformBizException("USER_EXISTS", "用户名已存在: " + username);
        }
        String roleNorm = switch (role == null ? "" : role) {
            // R8-3（§21.1）：白名单补 data_dev，并直接以权限矩阵为唯一事实来源，
            // 避免「矩阵加角色、这里忘加」的双份维护
            case "admin", "data_dev", "operator", "analyst" -> role;
            default -> throw new PlatformBizException("PARAM_INVALID", "非法角色: " + role);
        };
        if (!RolePermissions.knownRole(roleNorm)) {
            throw new PlatformBizException("PARAM_INVALID", "角色未在权限矩阵中登记: " + roleNorm);
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRealName(realName == null ? "" : realName);
        user.setRole(roleNorm);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return new UserView(user.getId(), user.getUsername(), user.getRealName(), user.getRole());
    }

    /** 启用/禁用：禁止禁用 admin 自身（后台必须保留一个管理员） */
    public void toggleUser(Long userId, boolean enable, CurrentUser operator) {
        UserEntity user = requireUser(userId);
        if (operator != null && operator.userId().equals(userId)) {
            throw new PlatformBizException("FORBIDDEN_OPERATION", "不能停用当前登录账号");
        }
        user.setStatus(enable ? 1 : 0);
        userMapper.updateById(user);
    }

    /** 重置密码：新密码 BCrypt（成功后旧会话仍有效，由调用方自行决定） */
    public void resetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new PlatformBizException("PARAM_INVALID", "新密码至少 6 位");
        }
        UserEntity user = requireUser(userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    /** 用户列表（不含 password_hash） */
    public List<UserView> listUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                        .orderByAsc(UserEntity::getId)).stream()
                .map(u -> new UserView(u.getId(), u.getUsername(), u.getRealName(), u.getRole()))
                .toList();
    }

    /** 管理页分页：兼容旧数组接口，同时让用户规模增长时读取保持有界。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserPage listUsersPage(long page, int size, String sort) {
        if (page < 1 || size < 1 || size > 100) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "page 必须大于等于 1，size 必须在 1 到 100 之间");
        }
        String[] parts = sort == null ? new String[0] : sort.trim().toLowerCase(java.util.Locale.ROOT).split(",", -1);
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 格式应为 字段[,asc|desc]");
        }
        String direction = parts.length == 1 ? "asc" : parts[1];
        if (!List.of("id", "username", "role", "createdat").contains(parts[0])
                || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 字段或方向不受支持");
        }
        long total = userMapper.selectCount(new LambdaQueryWrapper<>());
        long totalPages = PaginationMath.totalPages(total, size);
        long actualPage = Math.min(page, totalPages);
        var query = new LambdaQueryWrapper<UserEntity>();
        boolean ascending = "asc".equals(direction);
        switch (parts[0]) {
            case "username" -> query.orderBy(true, ascending, UserEntity::getUsername);
            case "role" -> query.orderBy(true, ascending, UserEntity::getRole);
            case "createdat" -> query.orderBy(true, ascending, UserEntity::getCreatedAt);
            default -> query.orderBy(true, ascending, UserEntity::getId);
        }
        if (!"id".equals(parts[0])) query.orderByDesc(UserEntity::getId);
        long offset = Math.multiplyExact(actualPage - 1, (long) size);
        List<UserView> items = userMapper.selectList(query.last("LIMIT " + size + " OFFSET " + offset)).stream()
                .map(u -> new UserView(u.getId(), u.getUsername(), u.getRealName(), u.getRole()))
                .toList();
        return new UserPage(items, actualPage, size, total, totalPages, parts[0] + "," + direction);
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new PlatformBizException(PlatformBizException.USER_NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
