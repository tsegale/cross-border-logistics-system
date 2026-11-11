package com.freightflow.logistics.support;

import com.freightflow.logistics.security.SecurityConfig;
import com.freightflow.logistics.security.SecurityProblems;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * logistics' real security chain in every MockMvc test, and an operator's token on every request unless the test sets
 * its own with {@code .with(TestCallers.as(...))}: a request's own post-processors run after the default ones. The
 * authorisation matrix tests set the caller on every request.
 */
@TestConfiguration
@Import({SecurityConfig.class, SecurityProblems.class})
public class TestSecurity {

    @Bean
    MockMvcBuilderCustomizer operatorByDefault() {
        return builder -> builder.defaultRequest(get("/").with(TestCallers.operator()));
    }
}
