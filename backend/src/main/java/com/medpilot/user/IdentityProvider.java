package com.medpilot.user;

/** Source that established the user's identity. Roles are always locally approved. */
public enum IdentityProvider {
    LOCAL,
    OIDC,
    SAML,
    LDAP
}
