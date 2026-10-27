package io.github.yinwenyuan28.ailite.admin.ui;

import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自带管理页静态资源。
 */
public class AdminPageTest {

    @Test
    public void pageListsThreeTabs() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AdminPageController())
                .setMessageConverters(new ByteArrayHttpMessageConverter())
                .build();
        mockMvc.perform(get("/ai-lite/admin").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("供应商")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("业务与提示词")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("调用日志")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("写入数据库")));
        mockMvc.perform(get("/ai-lite/admin/assets/admin.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ai-lite/admin/api")));
    }
}
