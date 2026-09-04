package com.medpilot.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    void trackedKeysNeverExceedTheConfiguredHardLimit() {
        LoginAttemptService service = new LoginAttemptService(5, 300, 900, 3, Clock.systemUTC());

        for (int index = 0; index < 20; index++) {
            service.recordFailure("127.0.0." + index, "user-" + index);
        }

        assertThat(service.trackedKeyCount()).isLessThanOrEqualTo(3);
    }
}
