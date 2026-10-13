package enterfind.server.util;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import enterfind.server.exception.InvalidArgumentException;

/**
 * Unit tests for CronExpressionValidator
 */
@DisplayName("CronExpressionValidator Tests")
class CronExpressionValidatorTest {
    
    // ============ Valid 5-field cron expressions ============
    
    @ParameterizedTest
    @ValueSource(strings = {
        "0 0 * * *",      // Every day at midnight
        "0 12 * * *",     // Every day at noon
        "*/15 * * * *",   // Every 15 minutes
        "0 0 1 * *",      // First day of every month
        "0 0 * * 0",      // Every Sunday
        "30 2 * * *",     // 2:30 AM every day
        "0,30 * * * *",   // Every hour at :00 and :30
        "0 9-17 * * *",   // 9 AM to 5 PM every hour
        "0 0 1-7 * *",    // First week of every month
        "0 0 * 1-6 *",    // January to June at midnight
        "0 0 * * 1-5",    // Monday to Friday at midnight
        "*/5 9-17 * * 1-5", // Every 5 minutes during business hours
    })
    @DisplayName("Should accept valid 5-field cron expressions")
    void testValid5FieldCronExpressions(String cronExpression) {
        assertTrue(CronExpressionValidator.isValid(cronExpression));
    }
    
    // ============ Valid 6-field cron expressions (NOT supported by cron-utils) ============
    // Removed: cron-utils only supports Unix 5-field format, not 6-field Quartz format
    
    // ============ Invalid field count ============
    
