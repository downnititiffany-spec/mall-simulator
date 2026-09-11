package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行环境管理（整改书 §8.4）：环境列表/连通测试/激活/禁用。
 * 运维中心调用；普通员工页面只显示环境名称与数据更新时间（前端控制，§8.4）。
 *
 * <p>R8-3 §3.2：整类归 runtime:manage（admin/data_dev）。读接口一并要求该权限，
 * 因为环境行含连接配置（host/port/账号等），不应开放给 operator/analyst；
 * 冻结前端 web/src/api.js 未使用本组接口，故不会造成前端 403。</p>
 */
@RestController
@RequestMapping("/api/v1/runtime-profiles")
@RequiredArgsConstructor
@RequiresPermission(PermissionCode.RUNTIME_MANAGE)
public class RuntimeProfileController {

    private final RuntimeProfileService profileService;

    @PostMapping
    public ApiResponse<RuntimeProfile> create(@Valid @RequestBody RuntimeProfile profile) {
        return ApiResponse.ok(profileService.create(profile), TraceContext.create().traceId());
    }

    @PutMapping("/{id}")
    public ApiResponse<RuntimeProfile> update(@PathVariable Long id, @RequestBody RuntimeProfile profile) {
        profile.setId(id);
        return ApiResponse.ok(profileService.update(profile), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/test")
    public ApiResponse<RuntimeProfileService.TestResult> test(@PathVariable Long id) {
        return ApiResponse.ok(profileService.test(id), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<RuntimeProfile> activate(@PathVariable Long id) {
        return ApiResponse.ok(profileService.activate(id), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<RuntimeProfile> disable(@PathVariable Long id) {
        return ApiResponse.ok(profileService.disable(id), TraceContext.create().traceId());
    }

    @GetMapping("/active")
    public ApiResponse<RuntimeProfile> active() {
        return ApiResponse.ok(profileService.getActive(), TraceContext.create().traceId());
    }

    @GetMapping("/{id}")
    public ApiResponse<RuntimeProfile> get(@PathVariable Long id) {
        return ApiResponse.ok(profileService.get(id), TraceContext.create().traceId());
    }

    @GetMapping
    public ApiResponse<List<RuntimeProfile>> list() {
        return ApiResponse.ok(profileService.list(), TraceContext.create().traceId());
    }
}