package enterfind.server.scan.treatment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for TreatmentUtils methods
 */
class TreatmentUtilsTest {

    @Test
    void testChunkText_NullInput_ReturnsEmptyList() {
        // Given
        String text = null;
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertTrue(chunks.isEmpty(), "Should return empty list for null input");
    }

    @Test
    void testChunkText_EmptyString_ReturnsEmptyList() {
        // Given
        String text = "";
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertTrue(chunks.isEmpty(), "Should return empty list for empty string");
    }

    @Test
    void testChunkText_TextSmallerThanMaxSize_ReturnsSingleChunk() {
        // Given
        String text = "Hello world";
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(1, chunks.size(), "Should return single chunk");
        assertEquals(text, chunks.get(0), "Chunk should contain entire text");
    }

    @Test
    void testChunkText_TextExactlyMaxSize_ReturnsSingleChunk() {
        // Given
        String text = "a".repeat(100);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(1, chunks.size(), "Should return single chunk");
        assertEquals(text, chunks.get(0), "Chunk should contain entire text");
    }

    @Test
    void testChunkText_TextLargerWithWhitespace_BreaksOnWhitespace() {
        // Given
        String text = "a".repeat(90) + " " + "b".repeat(90);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(2, chunks.size(), "Should return two chunks");
        assertTrue(chunks.get(0).endsWith(" "), "First chunk should include the whitespace");
        assertTrue(chunks.get(1).startsWith("b"), "Second chunk should start with 'b'");
        assertEquals(91, chunks.get(0).length(), "First chunk should be 90 a's + space");
        assertEquals(90, chunks.get(1).length(), "Second chunk should be 90 b's");
    }

    @Test
    void testChunkText_NoWhitespaceWithinRange_BreaksAtMaxSize() {
        // Given - no whitespace within 80% range (100 * 0.8 = 80)
        // Place whitespace at position 70 (before 80% threshold)
        String text = "a".repeat(70) + " " + "b".repeat(100);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(2, chunks.size(), "Should return two chunks");
        // Should break at maxChunkSize instead of the space at position 70
        assertEquals(100, chunks.get(0).length(), "First chunk should be exactly maxChunkSize");
        assertEquals(71, chunks.get(1).length(), "Second chunk should be remaining text");
    }

    @Test
    void testChunkText_WhitespaceExactlyAt80Percent_DoesNotBreakOnIt() {
        // Given - whitespace exactly at 80% threshold (100 * 0.8 = 80)
        String text = "a".repeat(80) + " " + "b".repeat(100);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        // The condition is lastWhitespace > startIndex + (maxChunkSize * 4 / 5)
        // So position 80 is NOT > 80, it should break at maxChunkSize instead
        assertEquals(2, chunks.size(), "Should return two chunks");
        assertEquals(100, chunks.get(0).length(), "First chunk should be exactly maxChunkSize");
    }

    @Test
    void testChunkText_WhitespaceJustAfter80Percent_BreaksOnIt() {
        // Given - whitespace at position 81 (just after 80% threshold of 80)
        String text = "a".repeat(81) + " " + "b".repeat(100);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(2, chunks.size(), "Should return two chunks");
        assertEquals(82, chunks.get(0).length(), "First chunk should be 81 a's + space");
        assertTrue(chunks.get(0).endsWith(" "), "First chunk should end with space");
        assertEquals(100, chunks.get(1).length(), "Second chunk should be 100 b's");
    }

    @Test
    void testChunkText_MultipleChunks_BreaksCorrectly() {
        // Given - create text that will produce 3 chunks
        // Chunk 1: 90 a's + space (91 chars)
        // Chunk 2: 90 b's + space (91 chars)
        // Chunk 3: 50 c's (50 chars)
        String text = "a".repeat(90) + " " + "b".repeat(90) + " " + "c".repeat(50);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(3, chunks.size(), "Should return three chunks");
        assertEquals(91, chunks.get(0).length());
        assertEquals(91, chunks.get(1).length());
        assertEquals(50, chunks.get(2).length());

        // Verify content
        assertTrue(chunks.get(0).startsWith("aaa"));
        assertTrue(chunks.get(1).startsWith("bbb"));
        assertTrue(chunks.get(2).startsWith("ccc"));
    }

