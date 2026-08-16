package com.medpilot.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataEncryptionServiceTest {

    private static final String KEY = key("0123456789abcdef0123456789abcdef");
    private static final String OTHER_KEY = key("abcdef0123456789abcdef0123456789");

    @Test
    void roundTripsWithAUniqueNonceForEveryEncryption() {
        DataEncryptionService service = new DataEncryptionService(KEY);

        String first = service.encrypt("sensitive medical text");
        String second = service.encrypt("sensitive medical text");

        assertThat(first).startsWith("enc:v1:").isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo("sensitive medical text");
        assertThat(service.decrypt(second)).isEqualTo("sensitive medical text");
    }

    @Test
    void rejectsTamperedCiphertextAndWrongKey() {
        DataEncryptionService service = new DataEncryptionService(KEY);
        String encrypted = service.encrypt("sensitive medical text");
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(DataEncryptionException.class);
        assertThatThrownBy(() -> new DataEncryptionService(OTHER_KEY).decrypt(encrypted))
                .isInstanceOf(DataEncryptionException.class);
    }

    @Test
    void rejectsMissingMalformedAndNon256BitKeys() {
        assertThatThrownBy(() -> new DataEncryptionService(" "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DataEncryptionService("not-base64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DataEncryptionService(key("too-short")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsLegacyPlaintextForMigrationCompatibility() {
        assertThat(new DataEncryptionService(KEY).decrypt("legacy plaintext"))
                .isEqualTo("legacy plaintext");
    }

    @Test
    void decryptsOldKeyRingDataAfterActiveKeyRotation() {
        String rotated = key("fedcba9876543210fedcba9876543210");
        DataEncryptionService old = new DataEncryptionService(KEY);
        String oldCiphertext = old.encrypt("legacy patient note");
        DataEncryptionService rotatedService = new DataEncryptionService(
                KEY, "v2=" + rotated, "v2");

        assertThat(rotatedService.decrypt(oldCiphertext)).isEqualTo("legacy patient note");
        String newCiphertext = rotatedService.encrypt("new patient note");
        assertThat(newCiphertext).startsWith("enc:v1:v2:");
        assertThat(rotatedService.decrypt(newCiphertext)).isEqualTo("new patient note");
        assertThatThrownBy(() -> new DataEncryptionService(KEY).decrypt(newCiphertext))
                .isInstanceOf(DataEncryptionException.class);
    }

    private static String key(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
