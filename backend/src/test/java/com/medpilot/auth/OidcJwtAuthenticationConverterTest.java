package com.medpilot.auth;

import com.medpilot.user.IdentityProvider;
import com.medpilot.user.Role;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcJwtAuthenticationConverterTest {

    @Test
    void mapsOnlyPreBoundOidcUserAndUsesLocalRole() {
        UserRepository users = mock(UserRepository.class);
        User user = boundUser("sub-123", Role.DOCTOR, 2);
        when(users.findByIdentityProviderAndExternalSubject(IdentityProvider.OIDC, "sub-123"))
                .thenReturn(Optional.of(user));
        OidcJwtAuthenticationConverter converter =
                new OidcJwtAuthenticationConverter(users, "acr", 2);

        Authentication authentication = converter.convert(jwt("sub-123", "AAL2", "ADMIN"));

        assertThat(authentication.getName()).isEqualTo(user.getUsername());
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_DOCTOR");
    }

    @Test
    void rejectsUnknownSubjectWithoutAutoProvisioning() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByIdentityProviderAndExternalSubject(eq(IdentityProvider.OIDC), eq("unknown")))
                .thenReturn(Optional.empty());
        OidcJwtAuthenticationConverter converter =
                new OidcJwtAuthenticationConverter(users, "acr", 2);

        assertThatThrownBy(() -> converter.convert(jwt("unknown", "AAL2", null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("pre-bound");
    }

    @Test
    void rejectsTokenAndLocalAccountWhenEitherMfaAssuranceIsInsufficient() {
        UserRepository users = mock(UserRepository.class);
        User localMfaTooLow = boundUser("sub-low", Role.DOCTOR, 1);
        when(users.findByIdentityProviderAndExternalSubject(IdentityProvider.OIDC, "sub-low"))
                .thenReturn(Optional.of(localMfaTooLow));
        OidcJwtAuthenticationConverter converter =
                new OidcJwtAuthenticationConverter(users, "acr", 2);

        assertThatThrownBy(() -> converter.convert(jwt("sub-low", "AAL2", null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("pre-bound");
        assertThatThrownBy(() -> converter.convert(jwt("sub-low", "AAL1", null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MFA assurance");
    }

    @Test
    void parsesNumericAcrAndCommonAmrValues() {
        assertThat(OidcJwtAuthenticationConverter.assuranceLevel(2)).isEqualTo(2);
        assertThat(OidcJwtAuthenticationConverter.assuranceLevel("urn:loa:2")).isEqualTo(2);
        assertThat(OidcJwtAuthenticationConverter.assuranceLevel(List.of("pwd", "otp"))).isEqualTo(2);
        assertThat(OidcJwtAuthenticationConverter.assuranceLevel("pwd")).isEqualTo(1);
        assertThat(OidcJwtAuthenticationConverter.assuranceLevel(null)).isZero();
    }

    private static User boundUser(String subject, Role role, int mfa) {
        User user = new User("oidc-" + subject, "unused-password-hash", role);
        user.setFederatedIdentity(IdentityProvider.OIDC, subject);
        user.setHospitalStaffProfile("EMP-" + subject, "HOSP-A", "MAIN", "ED");
        user.setMfaAssuranceLevel(mfa);
        return user;
    }

    private static Jwt jwt(String subject, Object acr, String tokenRole) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuer("https://idp.example.test")
                .audience(List.of("medpilot"))
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("acr", acr);
        if (tokenRole != null) builder.claim("role", tokenRole);
        return builder.build();
    }
}
