package com.omnidude.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublicRateLimiterTest {

    @Test
    void allowsTheLimitThenRefusesUntilTheWindowEnds() {
        PublicRateLimiter l = new PublicRateLimiter();
        long t = 1_000_000;
        for (int i = 0; i < PublicRateLimiter.LIMIT_PER_MINUTE; i++) {
            assertThat(l.allow("1.2.3.4", t + i)).isTrue();
        }
        assertThat(l.allow("1.2.3.4", t + 1000)).isFalse();
        assertThat(l.allow("5.6.7.8", t + 1000)).as("cada origem tem o seu limite").isTrue();
        assertThat(l.allow("1.2.3.4", t + 60_001)).as("a janela seguinte recomeça").isTrue();
    }
}
