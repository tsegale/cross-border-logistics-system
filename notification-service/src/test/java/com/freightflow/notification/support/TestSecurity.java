package com.freightflow.notification.support;

import com.freightflow.notification.security.SecurityConfig;
import com.freightflow.notification.security.SecurityProblems;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** notification-service's real security chain in every MockMvc test; each request sets its caller. */
@TestConfiguration
@Import({SecurityConfig.class, SecurityProblems.class})
public class TestSecurity {
}
