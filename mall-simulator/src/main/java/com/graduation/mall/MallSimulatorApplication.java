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
// 仅扫描商城责任范围内的 Mapper：商品/库存/交易/Outbox/账号会话（§5.2 边界）。
// 采集(ingestion)/指标(metric)/流水线(pipeline)/AI(ai)/决策(decision) 的 Mapper 属于
// analytics-server，已随平台复制代码一并移出本模块，此处不再声明。
@MapperScan({"com.graduation.mall.domain.mapper", "com.graduation.mall.auth.mapper"})
public class MallSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallSimulatorApplication.class, args);
    }
}