package com.medpilot.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final DataEncryptionService encryption;

    public EncryptedStringConverter(
            @Value("${medpilot.data-encryption-key}") String encodedKey,
            @Value("${medpilot.data-encryption-keys:}") String keyRing,
            @Value("${medpilot.data-encryption-active-key-id:v1}") String activeKeyId) {
        this.encryption = new DataEncryptionService(encodedKey, keyRing, activeKeyId);
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryption.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryption.decrypt(dbData);
    }
}
