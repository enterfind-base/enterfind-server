package enterfind.server.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;

/**
 * Service for TOTP (Time-based One-Time Password) operations.
 */
@Service
public class TotpService {

    private static final String ISSUER = "EnterFind";
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_LENGTH = 8;
    private static final String RECOVERY_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final SecretGenerator secretGenerator;
    private final CodeGenerator codeGenerator;
    private final CodeVerifier codeVerifier;
    private final QrGenerator qrGenerator;
    private final SecureRandom secureRandom;

    public TotpService() {
        this.secretGenerator = new DefaultSecretGenerator();
        this.codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);
        TimeProvider timeProvider = new SystemTimeProvider();
        this.codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        this.qrGenerator = new ZxingPngQrGenerator();
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generate a new TOTP secret.
     */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Generate the otpauth:// URI for a TOTP secret.
     */
    public String generateQrCodeUri(String secret, String nickname) {
        QrData data = new QrData.Builder()
            .label(nickname)
            .secret(secret)
            .issuer(ISSUER)
            .algorithm(HashingAlgorithm.SHA1)
            .digits(6)
            .period(30)
            .build();
        return data.getUri();
    }

    /**
     * Generate a QR code as a base64 data URL.
     */
    public String generateQrCodeDataUrl(String secret, String nickname) {
        QrData data = new QrData.Builder()
            .label(nickname)
            .secret(secret)
            .issuer(ISSUER)
            .algorithm(HashingAlgorithm.SHA1)
            .digits(6)
            .period(30)
            .build();

        try {
            byte[] imageData = qrGenerator.generate(data);
            String mimeType = qrGenerator.getImageMimeType();
            return Utils.getDataUriForImage(imageData, mimeType);
        } catch (QrGenerationException e) {
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    /**
     * Verify a TOTP code against a secret.
     */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code);
    }

    /**
     * Generate a list of recovery codes.
     */
    public List<String> generateRecoveryCodes() {
        return generateRecoveryCodes(RECOVERY_CODE_COUNT);
    }

    /**
     * Generate a specified number of recovery codes.
     */
    public List<String> generateRecoveryCodes(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(generateRecoveryCode());
        }
        return codes;
    }

    /**
     * Generate a single recovery code.
     */
    private String generateRecoveryCode() {
        StringBuilder code = new StringBuilder(RECOVERY_CODE_LENGTH);
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            int index = secureRandom.nextInt(RECOVERY_CODE_CHARS.length());
            code.append(RECOVERY_CODE_CHARS.charAt(index));
        }
        return code.toString();
    }

    /**
     * Serialize recovery codes to a comma-separated string for storage.
     */
    public String serializeRecoveryCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        return codes.stream().collect(Collectors.joining(","));
    }

    /**
     * Deserialize recovery codes from a comma-separated string.
     */
    public List<String> deserializeRecoveryCodes(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(serialized.split(",")));
    }

    /**
     * Verify a recovery code against the stored codes.
     * Returns the code if valid, null otherwise.
     */
    public String verifyRecoveryCode(String code, String serializedCodes) {
        if (code == null || serializedCodes == null) {
            return null;
        }

        List<String> codes = deserializeRecoveryCodes(serializedCodes);
        String normalizedCode = code.toUpperCase().trim();

        for (String storedCode : codes) {
            if (storedCode.equals(normalizedCode)) {
                return storedCode;
            }
        }
        return null;
    }

    /**
     * Remove a used recovery code from the list and return the updated serialized string.
     */
    public String consumeRecoveryCode(String usedCode, String serializedCodes) {
        List<String> codes = deserializeRecoveryCodes(serializedCodes);
        codes.remove(usedCode);
        return codes.isEmpty() ? null : serializeRecoveryCodes(codes);
    }

    /**
     * Count remaining recovery codes.
     */
    public int countRecoveryCodes(String serializedCodes) {
        if (serializedCodes == null || serializedCodes.isBlank()) {
            return 0;
        }
        return serializedCodes.split(",").length;
    }
}
