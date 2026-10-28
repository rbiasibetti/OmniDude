package com.omnidude.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    private LoginAttemptService service;
    private final Instant t0 = Instant.parse("2026-01-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("admin")).thenReturn(Optional.of(new User()));
        when(users.findByUsername("ana")).thenReturn(Optional.of(new User()));
        service = new LoginAttemptService(users);
    }

    private void fail(String user, String ip, int times, Instant at) {
        for (int i = 0; i < times; i++) {
            service.failure(user, ip, at.plusSeconds(i));
        }
    }

    @Test
    void fiveFailuresFromOneSourceLockOnlyThatSource() {
        fail("admin", "10.0.0.1", 5, t0);
        assertThat(service.isLocked("admin", "10.0.0.1", t0.plusSeconds(10))).isTrue();
        assertThat(service.isLocked("admin", "10.0.0.2", t0.plusSeconds(10)))
                .as("quem erra de um IP não tranca o usuário para o resto do mundo").isFalse();
        assertThat(service.isLocked("ana", "10.0.0.1", t0.plusSeconds(10))).isFalse();
    }

    @Test
    void theLockExpires() {
        fail("admin", "10.0.0.1", 5, t0);
        assertThat(service.isLocked("admin", "10.0.0.1", t0.plus(LoginAttemptService.LOCK).plusSeconds(10))).isFalse();
    }

    @Test
    void failuresSpreadOverManySourcesStillTripTheUserCeiling() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES_ANY_SOURCE; i++) {
            service.failure("admin", "10.1.0." + i, t0.plusSeconds(i));            // nenhum IP passa de 1 falha
        }
        assertThat(service.isLocked("admin", "10.9.9.9", t0.plusSeconds(60))).as("teto por usuário, de qualquer origem").isTrue();
    }

    @Test
    void oldFailuresDecayInsteadOfAccumulatingForever() {
        fail("admin", "10.0.0.1", 4, t0);
        service.failure("admin", "10.0.0.1", t0.plus(LoginAttemptService.WINDOW).plusSeconds(60));       // 5ª, mas 15 min depois
        assertThat(service.isLocked("admin", "10.0.0.1", t0.plus(LoginAttemptService.WINDOW).plusSeconds(61))).isFalse();
    }

    @Test
    void unknownUsernamesAreNotTrackedSoInventedNamesCannotFillTheMemory() {
        for (int i = 0; i < 1000; i++) {
            service.failure("inventado-" + i, "10.0.0.1", t0);
        }
        assertThat(service.isLocked("inventado-1", "10.0.0.1", t0)).isFalse();
        fail("inventado-1", "10.0.0.1", 10, t0);
        assertThat(service.isLocked("inventado-1", "10.0.0.1", t0)).isFalse();
    }
}
