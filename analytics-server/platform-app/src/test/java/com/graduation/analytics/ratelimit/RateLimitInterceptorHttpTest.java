package com.graduation.analytics.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.controller.PlatformExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class RateLimitInterceptorHttpTest {

    @Test
    void appliesAfterAuthenticationAndReturns429WithRetryAfter() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.validate("admin-token")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LimitedController())
                .addInterceptors(new AuthInterceptor(auth, new ObjectMapper()),
                        new RateLimitInterceptor(new InMemoryRequestRateLimiter()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .build();

        for (int i = 0; i < 2; i++) {
            assertThat(mvc.perform(get("/api/v1/test-limited")
                            .header("Authorization", "Bearer admin-token"))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        var limited = mvc.perform(get("/api/v1/test-limited")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
        assertThat(mvc.perform(get("/api/v1/test-limited")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @RestController
    static class LimitedController {
        @GetMapping("/api/v1/test-limited")
        @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
        @RateLimited(scope = "test", requests = 2, windowSeconds = 60)
        String limited() {
            return "ok";
        }
    }
}
