package com.graduation.mall.config;

import com.graduation.mall.outbox.EventClock;
import com.graduation.mall.outbox.EventIdGenerator;
import com.graduation.mall.outbox.UuidEventIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 基础 Bean：事件 ID 生成器与业务时间源（§20.3：不把系统时间直接当业务时间；
 * 统一 Asia/Shanghai；测试可注固定实现保证可复现）。
 */
@Configuration
public class AppConfig {

    @Bean
    public EventIdGenerator eventIdGenerator() {
        return new UuidEventIdGenerator();
    }

    @Bean
    public EventClock eventClock() {
        return new EventClock(Clock.system(ZoneId.of("Asia/Shanghai")));
    }
}