package enterfind.server.scan.treatment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import enterfind.server.scan.treatment.OcrTreatmentHandler.LanguagePin;

/**
 * Unit tests for scanned-PDF language pinning. Each array is one document's per-page OSD results,
 * null meaning OSD produced nothing. No Tesseract needed.
 */
class OcrLanguagePinTest {

    /** Feeds detections in page order and asserts the language pins at expectedPage (1-based), or never if 0. */
    private static void assertPins(String expectedLang, int expectedPage, String... detections) {
        LanguagePin pin = LanguagePin.NONE;
        int pinnedAt = 0;
        for (int page = 0; page < detections.length && pinnedAt == 0; page++) {
            pin = pin.observe(detections[page]);
            if (pin.pinned() != null) {
                pinnedAt = page + 1;
            }
        }
        assertEquals(expectedPage, pinnedAt, Arrays.toString(detections));
        assertEquals(expectedLang, pin.pinned(), Arrays.toString(detections));
    }

    @Test
    void failedDetectionsNeverAgree() {
        // The bug: two blank opening pages both "detected" eng and pinned it for a Russian book
        assertPins("rus", 4, null, null, "rus", "rus");
        assertPins(null, 0, null, null, null);
    }

    @Test
    void firstTwoPagesAgreeingPinsAtTheSecond() {
        assertPins("ara", 2, "ara", "ara");
    }

    @Test
    void disagreementDefersThePin() {
        assertPins("rus", 3, "eng", "rus", "rus");
    }

    @Test
    void aFailureBetweenAgreeingDetectionsDoesNotResetThem() {
        assertPins("rus", 3, "rus", null, "rus");
    }

    @Test
    void realDefaultDetectionsCanPin() {
        // An unrecognised or uninstalled script is a real result that resolves to eng
        assertPins("eng", 2, "eng", "eng");
    }

    @Test
    void onceSetThePinDoesNotMove() {
        LanguagePin pin = LanguagePin.NONE.observe("rus").observe("rus");
        assertEquals("rus", pin.observe("ara").observe("ara").pinned());
        assertNull(LanguagePin.NONE.observe(null).pinned());
    }
}