    @ParameterizedTest
    @ValueSource(strings = {
        "0",                     // 1 field
        "0 0",                   // 2 fields
        "0 0 *",                 // 3 fields
        "0 0 * *",               // 4 fields
        "0 0 * * * * *",         // 7 fields
        "0 0 * * * * * *",       // 8 fields
    })
    @DisplayName("Should reject cron expressions with invalid field count")
    void testInvalidFieldCount(String cronExpression) {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid(cronExpression));
    }
    
    // ============ Null and empty values ============
    
    @Test
    @DisplayName("Should accept null cron expression (optional field)")
    void testNullCronExpression() {
        assertTrue(CronExpressionValidator.isValid(null));
    }
    
    @Test
    @DisplayName("Should accept empty cron expression (optional field)")
    void testEmptyCronExpression() {
        assertTrue(CronExpressionValidator.isValid(""));
    }
    
    @Test
    @DisplayName("Should accept whitespace-only cron expression (optional field)")
    void testWhitespaceCronExpression() {
        assertTrue(CronExpressionValidator.isValid("   "));
    }
    
    // ============ Out-of-range values ============
    
    @ParameterizedTest
    @ValueSource(strings = {
        "60 0 * * *",      // Minute out of range (max 59)
        "0 24 * * *",      // Hour out of range (max 23)
        "0 0 * 13 *",      // Month out of range (max 12)
    })
    @DisplayName("Should reject cron expressions with out-of-range values")
    void testOutOfRangeValues(String cronExpression) {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid(cronExpression));
    }
    
    // ============ Invalid range syntax ============
    
    @Test
    @DisplayName("Should reject cron expression 10-5 * * * *")
    void testInvalidRangeSyntax1() {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid("10-5 * * * *"));
    }
    
    @Test
    @DisplayName("Should reject cron expression 0 20-5 * * *")
    void testInvalidRangeSyntax2() {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid("0 20-5 * * *"));
    }
    
    @Test
    @DisplayName("Should reject cron expression 0 0 1-32 * *")
    void testInvalidRangeSyntax3() {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid("0 0 1-32 * *"));
    }
    
    @Test
    @DisplayName("Should reject cron expression 0 0-24 * * *")
    void testInvalidRangeSyntax4() {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid("0 0-24 * * *"));
    }
    
    @Test
    @DisplayName("Should reject cron expression with step value of 0")
    void testStepValueZero() {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid("*/0 * * * *"));
    }
    
    // ============ Non-numeric values ============
    
    @ParameterizedTest
    @ValueSource(strings = {
        "abc * * * *",      // Non-numeric in minute field
        "0 xyz * * *",      // Non-numeric in hour field
        "0 0 def * *",      // Non-numeric in day field
        "0 0 * ghi *",      // Non-numeric in month field
        "0 0 * * jkl",      // Non-numeric in day-of-week field
        "0-abc * * * *",    // Non-numeric in range
        "0/xyz * * * *",    // Non-numeric step value
        "0,abc,5 * * * *",  // Non-numeric in list
    })
    @DisplayName("Should reject cron expressions with non-numeric values")
    void testNonNumericValues(String cronExpression) {
        assertThrows(InvalidArgumentException.class, () -> CronExpressionValidator.isValid(cronExpression));
    }
    
    // ============ Edge cases for step values ============
    
    @ParameterizedTest
    @ValueSource(strings = {
        "*/5 * * * *",      // Step with wildcard
        "0-30/5 * * * *",   // Step with range
        "10-20/2 * * * *",  // Step with specific range
        "0,1,2 * * * *",    // List values
        "0,10,20,30 * * * *", // List with multiple values
    })
    @DisplayName("Should accept valid step and list expressions")
    void testValidStepAndListExpressions(String cronExpression) {
        assertTrue(CronExpressionValidator.isValid(cronExpression));
    }
    
    // ============ Whitespace handling ============
    
    @Test
    @DisplayName("Should handle leading and trailing whitespace")
    void testWhitespaceHandling() {
        assertTrue(CronExpressionValidator.isValid("  0 0 * * *  "));
    }
    
    @Test
    @DisplayName("Should handle extra spaces between fields")
    void testExtraSpacesBetweenFields() {
        // cron-utils automatically normalizes whitespace, so extra spaces are acceptable
        assertTrue(CronExpressionValidator.isValid("0  0  *  *  *"));
    }
    
    // ============ Boundary values ============
    
    @Test
    @DisplayName("Should accept boundary minute value (0)")
    void testBoundaryMinute0() {
        assertTrue(CronExpressionValidator.isValid("0 0 * * *"));
    }
    
    @Test
    @DisplayName("Should accept boundary minute value (59)")
    void testBoundaryMinute59() {
        assertTrue(CronExpressionValidator.isValid("59 0 * * *"));
    }
    
    @Test
    @DisplayName("Should accept boundary hour value (0)")
    void testBoundaryHour0() {
        assertTrue(CronExpressionValidator.isValid("0 0 * * *"));
    }
    
    @Test
    @DisplayName("Should accept boundary hour value (23)")
    void testBoundaryHour23() {
        assertTrue(CronExpressionValidator.isValid("0 23 * * *"));
    }
    
    @Test
    @DisplayName("Should accept boundary day value (1)")
    void testBoundaryDay1() {
        assertTrue(CronExpressionValidator.isValid("0 0 1 * *"));
    }
    
    @Test
    @DisplayName("Should accept boundary day value (31)")
    void testBoundaryDay31() {
        assertTrue(CronExpressionValidator.isValid("0 0 31 * *"));
    }
    
    @Test
    @DisplayName("Should accept boundary month value (1)")
    void testBoundaryMonth1() {
        assertTrue(CronExpressionValidator.isValid("0 0 * 1 *"));
    }
    
    @Test
    @DisplayName("Should accept boundary month value (12)")
    void testBoundaryMonth12() {
        assertTrue(CronExpressionValidator.isValid("0 0 * 12 *"));
    }
    
    @Test
    @DisplayName("Should accept boundary day-of-week value (0)")
    void testBoundaryDayOfWeek0() {
        assertTrue(CronExpressionValidator.isValid("0 0 * * 0"));
    }
    
    @Test
    @DisplayName("Should accept boundary day-of-week value (6)")
    void testBoundaryDayOfWeek6() {
        assertTrue(CronExpressionValidator.isValid("0 0 * * 6"));
    }
}
