package com.sportshop.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaForwardControllerTest {
    @Test
    void forwardsKnownClientRoutesAndLeavesApiPathsUnhandled() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

        mvc.perform(get("/catalog")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/sales/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
    }
}
