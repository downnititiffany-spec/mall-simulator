package com.graduation.analytics.metric.publish;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves paths in an ADS export manifest for the current application host. */
public final class MetricExportPath {

    private static final Pattern WSL_DRIVE_MOUNT = Pattern.compile("^/mnt/([A-Za-z])/(.*)$");

    private MetricExportPath() {
    }

    /**
     * Accepts ordinary local paths and {@code file:} URIs. Other URI schemes (for example
     * {@code hdfs:}) are rejected explicitly: they require an artifact transport, not NIO.
     */
    public static Path localPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("导出文件路径不能为空");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:")) {
            try {
                URI uri = URI.create(value);
                String wslWindowsPath = isWindowsHost() ? windowsPathFromWslMount(uri.getPath()) : null;
                return wslWindowsPath == null ? Path.of(uri) : Path.of(wslWindowsPath);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无效的本机 file URI: " + value, e);
            }
        }
        if (value.matches("^[A-Za-z]:[\\\\/].*")) {
            return Path.of(value);
        }
        String wslWindowsPath = isWindowsHost() ? windowsPathFromWslMount(value) : null;
        if (wslWindowsPath != null) {
            return Path.of(wslWindowsPath);
        }
        if (value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("仅支持本机文件路径或 file URI，不支持 URI scheme: " + value);
        }
        return Path.of(value);
    }

    /** Convert a WSL drive mount to its Windows host path; package-visible for cross-OS tests. */
    static String windowsPathFromWslMount(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = WSL_DRIVE_MOUNT.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1).toUpperCase(Locale.ROOT) + ":/" + matcher.group(2);
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
