package com.graduation.analytics.metric.publish;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/** Resolves paths in an ADS export manifest for the current application host. */
public final class MetricExportPath {

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
                return Path.of(URI.create(value));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无效的本机 file URI: " + value, e);
            }
        }
        if (value.matches("^[A-Za-z]:[\\\\/].*")) {
            return Path.of(value);
        }
        if (value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("仅支持本机文件路径或 file URI，不支持 URI scheme: " + value);
        }
        return Path.of(value);
    }
}
