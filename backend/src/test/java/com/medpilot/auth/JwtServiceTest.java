package com.medpilot.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    @Test
    void rejectsMissingOrShortSecrets() {
        assertThatThrownBy(() -> new JwtService(null, 900_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(" ", 900_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("x".repeat(31), 900_000))
                .isInstanceOf(IllegalStateException.class);
    }
}
