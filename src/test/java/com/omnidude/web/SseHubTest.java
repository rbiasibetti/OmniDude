package com.omnidude.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SseHubTest {

    @Test
    void thereIsACapOnOpenStreams() {
        SseHub hub = new SseHub();
        for (int i = 0; i < SseHub.MAX_STREAMS; i++) {
            assertThat(hub.subscribe()).isNotNull();
        }
        assertThatThrownBy(hub::subscribe).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
