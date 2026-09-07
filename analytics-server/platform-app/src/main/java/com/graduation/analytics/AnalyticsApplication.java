package com.graduation.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 分析平台启动入口（R1 骨架，整改书 §6）：与 mall-simulator 独立的进程（端口 8091）。
 * 后续轮次逐步接入：connection-ingestion / warehouse-pipeline / metric-analysis /
 * ai-decision 及三库（analytics_meta / analytics_metric）迁移与代码。
 */
@SpringBootApplication
public class AnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}