package com.medpilot.health;

import com.medpilot.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Long-lived patient profile, stored as encrypted JSON to keep PHI out of columns/logs. */
@Entity
@Table(name = "health_profiles")
public class HealthProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "profile_json", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String profileJson;

    @Column(name = "consent_granted", nullable = false)
    private boolean consentGranted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected HealthProfile() {
    }

    public HealthProfile(Long userId, String profileJson, boolean consentGranted) {
        this.userId = userId;
        this.profileJson = profileJson;
        this.consentGranted = consentGranted;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getProfileJson() { return profileJson; }
    public boolean isConsentGranted() { return consentGranted; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String profileJson, boolean consentGranted) {
        this.profileJson = profileJson;
        this.consentGranted = consentGranted;
        this.updatedAt = Instant.now();
    }
}
