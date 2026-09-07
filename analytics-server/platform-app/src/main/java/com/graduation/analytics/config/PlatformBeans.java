package com.graduation.analytics.config;

import com.graduation.analytics.contracts.EventClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 平台通用 Bean（R1）：EventClock 模拟时钟（§20.3，业务时间与系统时间分离）。
 * 商城侧在 mall-simulator AppConfig 注册；平台独立进程自行注册（整改书 §5.2 迁移语义）。
 */
@Configuration
public class PlatformBeans {

    @Bean
    public EventClock eventClock() {
        return new EventClock(Clock.system(ZoneId.of("Asia/Shanghai")));
    }
}