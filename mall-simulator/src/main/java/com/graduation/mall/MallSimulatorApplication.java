package com.graduation.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * mall-simulator 入口 —— 独立进程、独立数据库（mall_simulator）。
 * 与 analytics-server 完全解耦（§19.1/§19.2：mall-simulator 不能依赖 analytics-server）。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan({"com.graduation.mall.domain.mapper", "com.graduation.mall.ingestion.mapper"})
public class MallSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallSimulatorApplication.class, args);
    }
}