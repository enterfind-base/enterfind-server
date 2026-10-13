package enterfind.server.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handles encryption and decryption of datasource configuration.
 *
 * Key is loaded from environment variable ENTERFIND_ENCRYPTION_KEY
 * (or Spring property enterfind.encryption.key).
 *
 * If no key is available, encryption is disabled and plaintext is used.
 *
 * Encryption uses AES-256-GCM for authenticated encryption.
 * Encrypted values are prefixed with "ENC:" followed by base64(iv + ciphertext).
 *
 * Generate a key with: openssl rand -base64 32
 */
@Component
public class ConfigEncryptor {

    private static final Logger logger = LoggerFactory.getLogger(ConfigEncryptor.class);

    private static final String ENCRYPTED_PREFIX = "ENC:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128 bits for authentication tag
    private static final int REQUIRED_KEY_LENGTH = 32; // 256 bits

    private SecretKey secretKey;
    private boolean encryptionEnabled;

    public ConfigEncryptor(@Value("${ENTERFIND_ENCRYPTION_KEY:}") String encryptionKey) {
        if (encryptionKey != null && !encryptionKey.isBlank()) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(encryptionKey.trim());
                if (keyBytes.length != REQUIRED_KEY_LENGTH) {
                    logger.error("Encryption key must be {} bytes ({} bits). Got {} bytes. Encryption disabled.",
                            REQUIRED_KEY_LENGTH, REQUIRED_KEY_LENGTH * 8, keyBytes.length);
                    encryptionEnabled = false;
                    return;
                }
                this.secretKey = new SecretKeySpec(keyBytes, "AES");
                this.encryptionEnabled = true;
                logger.info("Encryption enabled for datasource configuration");
            } catch (IllegalArgumentException e) {
                logger.error("Invalid base64 encryption key: {}. Encryption disabled.", e.getMessage());
                encryptionEnabled = false;
            }
        } else {
            logger.warn("No encryption key configured (ENTERFIND_ENCRYPTION_KEY). "
                    + "Datasource credentials will be stored in plaintext.");
            encryptionEnabled = false;
        }
    }

    /**
     * Check if encryption is enabled.
     */
    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    /**
     * Encrypt a plaintext string.
     * If encryption is disabled, returns the plaintext unchanged.
     *
     * @param plaintext the string to encrypt
     * @return encrypted string prefixed with "ENC:" or plaintext if encryption disabled
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }

        if (!encryptionEnabled) {
            return plaintext;
        }

        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine IV and ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            // Return prefixed base64
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);

        } catch (Exception e) {
            logger.error("Encryption failed: {}", e.getMessage());
            throw new RuntimeException("Failed to encrypt configuration", e);
        }
    }

    /**
     * Decrypt an encrypted string.
     * If the string is not encrypted (no "ENC:" prefix), returns it unchanged.
     * This allows backward compatibility with existing unencrypted data.
     *
     * @param ciphertext the encrypted string (with "ENC:" prefix) or plaintext
     * @return decrypted string
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }

        // If not encrypted, return as-is (backward compatibility)
        if (!ciphertext.startsWith(ENCRYPTED_PREFIX)) {
            return ciphertext;
        }

        if (!encryptionEnabled) {
            logger.error("Cannot decrypt: encryption key not available. "
                    + "Data was encrypted but no key is configured.");
            throw new RuntimeException("Cannot decrypt configuration: encryption key not available");
        }

        try {
            // Decode base64
            String base64Data = ciphertext.substring(ENCRYPTED_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(base64Data);

            // Extract IV and ciphertext
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encryptedBytes = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encryptedBytes, 0, encryptedBytes.length);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Decrypt
            byte[] plaintext = cipher.doFinal(encryptedBytes);
            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (Exception e) {
            logger.error("Decryption failed: {}", e.getMessage());
            throw new RuntimeException("Failed to decrypt configuration", e);
        }
    }

    /**
     * Check if a string is encrypted (has the ENC: prefix).
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }
}
