package com.graduation.analytics.runtime.storage;

import com.graduation.analytics.runtime.entity.RuntimeProfile;

/** Resolves the storage implementation strictly from the active profile's landing URI. */
public final class LandingStorageResolver {

    private LandingStorageResolver() {
    }

    public static LandingStorage forProfile(RuntimeProfile profile) {
        if (profile == null || profile.getLandingUri() == null || profile.getLandingUri().isBlank()) {
            throw new IllegalArgumentException("active runtime profile must define landing_uri");
        }
        String uri = profile.getLandingUri().trim();
        if (uri.regionMatches(true, 0, "hdfs://", 0, "hdfs://".length())) {
            try {
                return new HdfsLandingStorage(uri);
            } catch (RuntimeException e) {
                throw new IllegalStateException("hdfs Landing 无法初始化: " + e.getMessage(), e);
            }
        }
        return new LocalLandingStorage(uri);
    }
}
