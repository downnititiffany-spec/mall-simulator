package com.graduation.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模拟数据生成器独立入口（V2.1 §3.1 第三个程序：{@code synthetic-data-generator} / 8092 或 CLI）。
 *
 * <p>唯一职责：固定 seed 的场景生成、负载控制、脏数据策略、运行报告。</p>
 *
 * <p>明确禁止（V2.1 §3.1 / §3.4-3）：直接操作商城数据库；依赖商城内部 Service/Mapper；
 * 写平台业务库；触发平台流水线。本工程是**测试客户端**——没有它，商城与平台都必须照常工作。</p>
 */
@SpringBootApplication
public class GeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeneratorApplication.class, args);
    }
}