    @Test
    void testChunkText_NoWhitespaceAtAll_BreaksAtMaxSize() {
        // Given - long text with no whitespace
        String text = "a".repeat(250);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(3, chunks.size(), "Should return three chunks");
        assertEquals(100, chunks.get(0).length());
        assertEquals(100, chunks.get(1).length());
        assertEquals(50, chunks.get(2).length());

        // All chunks should be continuous 'a's
        chunks.forEach(chunk -> {
            assertTrue(chunk.matches("a+"), "Each chunk should only contain 'a' characters");
        });
    }

    @Test
    void testChunkText_NewlineAsWhitespace_BreaksOnNewline() {
        // Given
        String text = "a".repeat(90) + "\n" + "b".repeat(90);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(2, chunks.size(), "Should return two chunks");
        assertTrue(chunks.get(0).endsWith("\n"), "First chunk should end with newline");
        assertEquals(91, chunks.get(0).length());
    }

    @Test
    void testChunkText_TabAsWhitespace_BreaksOnTab() {
        // Given
        String text = "a".repeat(90) + "\t" + "b".repeat(90);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(2, chunks.size(), "Should return two chunks");
        assertTrue(chunks.get(0).endsWith("\t"), "First chunk should end with tab");
        assertEquals(91, chunks.get(0).length());
    }

    @Test
    void testChunkText_MultipleWhitespacesInRange_BreaksOnLast() {
        // Given - multiple whitespaces within the valid range
        String text = "a".repeat(85) + " " + "b".repeat(5) + " " + "c".repeat(100);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(2, chunks.size(), "Should return two chunks");
        // Should break on the last whitespace found (at position 91)
        assertEquals(92, chunks.get(0).length(), "First chunk should break at second space");
        assertTrue(chunks.get(0).contains("b"), "First chunk should contain some b's");
    }

    @Test
    void testChunkText_VeryLongText_ProducesManyChunks() {
        // Given - text that produces many chunks
        String text = "word ".repeat(20000); // ~100,000 chars
        int maxChunkSize = 500;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertTrue(chunks.size() > 100, "Should produce many chunks");

        // Verify each chunk is within size limit
        chunks.forEach(chunk -> {
            assertTrue(chunk.length() <= maxChunkSize,
                "Each chunk should be at most maxChunkSize");
        });

        // Verify reassembly produces original text
        String reassembled = String.join("", chunks);
        assertEquals(text, reassembled, "Reassembled text should match original");
    }

    @Test
    void testChunkText_LastChunkSmallerThanMax_HandledCorrectly() {
        // Given
        String text = "a".repeat(90) + " " + "b".repeat(30);
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(2, chunks.size(), "Should return two chunks");
        assertEquals(91, chunks.get(0).length());
        assertEquals(30, chunks.get(1).length(), "Last chunk should be smaller");
    }

    @Test
    void testChunkText_RealWorldScenario_500KBChunks() {
        // Given - simulate real-world OCR/content text with paragraphs
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("This is paragraph number ").append(i).append(". ");
            sb.append("It contains some text that would be extracted from a document. ");
            sb.append("The text has multiple sentences and whitespace.\n\n");
        }
        String text = sb.toString();
        int maxChunkSize = TreatmentUtils.FTS_CHUNK_SIZE;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertTrue(chunks.size() >= 1, "Should produce at least one chunk");

        // Verify each chunk respects the limit
        chunks.forEach(chunk -> {
            assertTrue(chunk.length() <= maxChunkSize,
                "Chunk size should not exceed limit");
        });

        // Verify reassembly
        String reassembled = String.join("", chunks);
        assertEquals(text, reassembled, "Reassembled text should match original");

