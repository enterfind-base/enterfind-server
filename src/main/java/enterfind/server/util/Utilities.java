package enterfind.server.util;

import java.security.SecureRandom;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Component;

/**
 * Utility class for common operations across the application
 */
@Component
public class Utilities {

    private static final char[] CHAR_ARRAY = "0123456789abcdef".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Generate a unique 16-character hex ID using Apache Commons
     * @return 16-character hexadecimal string
     */
    public static String generateHexId() {
        return RandomStringUtils.random(16, 0, 16, false, false,
            CHAR_ARRAY, SECURE_RANDOM);
    }

    /**
     * Generate a random alphanumeric ID of specified length
     * @param length the desired length of the ID
     * @return random alphanumeric string
     */
    public static String generateAlphanumericId(int length) {
        return RandomStringUtils.secure().nextAlphanumeric(length);
    }

    /**
     * Generate a random numeric ID of specified length
     * @param length the desired length of the ID
     * @return random numeric string
     */
    public static String generateNumericId(int length) {
        return RandomStringUtils.secure().nextNumeric(length);
    }
}