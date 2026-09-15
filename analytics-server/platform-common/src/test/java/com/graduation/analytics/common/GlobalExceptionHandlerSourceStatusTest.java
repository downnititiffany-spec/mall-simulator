package com.graduation.analytics.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-03 错误码 → HTTP 状态映射（L0）。要求：**单一所有者**（{@link PlatformBizException} 定码、
 * {@link GlobalExceptionHandler} 定状态），不得新增第二套异常类型或映射表。
 *
 * <p>为什么需要分状态：任务书 §4 P1-03 要求 {@code SOURCE_NOT_FOUND} 与「不可变/占用/画像非法」
 * 在传输层可区分；整改前 {@code handleBiz} 用类级 {@code @ResponseStatus(BAD_REQUEST)}
 * 把所有业务码压成 400。</p>
 *
 * <p><b>取证边界</b>：本类断言的是处理器返回的 {@code ResponseEntity} 状态；
 * 真机 HTTP 状态码由 E3（真实例 + 真 MySQL 副本库）以原始响应取证。</p>
 */
class GlobalExceptionHandlerSourceStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<ApiResponse<Void>> handle(String code, String message) {
        return handler.handleBiz(new PlatformBizException(code, message));
    }

    @Test
    @DisplayName("SOURCE_NOT_FOUND → 404（资源不存在，不是参数错）")
    void sourceNotFoundMapsTo404() {
        ResponseEntity<ApiResponse<Void>> response = handle(PlatformBizException.SOURCE_NOT_FOUND, "源不存在: 999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("源不存在: 999");
        assertThat(response.getBody().traceId()).isNotBlank();
    }

    @Test
    @DisplayName("SOURCE_CODE_IMMUTABLE / SOURCE_IN_USE / SOURCE_PROFILE_INVALID / SOURCE_NOT_BOUND → 409（与当前资源状态冲突）")
    void sourceConflictCodesMapTo409() {
        assertThat(handle(PlatformBizException.SOURCE_CODE_IMMUTABLE, "source_code 不可改").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handle(PlatformBizException.SOURCE_IN_USE, "当前源不可暂停").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handle(PlatformBizException.SOURCE_PROFILE_INVALID, "画像文件不存在").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // P1-05 加性：未绑定源是"当前运行环境状态不允许采集"，不是请求参数写错（那才是 400）
        assertThat(handle(PlatformBizException.SOURCE_NOT_BOUND, "运行环境未绑定源").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("dry-run 域两个码 → 404（S2-01B 加性新增：引用被允许但位置没有东西 / 报告不在本进程）")
    void dryRunCodesMapTo404() {
        assertThat(handle(PlatformBizException.DRY_RUN_SAMPLE_NOT_FOUND, "样本不存在").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND, "报告不存在").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("既有错误码状态不变（仍是 400）：改动必须是加性的，不得顺手改已有语义")
    void existingCodesKeepBadRequest() {
        assertThat(handle(PlatformBizException.PARAM_INVALID, "参数错").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(PlatformBizException.USER_NOT_FOUND, "用户不存在").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(PlatformBizException.INTERNAL, "内部错").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(PlatformBizException.METHOD_NOT_ALLOWED, "方法错").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(PlatformBizException.UNSUPPORTED_MEDIA_TYPE, "媒体类型错").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("未知业务码 → 400 兜底（不因新增分支而漏掉默认值）")
    void unknownCodeFallsBackTo400() {
        assertThat(handle("SOMETHING_NEW", "未知码").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("错误码常量本身是加性的：原有 5 个码逐字不变，源域/dry-run 域新增码逐字取自任务书")
    void codesAreAdditiveAndVerbatim() {
        assertThat(PlatformBizException.USER_NOT_FOUND).isEqualTo("USER_NOT_FOUND");
        assertThat(PlatformBizException.PARAM_INVALID).isEqualTo("PARAM_INVALID");
        assertThat(PlatformBizException.UNSUPPORTED_MEDIA_TYPE).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(PlatformBizException.METHOD_NOT_ALLOWED).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(PlatformBizException.INTERNAL).isEqualTo("INTERNAL");

        assertThat(PlatformBizException.SOURCE_NOT_FOUND).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(PlatformBizException.SOURCE_CODE_IMMUTABLE).isEqualTo("SOURCE_CODE_IMMUTABLE");
        assertThat(PlatformBizException.SOURCE_PROFILE_INVALID).isEqualTo("SOURCE_PROFILE_INVALID");
        assertThat(PlatformBizException.SOURCE_IN_USE).isEqualTo("SOURCE_IN_USE");
        // P1-05 加性新增（D-037）：未绑定源
        assertThat(PlatformBizException.SOURCE_NOT_BOUND).isEqualTo("SOURCE_NOT_BOUND");
        // S2-01B 加性新增（映射 dry-run，仅预览、不激活）
        assertThat(PlatformBizException.DRY_RUN_SAMPLE_NOT_FOUND).isEqualTo("DRY_RUN_SAMPLE_NOT_FOUND");
        assertThat(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND).isEqualTo("DRY_RUN_REPORT_NOT_FOUND");
    }
}
