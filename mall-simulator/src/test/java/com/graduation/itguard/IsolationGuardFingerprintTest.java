package com.graduation.itguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例指纹判据的反例集（DEV-002 语义一致性收口，2026-09-14）。
 *
 * <p>唯一权威实例身份＝{@code hostname:port}（现场真值：{@code dahaishui:3307}）。
 * 本类**不连库、不打 {@code @Tag("it")}**：属默认套件，隔离档（{@code -Pisolated-tests}，
 * 只跑 {@code @Tag("it")}）不会执行它，因此不改变隔离档的 30 个用例口径。</p>
 *
 * <p>与 analytics 侧 {@code TestIsolationGuardTest#fingerprintOnlyAcceptsCanonicalHostnamePort}
 * 是同一组反例（两侧实现是两份同源文件，判据必须一致）。</p>
 */
class IsolationGuardFingerprintTest {

    /** 隔离实例真值：与 runner 门禁 6 探针注入的 {@code IT_GUARD_SERVERFINGERPRINT} 同形。 */
    private static final String HOST = "dahaishui";
    private static final int PORT = 3307;

    @Test
    @DisplayName("canonical hostname:port 是唯一通过形态（含规范化）")
    void canonicalHostnamePortPasses() {
        assertTrue(IsolationGuard.fingerprintMatches("dahaishui:3307", HOST, PORT),
                "dahaishui:3307 -> PASS");
        // 规范化：两侧空白去掉；hostname 段大小写归一（允许）
        assertTrue(IsolationGuard.fingerprintMatches("  DAHAISHUI:3307  ", HOST, PORT));
        assertTrue(IsolationGuard.fingerprintMatches("dahaishui:3307", " DAHAISHUI ", PORT));
        // 判据是「hostname:port 相等」，不是「必须叫 dahaishui」：另一台真机同形也通过
        assertTrue(IsolationGuard.fingerprintMatches("otherhost:3307", "otherhost", PORT));
    }

    @Test
    @DisplayName("端口不同一律拒绝（不模糊匹配、不做前缀匹配）")
    void differentPortRejected() {
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:3306", HOST, PORT),
                "dahaishui:3306 -> FAIL（宿主正式端口）");
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:33070", HOST, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:330", HOST, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:0", HOST, PORT));
    }

    @Test
    @DisplayName("hostname 不同一律拒绝（不做前缀匹配）")
    void differentHostRejected() {
        assertFalse(IsolationGuard.fingerprintMatches("otherhost:3307", HOST, PORT),
                "otherhost:3307 -> FAIL");
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui2:3307", HOST, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishu:3307", HOST, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui.local:3307", HOST, PORT));
    }

    @Test
    @DisplayName("旧宽松形态全部拒绝：裸 hostname／裸端口／127.0.0.1／localhost／server_uuid")
    void laxFormsRejected() {
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui", HOST, PORT), "裸 hostname -> FAIL");
        assertFalse(IsolationGuard.fingerprintMatches("3307", HOST, PORT), "裸 port -> FAIL");
        assertFalse(IsolationGuard.fingerprintMatches("127.0.0.1:3307", HOST, PORT),
                "127.0.0.1:3307 -> FAIL（连接地址不得替代真实 hostname）");
        assertFalse(IsolationGuard.fingerprintMatches("localhost:3307", HOST, PORT),
                "localhost:3307 -> FAIL（同上）");
        assertFalse(IsolationGuard.fingerprintMatches("de8ebbea-aff4-11f1-8037-00155d5dba47", HOST, PORT),
                "server_uuid -> FAIL（不是 fingerprint 替代值）");
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:3307:3307", HOST, PORT));
    }

    @Test
    @DisplayName("缺失/非法值一律 fail-closed（不兜底、不跳级）")
    void missingValuesRejected() {
        assertFalse(IsolationGuard.fingerprintMatches(null, HOST, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("", HOST, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("   ", HOST, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:3307", null, PORT));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:3307", "   ", PORT));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:3307", HOST, 0));
        assertFalse(IsolationGuard.fingerprintMatches("dahaishui:3307", HOST, -1));
    }
}
