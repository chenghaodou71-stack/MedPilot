package com.medpilot.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** AES-GCM service with a small key ring for non-disruptive key rotation. */
@Component
public class DataEncryptionService {

    private static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] LEGACY_FILE_PREFIX = {'M', 'P', 'A', '1'};
    private static final byte[] KEYED_FILE_PREFIX = {'M', 'P', 'A', '2'};

    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public DataEncryptionService(
            @Value("${medpilot.data-encryption-key}") String encodedKey,
            @Value("${medpilot.data-encryption-keys:}") String keyRing,
            @Value("${medpilot.data-encryption-active-key-id:v1}") String activeKeyId) {
        this.keys = parseKeys(encodedKey, keyRing);
        this.activeKeyId = normalizeId(activeKeyId == null || activeKeyId.isBlank() ? "v1" : activeKeyId);
        if (!keys.containsKey(this.activeKeyId)) {
            throw new IllegalStateException("active encryption key id is not present in key ring");
        }
    }

    /** Compatibility constructor for unit tests and small command-line tools. */
    public DataEncryptionService(String encodedKey) {
        this(encodedKey, "", "v1");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        byte[] nonce = randomNonce();
        try {
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce)
                    .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            // Keep the historical prefix while adding an explicit key id after it.
            return PREFIX + activeKeyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new DataEncryptionException("medical data encryption failed", ex);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || !storedValue.startsWith(PREFIX)) return storedValue;
        String remainder = storedValue.substring(PREFIX.length());
        String keyId = "v1";
        String encodedPayload = remainder;
        int separator = remainder.indexOf(':');
        if (separator > 0) {
            keyId = normalizeId(remainder.substring(0, separator));
            encodedPayload = remainder.substring(separator + 1);
        }
        return decryptPayload(encodedPayload, keyId);
    }

    /** Encrypts a bounded attachment stream without loading the whole file into memory. */
    public void encryptStream(InputStream plaintext, OutputStream destination) {
        byte[] nonce = randomNonce();
        byte[] id = activeKeyId.getBytes(StandardCharsets.US_ASCII);
        if (id.length > 32) throw new IllegalStateException("encryption key id is too long");
        try {
            destination.write(KEYED_FILE_PREFIX);
            destination.write(id.length);
            destination.write(id);
            destination.write(nonce);
            try (CipherOutputStream encrypted = new CipherOutputStream(
                    destination, cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce))) {
                plaintext.transferTo(encrypted);
            }
        } catch (GeneralSecurityException | IOException ex) {
            throw new DataEncryptionException("attachment encryption failed", ex);
        }
    }

    /** Decrypts both legacy MPA1 streams and key-ring aware MPA2 streams. */
    public void decryptStream(InputStream encrypted, OutputStream destination) {
        try {
            byte[] prefix = encrypted.readNBytes(4);
            String keyId;
            if (Arrays.equals(prefix, LEGACY_FILE_PREFIX)) {
                keyId = "v1";
            } else if (Arrays.equals(prefix, KEYED_FILE_PREFIX)) {
                int length = encrypted.read();
                if (length < 1 || length > 32) throw new GeneralSecurityException("invalid key id length");
                keyId = normalizeId(new String(encrypted.readNBytes(length), StandardCharsets.US_ASCII));
            } else {
                throw new GeneralSecurityException("invalid attachment ciphertext prefix");
            }
            byte[] nonce = encrypted.readNBytes(NONCE_BYTES);
            if (nonce.length != NONCE_BYTES) throw new GeneralSecurityException("attachment ciphertext is too short");
            try (CipherInputStream plaintext = new CipherInputStream(
                    encrypted, cipher(Cipher.DECRYPT_MODE, keyFor(keyId), nonce))) {
                plaintext.transferTo(destination);
            }
        } catch (GeneralSecurityException | IOException ex) {
            throw new DataEncryptionException("attachment decryption failed", ex);
        }
    }

    private String decryptPayload(String encodedPayload, String keyId) {
        try {
            byte[] payload = Base64.getDecoder().decode(encodedPayload);
            if (payload.length <= NONCE_BYTES) throw new GeneralSecurityException("ciphertext is too short");
            byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
            return new String(cipher(Cipher.DECRYPT_MODE, keyFor(keyId), nonce)
                    .doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new DataEncryptionException("stored medical data failed authentication", ex);
        }
    }

    private SecretKeySpec keyFor(String keyId) throws GeneralSecurityException {
        SecretKeySpec key = keys.get(keyId);
        if (key == null) throw new GeneralSecurityException("unknown encryption key id");
        return key;
    }

    private Cipher cipher(int mode, SecretKeySpec key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return nonce;
    }

    private static Map<String, SecretKeySpec> parseKeys(String encodedKey, String keyRing) {
        if (encodedKey == null || encodedKey.isBlank()) throw new IllegalStateException("MEDPILOT_DATA_ENCRYPTION_KEY is required");
        Map<String, SecretKeySpec> result = new LinkedHashMap<>();
        result.put("v1", decodeKey(encodedKey, "MEDPILOT_DATA_ENCRYPTION_KEY"));
        if (keyRing != null && !keyRing.isBlank()) {
            for (String entry : keyRing.split("[,;]")) {
                String value = entry.trim();
                if (value.isBlank()) continue;
                int separator = value.indexOf('=');
                if (separator < 0) separator = value.indexOf(':');
                if (separator <= 0 || separator == value.length() - 1) throw new IllegalStateException("MEDPILOT_DATA_ENCRYPTION_KEYS must use id=base64 entries");
                String id = normalizeId(value.substring(0, separator));
                result.put(id, decodeKey(value.substring(separator + 1), "MEDPILOT_DATA_ENCRYPTION_KEYS"));
            }
        }
        return Map.copyOf(result);
    }

    private static SecretKeySpec decodeKey(String encoded, String name) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded.trim());
            if (bytes.length != KEY_BYTES) throw new IllegalStateException(name + " must decode to 32 bytes");
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(name + " must be Base64", ex);
        }
    }

    private static String normalizeId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[A-Za-z0-9._-]{1,32}")) throw new IllegalStateException("invalid encryption key id");
        return id;
    }
}
