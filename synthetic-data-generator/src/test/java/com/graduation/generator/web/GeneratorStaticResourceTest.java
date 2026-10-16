package com.graduation.generator.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies that the isolated generator service packages and serves its browser entry without a database. */
@SpringBootTest(classes = GeneratorStaticResourceTest.StaticResourceApplication.class)
@AutoConfigureMockMvc
class GeneratorStaticResourceTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void servesGeneratorConsoleEntryAtServiceRoot() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        MvcResult page = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("GENERATOR CONSOLE")))
                .andReturn();

        String html = new String(page.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("启动一个生成计划"));
    }

    @Test
    void servesConsoleScriptAndStyleWithoutStartingDatabase() throws Exception {
        mvc.perform(get("/generator.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MALL_OUTCOME_UNKNOWN")))
                .andExpect(content().string(containsString("resultNeedsReconciliation")));

        mvc.perform(get("/generator.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("prefers-reduced-motion")));
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class StaticResourceApplication {
    }
}
