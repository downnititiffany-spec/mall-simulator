-- V7: 权限登录（§3.2 权限模块 / §21.5 会话）
-- 演示种子密码已用 BCrypt 预计算：admin/admin123、operator/operator123、analyst/analyst123
-- 生产发布时必须修改种子密码或删除种子账号（deployment.md 有说明）。
CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  real_name     VARCHAR(64)  NOT NULL DEFAULT '',
  role          VARCHAR(32)  NOT NULL,              -- admin | operator | analyst | data_dev
  status        TINYINT      NOT NULL DEFAULT 1,    -- 1 启用 0 禁用
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户（§21.5）';

CREATE TABLE IF NOT EXISTS user_session (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  token      VARCHAR(64)  NOT NULL UNIQUE,          -- UUID（无随机性依赖）
  user_id    BIGINT       NOT NULL,
  expires_at DATETIME     NOT NULL,
  login_ip   VARCHAR(64)  NOT NULL DEFAULT '',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session_user (user_id),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录会话（简易 token，24h 过期）';

INSERT INTO sys_user (username, password_hash, real_name, role, status)
VALUES ('admin', '$2a$10$mM3.Zz5EUO1hnfeKPAHJG.5SAlz87qJir.odk57b9cA8BN4B90hO.', '系统管理员', 'admin', 1);

INSERT INTO sys_user (username, password_hash, real_name, role, status)
VALUES ('operator', '$2a$10$49dA9vSbVZPT0KwpctmVluZJDZAoXXcI0SpwpR9PmGMdSWtvKK4E6', '运营专员', 'operator', 1);

INSERT INTO sys_user (username, password_hash, real_name, role, status)
VALUES ('analyst', '$2a$10$NKhvcXtOxP2wCXChPGLgXevt7r55GDPCoOJJ/v0XUiSPkYco5sNXu', '数据分析师', 'analyst', 1);