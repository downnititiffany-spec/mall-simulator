package com.graduation.generator.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.PlanRow;
import com.graduation.generator.meta.RunStatus;
import com.graduation.generator.service.GenerationRunService;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunStarted;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunView;
import com.graduation.generator.web.dto.GeneratorApiDtos.StartRunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 生成器命令行（§4.5 的 CLI 分支）。
 *
 * <p><b>为什么计划版本必须由 CLI 建</b>：冻结契约里的七个端点没有任何"创建计划"的入口，
 * {@code GenerationRunStartRequest} 也明确只承载 {@code plan_id + version}（其余参数从不可变计划版本读）。
 * 所以"写入计划版本"这一步只能由命令行/页面承担，本类就是那个入口，且不与 API 抢所有权。</p>
 *
 * <p>用法（jar 或 {@code mvn spring-boot:run} 均可）：</p>
 * <pre>
 * --generator.cli=plan-append --plan-id=demo --scenario=normal --seed=42 \
 *   --start=2026-09-01T00:00:00 --end=2026-09-30T23:59:59 --event-count=1000
 * --generator.cli=run-start --plan-id=demo --version=1 [--wait-seconds=300]
 * </pre>
 *
 * <p>不带 {@code --generator.cli} 时本类<b>不做任何事</b>，Web 服务照常启动（测试与页面模式依赖这一点）。</p>
 */
@Component
public class GeneratorCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeneratorCli.class);
    private static final long POLL_INTERVAL_MS = 500;

    private final GeneratorMetaStore store;
    private final GenerationRunService service;
    private final ObjectMapper mapper;
    private final String command;
    private final ConfigurableApplicationContext context;

    public GeneratorCli(GeneratorMetaStore store, GenerationRunService service, ObjectMapper mapper,
                        @org.springframework.beans.factory.annotation.Value("${generator.cli:}") String command,
                        ConfigurableApplicationContext context) {
        this.store = store;
        this.service = service;
        this.mapper = mapper;
        this.command = command.trim();
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (command.isEmpty()) {
            return;
        }
        int exitCode;
        try {
            exitCode = switch (command) {
                case "plan-append" -> planAppend(args);
                case "run-start" -> runStart(args);
                default -> {
                    log.error("未知命令：{}（已实现：plan-append、run-start）", command);
                    yield 64;
                }
            };
        } catch (RuntimeException e) {
            log.error("命令 {} 执行失败：{}", command, e.toString(), e);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** 追加一个不可变计划版本（版本号 = 同 plan_id 的 MAX(version)+1，由元数据仓保证） */
    private int planAppend(ApplicationArguments args) {
        String planId = required(args, "plan-id");
        String scenario = optional(args, "scenario", "normal");
        String mode = optional(args, "mode", GenerationRunService.MODE_CANONICAL_EVENT_FILE);
        String dirtyProfile = optional(args, "dirty-profile", "none");
        long seed = Long.parseLong(optional(args, "seed", "42"));
        long eventCount = Long.parseLong(optional(args, "event-count", "1000"));
        int rate = Integer.parseInt(optional(args, "rate", "0"));
        Instant start = instant(required(args, "start"));
        Instant end = instant(required(args, "end"));
        String outputUri = optional(args, "output-uri", null);
        Long targetId = args.containsOption("target-id")
                ? Long.parseLong(optional(args, "target-id", "0")) : null;

        int version = store.appendPlanVersion(new PlanRow(null, planId, 0, mode, targetId, scenario, seed,
                start, end, eventCount, rate, dirtyProfile, outputUri));
        System.out.printf("plan_id=%s version=%d mode=%s scenario=%s seed=%d event_count=%d%n",
                planId, version, mode, scenario, seed, eventCount);
        return 0;
    }

    /** 按 plan/version 启动运行并等待终态（供冒烟脚本一键跑通；页面/第三方仍用 HTTP API） */
    private int runStart(ApplicationArguments args) {
        String planId = required(args, "plan-id");
        int version = Integer.parseInt(required(args, "version"));
        long waitSeconds = Long.parseLong(optional(args, "wait-seconds", "300"));
        long deadline = System.currentTimeMillis() + waitSeconds * 1000;

        RunStarted started = service.start(new StartRunRequest(planId, version));
        System.out.println("run_id=" + started.runId());
        RunView view = service.find(started.runId());
        while (!isTerminal(view.status()) && System.currentTimeMillis() < deadline) {
            sleep();
            view = service.find(started.runId());
        }
        System.out.println(toJson(view));
        for (var artifact : service.artifacts(started.runId())) {
            System.out.println(toJson(artifact));
        }
        if (!RunStatus.SUCCESS.name().equals(view.status())) {
            log.error("运行未成功：status={} error={}", view.status(), view.error());
            return 2;
        }
        return 0;
    }

    private static boolean isTerminal(String status) {
        return RunStatus.SUCCESS.name().equals(status) || RunStatus.FAILED.name().equals(status)
                || RunStatus.CANCELLED.name().equals(status);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待运行终态时被中断", e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败：" + value, e);
        }
    }

    private static String required(ApplicationArguments args, String name) {
        String value = optional(args, name, null);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填参数 --" + name);
        }
        return value;
    }

    private static String optional(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return fallback;
        }
        return values.get(0).trim();
    }

    /** 接受带偏移的 ISO8601，也接受不带偏移的本地时间（按业务时区 Asia/Shanghai 解释） */
    static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("时间格式无法解析：" + text
                        + "（示例：2026-09-01T00:00:00 或 2026-09-01T00:00:00+08:00）", e);
            }
        }
    }
}
