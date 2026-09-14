package com.graduation.analytics.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 测试支持用的指纹工具（唯一所有者：SHA-1 / MD5 十六进制）。
 *
 * <p>用于把「跑前/跑后」的只读事实压成一个可比较的短串，便于在证据 README 里逐字引用。
 * 不做任何安全用途（不是口令哈希）。</p>
 */
public final class TestRunDigest {

    private TestRunDigest() {
    }

    public static String sha1Hex(String material) {
        return hex("SHA-1", material);
    }

    public static String md5Hex(String material) {
        return hex("MD5", material);
    }

    private static String hex(String algorithm, String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] bytes = digest.digest((material == null ? "" : material).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少摘要算法 " + algorithm, e);
        }
    }
}
