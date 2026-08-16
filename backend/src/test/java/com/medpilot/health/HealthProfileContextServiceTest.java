package com.medpilot.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthProfileContextServiceTest {

    private final HealthProfileRepository profiles = mock(HealthProfileRepository.class);
    private final HealthProfileContextService service =
            new HealthProfileContextService(profiles, new ObjectMapper());

    @Test
    void returnsOnlyWhitelistedFieldsFromTheAuthenticatedUsersConsentedProfile() {
        HealthProfile profile = new HealthProfile(
                7L,
                "{\"allergies\":\" 青霉素 \",\"conditions\":\"哮喘\","
                        + "\"medications\":\"吸入药\",\"notes\":\"带既往检查\","
                        + "\"password\":\"must-not-leak\",\"nested\":{\"secret\":\"x\"}}",
                true);
        when(profiles.findByUserIdAndConsentGrantedTrue(7L)).thenReturn(Optional.of(profile));

        assertThat(service.resolveForUser(7L)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "allergies", "青霉素",
                "conditions", "哮喘",
                "medications", "吸入药",
                "notes", "带既往检查"));
        verify(profiles).findByUserIdAndConsentGrantedTrue(7L);
    }

    @Test
    void doesNotResolveUnconsentedOrEmptyProfiles() {
        when(profiles.findByUserIdAndConsentGrantedTrue(7L)).thenReturn(Optional.empty());

        assertThat(service.resolveForUser(7L)).isEmpty();
        assertThat(service.resolveForUser(null)).isEmpty();
    }

    @Test
    void failsClosedForMalformedOrMismatchedProfilesAndDropsBlankValues() {
        HealthProfile malformed = new HealthProfile(7L, "{not-json", true);
        when(profiles.findByUserIdAndConsentGrantedTrue(7L))
                .thenReturn(Optional.of(malformed));
        assertThat(service.resolveForUser(7L)).isEmpty();

        HealthProfile wrongOwner = new HealthProfile(8L, "{\"conditions\":\"secret\"}", true);
        when(profiles.findByUserIdAndConsentGrantedTrue(7L))
                .thenReturn(Optional.of(wrongOwner));
        assertThat(service.resolveForUser(7L)).isEmpty();

        HealthProfile values = new HealthProfile(
                7L,
                "{\"allergies\":\"  \",\"conditions\":123,\"notes\":\"ok\"}",
                true);
        when(profiles.findByUserIdAndConsentGrantedTrue(7L)).thenReturn(Optional.of(values));
        assertThat(service.resolveForUser(7L)).containsExactly(Map.entry("notes", "ok"));
    }
}
