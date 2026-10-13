package enterfind.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for ConfigEncryptor.
 */
class ConfigEncryptorTest {

    private static final String TEST_KEY_BASE64 = "PykQmzYw2SbTShBb8g5gukpLjtFQRvMJ07UTmPTr2m8="; // 32 bytes

    @Test
    void testEncryptDecryptRoundTrip() {
        // Given
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        String plaintext = "{\"password\":\"secret123\",\"username\":\"admin\"}";

        // When
        String encrypted = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(encrypted);

        // Then
        assertThat(encrypted).startsWith("ENC:");
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void testEncryptProducesDifferentCiphertextEachTime() {
        // Given - same plaintext encrypted multiple times should produce different ciphertext (due to random IV)
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        String plaintext = "{\"secret\":\"value\"}";

        // When
        String encrypted1 = encryptor.encrypt(plaintext);
        String encrypted2 = encryptor.encrypt(plaintext);

        // Then
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        assertThat(encryptor.decrypt(encrypted1)).isEqualTo(plaintext);
        assertThat(encryptor.decrypt(encrypted2)).isEqualTo(plaintext);
    }

    @Test
    void testDecryptPlaintextPassesThrough() {
        // Given - unencrypted data should pass through unchanged (backward compatibility)
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        String plaintext = "{\"password\":\"secret\"}";

        // When
        String result = encryptor.decrypt(plaintext);

        // Then
        assertThat(result).isEqualTo(plaintext);
    }

    @Test
    void testEncryptionDisabledWithoutKey() {
        // Given
        ConfigEncryptor encryptor = createEncryptorWithKey("");
        String plaintext = "{\"password\":\"secret\"}";

        // When
        String result = encryptor.encrypt(plaintext);

        // Then - should return plaintext unchanged
        assertThat(result).isEqualTo(plaintext);
        assertThat(encryptor.isEncryptionEnabled()).isFalse();
    }

    @Test
    void testDecryptFailsWithoutKeyWhenDataIsEncrypted() {
        // Given - encrypt with key, then try to decrypt without key
        ConfigEncryptor encryptorWithKey = createEncryptorWithKey(TEST_KEY_BASE64);
        String encrypted = encryptorWithKey.encrypt("{\"password\":\"secret\"}");

        ConfigEncryptor encryptorWithoutKey = createEncryptorWithKey("");

        // When/Then
        assertThatThrownBy(() -> encryptorWithoutKey.decrypt(encrypted))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("encryption key not available");
    }

    @Test
    void testEncryptNullReturnsNull() {
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        assertThat(encryptor.encrypt(null)).isNull();
    }

    @Test
    void testEncryptEmptyReturnsEmpty() {
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        assertThat(encryptor.encrypt("")).isEmpty();
    }

    @Test
    void testDecryptNullReturnsNull() {
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void testDecryptEmptyReturnsEmpty() {
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        assertThat(encryptor.decrypt("")).isEmpty();
    }

    @Test
    void testIsEncrypted() {
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);

        assertThat(encryptor.isEncrypted("ENC:somedata")).isTrue();
        assertThat(encryptor.isEncrypted("{\"plain\":\"json\"}")).isFalse();
        assertThat(encryptor.isEncrypted(null)).isFalse();
        assertThat(encryptor.isEncrypted("")).isFalse();
    }

    @Test
    void testInvalidKeyLengthDisablesEncryption() {
        // Given - key that's not 32 bytes
        String shortKey = "c2hvcnRrZXk="; // "shortkey" in base64, only 8 bytes

        // When
        ConfigEncryptor encryptor = createEncryptorWithKey(shortKey);

        // Then
        assertThat(encryptor.isEncryptionEnabled()).isFalse();
    }

    @Test
    void testInvalidBase64DisablesEncryption() {
        // Given - invalid base64
        String invalidKey = "not-valid-base64!!!";

        // When
        ConfigEncryptor encryptor = createEncryptorWithKey(invalidKey);

        // Then
        assertThat(encryptor.isEncryptionEnabled()).isFalse();
    }

    @Test
    void testLargePayloadEncryption() {
        // Given
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        StringBuilder largePayload = new StringBuilder("{\"data\":\"");
        for (int i = 0; i < 10000; i++) {
            largePayload.append("x");
        }
        largePayload.append("\"}");
        String plaintext = largePayload.toString();

        // When
        String encrypted = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void testSpecialCharactersEncryption() {
        // Given
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        String plaintext = "{\"password\":\"p@$$w0rd!#$%^&*()_+{}[]|\\\\:;'<>?,./~`\"}";

        // When
        String encrypted = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void testUnicodeEncryption() {
        // Given
        ConfigEncryptor encryptor = createEncryptorWithKey(TEST_KEY_BASE64);
        String plaintext = "{\"password\":\"密码\",\"emoji\":\"🔐🔑\"}";

        // When
        String encrypted = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    /**
     * Helper to create a ConfigEncryptor with a specific key for testing.
     */
    private ConfigEncryptor createEncryptorWithKey(String base64Key) {
        return new ConfigEncryptor(base64Key);
    }
}
