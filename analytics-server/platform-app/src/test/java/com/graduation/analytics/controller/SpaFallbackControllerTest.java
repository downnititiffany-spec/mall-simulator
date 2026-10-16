package com.graduation.analytics.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class SpaFallbackControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpaFallbackController()).build();
    }

    @Test
    void nestedSourceWizardRouteForwardsToSpaEntryPoint() throws Exception {
        mockMvc.perform(get("/sources/wizard").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(view().name("forward:/index.html"));
    }

    @Test
    void topLevelVueRouteStillForwardsToSpaEntryPoint() throws Exception {
        mockMvc.perform(get("/overview").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(view().name("forward:/index.html"));
    }

    @Test
    void apiPathIsNotCapturedBySpaFallback() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
    }
}
