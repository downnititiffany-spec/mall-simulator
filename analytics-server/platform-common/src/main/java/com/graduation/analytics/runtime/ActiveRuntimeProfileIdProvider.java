package com.graduation.analytics.runtime;

import java.util.Optional;

/** Read-only access to the globally active RuntimeProfile identity for query scoping. */
public interface ActiveRuntimeProfileIdProvider {

    Optional<Long> activeRuntimeProfileId();
}
