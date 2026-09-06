package com.graduation.mall.controller;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 单进程发布回退（§14.3 打包）：前端 dist 由打包脚本拷入 classpath:/static，
 * 浏览器直接访问 /overview 等前端路由（无扩展名）时由本控制器转发回 index.html，
 * 由 Vue 路由接管渲染；/api/**（多段）与带扩展名的静态资源不受影响。
 * 注意：必须是 @Controller（视图转发语义），@RestController 会把 forward 当字符串输出。
 */
@Controller
public class SpaFallbackController {

    @GetMapping(value = {"/", "/{path:[^\\.]*}"}, produces = MediaType.TEXT_HTML_VALUE)
    public String spa() {
        return "forward:/index.html";
    }
}