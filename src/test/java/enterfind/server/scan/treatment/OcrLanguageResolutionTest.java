package enterfind.server.scan.treatment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for how OcrTreatmentHandler maps an OSD script to a Tesseract -l value. No Tesseract needed.
 */
class OcrLanguageResolutionTest {

    private static final Pattern LANG_CODE = Pattern.compile("^[A-Za-z_]+(\\+[A-Za-z_]+)*$");

    @TempDir
    Path tessDir;

    @Test
    void everyCandidateIsAValidTessdataStem() {
        // A hyphen ("chi-sim") is the Debian package spelling, never a loadable model name
        OcrTreatmentHandler.SCRIPT_TO_LANGS.forEach((script, candidates) -> {
            assertFalse(candidates.isEmpty(), "No candidates for script " + script);
            for (String candidate : candidates) {
                assertTrue(LANG_CODE.matcher(candidate).matches(),
                        "Invalid -l value '" + candidate + "' for script " + script);
            }
        });
    }

    @Test
    void koreanOsdNameMapsToKoreanModel() {
        // Tesseract 5 OSD prints "Script: Korean" for a Korean page; without this key it fell back to eng
        assertEquals(List.of("kor"), OcrTreatmentHandler.SCRIPT_TO_LANGS.get("Korean"));
    }

    @Test
    void resolveLang_PrefersFirstFullyInstalledCandidate() {
        List<String> cyrillic = List.of("Cyrillic", "rus");

        assertEquals("Cyrillic", OcrTreatmentHandler.resolveLang(cyrillic,
                Map.of("Cyrillic", "Cyrillic", "rus", "rus")));
        assertEquals("rus", OcrTreatmentHandler.resolveLang(cyrillic, Map.of("rus", "rus")));
        assertNull(OcrTreatmentHandler.resolveLang(cyrillic, Map.of()));
    }

    @Test
    void resolveLang_FallsBackToInstalledSubsetOfLastCandidate() {
        assertEquals("eng+fra", OcrTreatmentHandler.resolveLang(List.of("eng+fra+deu+spa"),
                Map.of("eng", "eng", "fra", "fra")));
    }

    @Test
    void resolveLang_UsesInstalledToken() {
        assertEquals("script/Cyrillic", OcrTreatmentHandler.resolveLang(List.of("Cyrillic", "rus"),
                Map.of("Cyrillic", "script/Cyrillic")));
    }

    @Test
    void scanInstalled_FindsRootAndScriptSubdirModels() throws IOException {
        Files.createFile(tessDir.resolve("eng.traineddata"));
        Files.createFile(tessDir.resolve("pdf.ttf"));
        Files.createDirectories(tessDir.resolve("script"));
        Files.createFile(tessDir.resolve("script/Cyrillic.traineddata"));

        assertEquals(Map.of("eng", "eng", "Cyrillic", "script/Cyrillic"),
                OcrTreatmentHandler.scanInstalled(tessDir.toFile()));
    }

    @Test
    void scanInstalled_RootWinsOverScriptSubdir() throws IOException {
        Files.createFile(tessDir.resolve("Cyrillic.traineddata"));
        Files.createDirectories(tessDir.resolve("script"));
        Files.createFile(tessDir.resolve("script/Cyrillic.traineddata"));

        assertEquals(Map.of("Cyrillic", "Cyrillic"), OcrTreatmentHandler.scanInstalled(tessDir.toFile()));
    }

    @Test
    void scanInstalled_MissingDirectoryIsEmpty() {
        assertEquals(Map.of(), OcrTreatmentHandler.scanInstalled(tessDir.resolve("absent").toFile()));
    }
}