        // Verify chunks break on whitespace (not mid-word)
        for (int i = 0; i < chunks.size() - 1; i++) {
            String chunk = chunks.get(i);
            // Last character should be whitespace OR next chunk should start with continuation
            if (!Character.isWhitespace(chunk.charAt(chunk.length() - 1))) {
                // If not whitespace, we must have broken mid-word (no whitespace in range)
                // This is acceptable behavior
                System.out.println("Chunk " + i + " broke mid-word (no whitespace in 80% range)");
            }
        }
    }

    @Test
    void testChunkText_SingleCharacter_ReturnsSingleChunk() {
        // Given
        String text = "a";
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(1, chunks.size());
        assertEquals("a", chunks.get(0));
    }

    @Test
    void testChunkText_OnlyWhitespace_ReturnsSingleChunk() {
        // Given
        String text = "   \n\t  ";
        int maxChunkSize = 100;

        // When
        List<String> chunks = TreatmentUtils.chunkText(text, maxChunkSize);

        // Then
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    // -----------------------------------------------------------------------
    // chunkKeys
    // -----------------------------------------------------------------------

    @Test
    void testChunkKeys_SingleChunkDocument_KeepsBareKey() {
        assertEquals(List.of("ocr-content"), TreatmentUtils.chunkKeys("ocr-content", 0, 1));
    }

    @Test
    void testChunkKeys_SeveralChunksFromStart_AreAllNumbered() {
        // The scanned-PDF final flush used to give all three the bare key
        assertEquals(List.of("ocr-content-0", "ocr-content-1", "ocr-content-2"),
            TreatmentUtils.chunkKeys("ocr-content", 0, 3));
    }

    @Test
    void testChunkKeys_ContinuingAfterEarlierChunks_NumbersFromStartIndex() {
        assertEquals(List.of("ocr-content-4", "ocr-content-5"), TreatmentUtils.chunkKeys("ocr-content", 4, 2));
        // A lone last chunk of a longer document is still numbered
        assertEquals(List.of("content-4"), TreatmentUtils.chunkKeys("content", 4, 1));
    }

    @Test
    void testChunkKeys_NoChunks_ReturnsEmptyList() {
        assertEquals(List.of(), TreatmentUtils.chunkKeys("content", 0, 0));
    }

    // -----------------------------------------------------------------------
    // isNoiseAttribute — Predicate 1a: unknown_tag_ prefix
    // -----------------------------------------------------------------------

    @Test
    void testIsNoiseAttribute_UnknownTagPrefix_IsNoise() {
        assertTrue(TreatmentUtils.isNoiseAttribute("unknown_tag__0x0080_", "some value"));
    }

    @Test
    void testIsNoiseAttribute_UnknownTagPrefixExact_IsNoise() {
        assertTrue(TreatmentUtils.isNoiseAttribute("unknown_tag_", "x"));
    }

    @Test
    void testIsNoiseAttribute_SimilarButNotUnknownTag_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("tag_name", "Canon EOS R5"));
    }

    // -----------------------------------------------------------------------
    // isNoiseAttribute — Predicate 1b: no alphabetic characters in key
    // -----------------------------------------------------------------------

    @Test
    void testIsNoiseAttribute_AllDigitKey_IsNoise() {
        assertTrue(TreatmentUtils.isNoiseAttribute("1234", "some value"));
    }

    @Test
    void testIsNoiseAttribute_UnderscoresAndDigitsOnlyKey_IsNoise() {
        // Key contains only underscores and digits — no alphabetic characters
        assertTrue(TreatmentUtils.isNoiseAttribute("_0042_", "some value"));
    }

    // -----------------------------------------------------------------------
    // isNoiseAttribute — Predicate 1b: bare hex-ID key
    // -----------------------------------------------------------------------

    @Test
    void testIsNoiseAttribute_HexIdKeyWithUnderscores_IsNoise() {
        // Normalised form of a raw maker-note tag like "(0x004a)"
        assertTrue(TreatmentUtils.isNoiseAttribute("_0x004a_", "3648 2684682244"));
    }

    @Test
    void testIsNoiseAttribute_HexIdKeyNoUnderscores_IsNoise() {
        assertTrue(TreatmentUtils.isNoiseAttribute("0x80", "255"));
    }

    @Test
    void testIsNoiseAttribute_HexIdKeyDoubleUnderscores_IsNoise() {
        assertTrue(TreatmentUtils.isNoiseAttribute("__0xffff__", "some value"));
    }

    @Test
    void testIsNoiseAttribute_KeyWithHexLookingSubstring_IsNotNoise() {
        // "exif_0x8825" starts with a real word — not a bare hex ID
        assertFalse(TreatmentUtils.isNoiseAttribute("exif_0x8825", "GPS data"));
    }

    @Test
    void testIsNoiseAttribute_KeyWithAtLeastOneLetter_NotDroppedByPredicate1b() {
        // "a" has one letter; should not be dropped by predicate 1b
        // (may still pass if value is noise, but key alone doesn't trigger)
        assertFalse(TreatmentUtils.isNoiseAttribute("a1", "Canon"));
    }

    // -----------------------------------------------------------------------
    // isNoiseAttribute — Predicate 2a: Tika array-summary value
    // -----------------------------------------------------------------------

    @Test
    void testIsNoiseAttribute_TikaArraySummaryValue_IsNoise() {
        assertTrue(TreatmentUtils.isNoiseAttribute("some_key", "[22000 values]"));
    }

    @Test
    void testIsNoiseAttribute_TikaArraySummarySmall_IsNoise() {
        assertTrue(TreatmentUtils.isNoiseAttribute("some_key", "[1 values]"));
    }

    @Test
    void testIsNoiseAttribute_ValueLooksLikeArrayButHasText_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("some_key", "[not an array]"));
    }

    @Test
    void testIsNoiseAttribute_ValueWithBracketsButNotArrayPattern_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("some_key", "[22000 values] extra"));
    }

    // -----------------------------------------------------------------------
    // isNoiseAttribute — Predicate 2b: integer blob (>16 integer tokens)
    // -----------------------------------------------------------------------

    @Test
    void testIsNoiseAttribute_IntegerBlobAboveThreshold_IsNoise() {
        // 17 space-separated integers — over the threshold of 16
        String value = "1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17";
        assertTrue(TreatmentUtils.isNoiseAttribute("some_key", value));
    }

    @Test
    void testIsNoiseAttribute_IntegerBlobAtThreshold_IsNotNoise() {
        // Exactly 16 tokens — not over the threshold
        String value = "1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16";
        assertFalse(TreatmentUtils.isNoiseAttribute("some_key", value));
    }

    @Test
    void testIsNoiseAttribute_IntegerBlobWithNonInteger_IsNotNoise() {
        // 17 tokens but one is not an integer — should not match predicate 2b
        String value = "1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 abc";
        assertFalse(TreatmentUtils.isNoiseAttribute("some_key", value));
    }

    @Test
    void testIsNoiseAttribute_NegativeIntegerBlob_IsNoise() {
        String value = "-1 -2 -3 -4 -5 -6 -7 -8 -9 -10 -11 -12 -13 -14 -15 -16 -17";
        assertTrue(TreatmentUtils.isNoiseAttribute("some_key", value));
    }

    // -----------------------------------------------------------------------
    // isNoiseAttribute — Predicate 2c: low alphabetic ratio
    // -----------------------------------------------------------------------

    @Test
    void testIsNoiseAttribute_LowAlphaRatioBinaryBlob_IsNoise() {
        // 80 chars of digits/symbols — well below 15% alphabetic threshold
        String value = "12345678901234567890123456789012345678901234567890123456789012345678901234567890";
        assertTrue(TreatmentUtils.isNoiseAttribute("some_key", value));
    }

    @Test
    void testIsNoiseAttribute_ShortValueBelowLengthFloor_NotDroppedByRatio() {
        // Under 64 chars — ratio predicate doesn't apply; ensure not dropped
        String value = "123456789012345"; // 15 chars, all digits
        assertFalse(TreatmentUtils.isNoiseAttribute("some_key", value));
    }

    @Test
    void testIsNoiseAttribute_HighAlphaRatioLongValue_IsNotNoise() {
        // Long prose value — high alphabetic ratio
        String value = "Canon EOS R5 with a 45 megapixel sensor and impressive dynamic range performance";
        assertFalse(TreatmentUtils.isNoiseAttribute("some_key", value));
    }

    // -----------------------------------------------------------------------
    // isNoiseAttribute — legitimate EXIF / metadata fields must survive
    // -----------------------------------------------------------------------

    @Test
    void testIsNoiseAttribute_ExifMake_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("make", "Canon"));
    }

    @Test
    void testIsNoiseAttribute_ExifModel_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("model", "EOS R5"));
    }

    @Test
    void testIsNoiseAttribute_ExifDate_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("date_time_original", "2024:03:15 14:23:01"));
    }

    @Test
    void testIsNoiseAttribute_ImageDimensions_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("image_width", "4000"));
    }

    @Test
    void testIsNoiseAttribute_ContentType_IsNotNoise() {
        assertFalse(TreatmentUtils.isNoiseAttribute("content_type", "image/jpeg"));
    }
}
