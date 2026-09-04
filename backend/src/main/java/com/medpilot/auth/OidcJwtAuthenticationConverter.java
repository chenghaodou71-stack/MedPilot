package com.medpilot.auth;

import com.medpilot.user.IdentityProvider;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a validated hospital OIDC token to a pre-bound local account.
 *
 * Token role/group claims are deliberately ignored. Local role, organization,
 * employee number and MFA approval remain authoritative in the database.
 */
public final class OidcJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Pattern ASSURANCE_NUMBER =
            Pattern.compile("(?:aal|loa|level|assurance)[^0-9]*([0-9]+)", Pattern.CASE_INSENSITIVE);

    private final UserRepository users;
    private final String mfaClaim;
    private final int requiredMfaAssuranceLevel;

    public OidcJwtAuthenticationConverter(
            UserRepository users,
            String mfaClaim,
            int requiredMfaAssuranceLevel) {
        this.users = users;
        this.mfaClaim = requiredClaimName(mfaClaim);
        if (requiredMfaAssuranceLevel < 1 || requiredMfaAssuranceLevel > 9) {
            throw new IllegalArgumentException("OIDC MFA assurance level must be between 1 and 9");
        }
        this.requiredMfaAssuranceLevel = requiredMfaAssuranceLevel;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw invalid("OIDC subject is missing");
        }
        int tokenAssurance = assuranceLevel(jwt.getClaims().get(mfaClaim));
        if (tokenAssurance < requiredMfaAssuranceLevel) {
            throw invalid("OIDC MFA assurance is insufficient");
        }

        Optional<User> bound = users.findByIdentityProviderAndExternalSubject(
                IdentityProvider.OIDC, jwt.getSubject().strip());
        User user = bound
                .filter(candidate -> candidate.isLoginEligibleAt(Instant.now()))
                .filter(candidate -> candidate.getIdentityProvider() == IdentityProvider.OIDC)
                .filter(candidate -> candidate.getExternalSubject() != null
                        && candidate.getExternalSubject().equals(jwt.getSubject().strip()))
                .filter(candidate -> candidate.getMfaAssuranceLevel() >= requiredMfaAssuranceLevel)
                .orElseThrow(() -> invalid("OIDC subject is not pre-bound to an eligible account"));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        user.getUsername(),
                        jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        authentication.setDetails(Map.of(
                "identityProvider", IdentityProvider.OIDC.name(),
                "externalSubject", user.getExternalSubject(),
                "mfaAssuranceLevel", tokenAssurance));
        return authentication;
    }

    /** Visible for deterministic unit tests and IdP contract tests. */
    public static int assuranceLevel(Object claim) {
        if (claim == null) return 0;
        if (claim instanceof Number number) {
            return clamp(number.intValue());
        }
        if (claim instanceof Collection<?> values) {
            int max = 0;
            for (Object value : values) max = Math.max(max, assuranceLevel(value));
            return max;
        }
        String value = String.valueOf(claim).strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return 0;
        try {
            return clamp(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            // Continue with common ACR/AMR representations such as AAL2 or otp.
        }
        Matcher matcher = ASSURANCE_NUMBER.matcher(value);
        if (matcher.find()) {
            try {
                return clamp(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        if (value.contains("aal2") || value.contains("loa2")
                || value.contains("level2") || value.contains("mfa")
                || value.contains("otp") || value.contains("webauthn")
                || value.contains("hwk")) {
            return 2;
        }
        if (value.contains("aal1") || value.contains("loa1")
                || value.contains("level1") || value.equals("pwd")) {
            return 1;
        }
        return 0;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(9, value));
    }

    private static String requiredClaimName(String claim) {
        String normalized = claim == null ? "" : claim.strip();
        if (normalized.isEmpty() || !normalized.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("OIDC MFA claim name is invalid");
        }
        return normalized;
    }

    private static InvalidBearerTokenException invalid(String message) {
        return new InvalidBearerTokenException(message);
    }
}
