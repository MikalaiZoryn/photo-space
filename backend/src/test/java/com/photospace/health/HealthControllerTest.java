package com.photospace.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private DatabaseProbe databaseProbe;

    @Test
    void reportsUpWhenDatabaseIsReachable() {
        given(databaseProbe.isUp()).willReturn(true);

        var result = mvc.get().uri("/api/health").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(result).bodyJson().extractingPath("$.database").isEqualTo("UP");
    }

    @Test
    void reportsServiceUnavailableWhenDatabaseIsDown() {
        given(databaseProbe.isUp()).willReturn(false);

        var result = mvc.get().uri("/api/health").exchange();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("DOWN");
        assertThat(result).bodyJson().extractingPath("$.database").isEqualTo("DOWN");
    }
}
