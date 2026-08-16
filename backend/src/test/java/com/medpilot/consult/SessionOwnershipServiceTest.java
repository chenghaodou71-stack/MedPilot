package com.medpilot.consult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(SessionOwnershipService.class)
@ActiveProfiles("test")
class SessionOwnershipServiceTest {

    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";

    @Autowired
    SessionOwnershipService service;

    @Autowired
    ConsultationSessionRepository sessions;

    @BeforeEach
    void clearSessions() {
        sessions.deleteAll();
    }

    @Test
    void firstUseClaimsSessionForAuthenticatedUser() {
        ConsultationSession claimed = service.claim(SESSION_ID, 11L);

        assertThat(claimed.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(claimed.getUserId()).isEqualTo(11L);
        assertThat(sessions.findBySessionId(SESSION_ID)).isPresent();
    }

    @Test
    void sameUserCanContinueClaimedSession() {
        service.claim(SESSION_ID, 11L);

        ConsultationSession claimedAgain = service.claim(SESSION_ID, 11L);

        assertThat(claimedAgain.getUserId()).isEqualTo(11L);
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void differentUserCannotReuseClaimedSession() {
        service.claim(SESSION_ID, 11L);

        assertThatThrownBy(() -> service.claim(SESSION_ID, 22L))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("session");
    }

    @Test
    void invalidSessionIdIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.claim("not-a-uuid", 11L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.count()).isZero();
    }
}
