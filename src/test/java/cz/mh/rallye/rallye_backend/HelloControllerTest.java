package cz.mh.rallye.rallye_backend;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HelloControllerTest {
    @Test
    void helloRemainsAvailableWithoutDatabase() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HelloController()).build();
        mvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Rallye backend funguje"));
    }
}
