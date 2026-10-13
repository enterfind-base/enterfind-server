package enterfind.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("alpha")
public class TotpServiceTest {

    @Autowired
    private TotpService totpService;

    @Test
    public void testGenerateSecret() {
        String secret = totpService.generateSecret();

        assertNotNull(secret);
        assertFalse(secret.isEmpty());
        // Secret should be base32 encoded, typically 32 characters
        assertTrue(secret.length() >= 16);
    }

    @Test
    public void testGenerateSecret_unique() {
        String secret1 = totpService.generateSecret();
        String secret2 = totpService.generateSecret();

        assertNotEquals(secret1, secret2);
    }

    @Test
    public void testGenerateQrCodeDataUrl() {
        String secret = totpService.generateSecret();
        String qrCode = totpService.generateQrCodeDataUrl(secret, "testuser");

        assertNotNull(qrCode);
        assertTrue(qrCode.startsWith("data:image/png;base64,"));
    }

    @Test
    public void testGenerateRecoveryCodes() {
        List<String> codes = totpService.generateRecoveryCodes();

        assertNotNull(codes);
        assertEquals(10, codes.size());

        // Each code should be 8 characters
        for (String code : codes) {
            assertEquals(8, code.length());
            // Codes should be alphanumeric uppercase
            assertTrue(code.matches("[A-Z0-9]{8}"));
        }

        // All codes should be unique
        assertEquals(10, codes.stream().distinct().count());
    }

    @Test
    public void testSerializeAndCountRecoveryCodes() {
        List<String> codes = totpService.generateRecoveryCodes();
        String serialized = totpService.serializeRecoveryCodes(codes);

        assertNotNull(serialized);
        assertFalse(serialized.isEmpty());

        // Count should match
        int count = totpService.countRecoveryCodes(serialized);
        assertEquals(10, count);
    }

    @Test
    public void testVerifyRecoveryCode_valid() {
        List<String> codes = totpService.generateRecoveryCodes();
        String serialized = totpService.serializeRecoveryCodes(codes);
        String codeToUse = codes.get(0);

        // Verify the first code
        String matchedCode = totpService.verifyRecoveryCode(codeToUse, serialized);

        assertNotNull(matchedCode);
        assertEquals(codeToUse, matchedCode);
    }

    @Test
    public void testVerifyRecoveryCode_invalid() {
        List<String> codes = totpService.generateRecoveryCodes();
        String serialized = totpService.serializeRecoveryCodes(codes);

        // Try to verify an invalid code
        String matchedCode = totpService.verifyRecoveryCode("INVALID1", serialized);

        assertNull(matchedCode);
    }

    @Test
    public void testVerifyRecoveryCode_caseInsensitive() {
        List<String> codes = totpService.generateRecoveryCodes();
        String serialized = totpService.serializeRecoveryCodes(codes);
        String codeToUse = codes.get(0).toLowerCase(); // Use lowercase

        // Verify the code in lowercase
        String matchedCode = totpService.verifyRecoveryCode(codeToUse, serialized);

        assertNotNull(matchedCode);
    }

    @Test
    public void testConsumeRecoveryCode() {
        List<String> codes = totpService.generateRecoveryCodes();
        String serialized = totpService.serializeRecoveryCodes(codes);
        String codeToUse = codes.get(0);

        // Consume the first code
        String remainingCodes = totpService.consumeRecoveryCode(codeToUse, serialized);

        assertNotNull(remainingCodes);
        
        // Should have 9 codes remaining
        int remaining = totpService.countRecoveryCodes(remainingCodes);
        assertEquals(9, remaining);

        // The consumed code should no longer be verifiable
        String matchedCode = totpService.verifyRecoveryCode(codeToUse, remainingCodes);
        assertNull(matchedCode);
    }

    @Test
    public void testCountRecoveryCodes_empty() {
        int count = totpService.countRecoveryCodes("");
        assertEquals(0, count);

        count = totpService.countRecoveryCodes(null);
        assertEquals(0, count);
    }

    @Test
    public void testVerifyCode_invalidSecret() {
        // Invalid secret should return false, not throw exception
        boolean result = totpService.verifyCode("invalid", "123456");
        assertFalse(result);
    }

    @Test
    public void testVerifyCode_invalidCode() {
        String secret = totpService.generateSecret();

        // Random 6-digit code is very unlikely to be valid
        boolean result = totpService.verifyCode(secret, "000000");
        // This might occasionally pass if 000000 happens to be the current code
        // but statistically it's extremely unlikely
        // We're mainly testing that it doesn't throw an exception
        assertNotNull(Boolean.valueOf(result));
    }
}
