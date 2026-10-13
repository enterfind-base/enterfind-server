package enterfind.server.scan.treatment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.TestFileUtils;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Item;
import enterfind.server.scan.LocalFilesDataProvider;
import enterfind.server.scan.TreatmentOutput;

/**
 * A scanned PDF whose opening pages are blank must not have its language pinned from them: OSD
 * fails on a blank page, and two failures are not two detections that agree. Real Tesseract.
 */
class OcrPdfLanguagePinIntegrationTest extends BaseDatabaseTest {

    /** Render DPI used by the handler; page size is chosen so the image comes back at its own resolution. */
    private static final float RENDER_DPI = 200f;

    @Autowired
    private OcrTreatmentHandler ocrHandler;

    private Path tempDir;

    @BeforeEach
    void setUpTest() throws Exception {
        tempDir = Files.createTempDirectory("ocr-pdf-language-pin-test-");
    }

    @AfterEach
    void tearDownTest() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempDir);
        }
    }

    @Test
    void blankOpeningPagesDoNotPinEnglish() throws Exception {
        Path pdf = tempDir.resolve("blank-then-russian.pdf");
        writeBlankThenImagePdf(pdf, new ClassPathResource("ocr-test-images/russian-example.png").getFile().toPath(), 2, 3);

        LocalFileSystemDataSource dataSource = new LocalFileSystemDataSource(
            null, "OCR pin test", "0 0 * * *", LocalDateTime.now(), null,
            DataSourceStatus.IDLE, tempDir.toString(), null);
        Item item = new Item();
        item.setName(pdf.getFileName().toString());
        item.setSizeBytes(Files.size(pdf));

        TreatmentOutput output = ocrHandler.apply(item, new LocalFilesDataProvider(dataSource), dataSource, "OCR");

        String ocrText = output.results().stream()
            .filter(r -> r.getKey().startsWith(FieldNameConstants.FIELD_OCR_CONTENT))
            .map(r -> r.getValue())
            .collect(Collectors.joining("\n"));
        assertFalse(ocrText.isBlank(), "Should have OCR text from the Russian pages");
        // Read with an English model these come back as Latin look-alikes, not Cyrillic
        assertTrue(ocrText.contains("Дочь") || ocrText.contains("дочь"),
            "Russian pages should be OCR'd with a Cyrillic model, got: " + ocrText);
        assertTrue(ocrText.contains("матери") || ocrText.contains("Матери"),
            "Russian pages should be OCR'd with a Cyrillic model, got: " + ocrText);
    }

    private static void writeBlankThenImagePdf(Path target, Path image, int blankPages, int imagePages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject img = PDImageXObject.createFromFile(image.toString(), doc);
            PDRectangle size = new PDRectangle(img.getWidth() * 72f / RENDER_DPI, img.getHeight() * 72f / RENDER_DPI);
            for (int i = 0; i < blankPages; i++) {
                doc.addPage(new PDPage(size));
            }
            for (int i = 0; i < imagePages; i++) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(img, 0, 0, size.getWidth(), size.getHeight());
                }
            }
            doc.save(target.toFile());
        }
    }
}
