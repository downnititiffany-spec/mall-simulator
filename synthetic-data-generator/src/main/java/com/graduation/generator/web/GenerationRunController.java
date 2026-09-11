package com.graduation.generator.web;

import com.graduation.generator.service.GenerationRunService;
import com.graduation.generator.web.dto.GeneratorApiDtos.ArtifactView;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunStarted;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunView;
import com.graduation.generator.web.dto.GeneratorApiDtos.StartRunRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * §4.4 L153–L156 的四个端点，路径与请求/响应体逐项照 {@code contract-specs/openapi/generator-api.v1.yaml}。
 *
 * <p>状态码按契约记录（契约对每个端点都写了"指导书未规定状态码，按 200 记录，待冻结"），
 * 所以这里**不自行改成 201/202**——状态码未冻结时，实现跟着契约走，等冻结再统一改。</p>
 */
@RestController
@RequestMapping(path = "/api/v1/generation-runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class GenerationRunController {

    private final GenerationRunService service;

    public GenerationRunController(GenerationRunService service) {
        this.service = service;
    }

    /** §4.4 L153：按 plan/version 异步启动，返回 runId */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public RunStarted start(@RequestBody StartRunRequest request) {
        return service.start(request);
    }

    /** §4.4 L154：状态、计数、失败摘要 */
    @GetMapping("/{id}")
    public RunView find(@PathVariable("id") String id) {
        return service.find(id);
    }

    /** §4.4 L155：幂等取消，返回取消后的运行详情 */
    @PostMapping("/{id}/cancel")
    public RunView cancel(@PathVariable("id") String id) {
        return service.cancel(id);
    }

    /** §4.4 L156：文件、checksum、清单（可能为空数组） */
    @GetMapping("/{id}/artifacts")
    public List<ArtifactView> artifacts(@PathVariable("id") String id) {
        return service.artifacts(id);
    }
}
