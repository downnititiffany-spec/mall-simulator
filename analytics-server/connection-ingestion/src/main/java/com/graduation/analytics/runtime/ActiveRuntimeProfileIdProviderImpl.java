package com.graduation.analytics.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Reads a unique active profile ID, avoiding RuntimeProfileService and failing closed on duplicates. */
@Component
public class ActiveRuntimeProfileIdProviderImpl implements ActiveRuntimeProfileIdProvider {

    private final RuntimeProfileMapper profileMapper;

    public ActiveRuntimeProfileIdProviderImpl(RuntimeProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    @Override
    public Optional<Long> activeRuntimeProfileId() {
        List<RuntimeProfile> active = profileMapper.selectList(new LambdaQueryWrapper<RuntimeProfile>()
                .eq(RuntimeProfile::getStatus, RuntimeProfile.STATUS_ACTIVE)
                .orderByDesc(RuntimeProfile::getId)
                .last("LIMIT 2"));
        if (active == null || active.size() != 1) {
            return Optional.empty();
        }
        Long id = active.get(0).getId();
        return id == null || id <= 0 ? Optional.empty() : Optional.of(id);
    }
}
