
package enterfind.server.scan.treatment;
import static enterfind.server.model.FieldNameConstants.FIELD_CONTENT_TRUNCATED;
import static enterfind.server.model.FieldNameConstants.FIELD_OCR_CONTENT;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.util.ResizableSemaphore;
import jakarta.annotation.PostConstruct;

/**
 * Treatment handler that performs OCR (Optical Character Recognition) on images and PDFs
 * by spawning the tesseract CLI as an external process.
 *
 * Each OCR call spawns a new tesseract subprocess. A crash in the native tesseract binary
 * kills only that subprocess — the main server process is unaffected.
 */
@Component
public class OcrTreatmentHandler implements TreatmentHandler {
    private static final ResizableSemaphore semaphore = new ResizableSemaphore(2);
    private static final Logger logger = LoggerFactory.getLogger(OcrTreatmentHandler.class);

    // @VisibleForTesting: coordinate callers around concurrency-slot acquisition without spawning Tesseract.
    volatile Runnable beforeSemaphoreAcquireHook = null;
    volatile Runnable semaphoreAcquiredHook = null;

    private static final String tessDataPath;

    static {
        String path = System.getenv("TESSDATA_PREFIX");
        if (path == null || path.isEmpty()) {
            String[] possiblePaths = {
                "/usr/share/tesseract-ocr/5/tessdata",    // Ubuntu 24.04+
                "/usr/share/tesseract-ocr/4.00/tessdata", // Ubuntu 20.04/22.04
                "/usr/share/tessdata",                     // Generic location
                "/usr/local/share/tessdata",               // macOS Homebrew
                "/opt/homebrew/share/tessdata"             // macOS Apple Silicon
            };
            for (String candidate : possiblePaths) {
                File dir = new File(candidate);
                if (dir.exists() && new File(dir, "eng.traineddata").exists()) {
                    path = candidate;
                    break;
                }
            }
            if (path == null || path.isEmpty()) {
                path = "/usr/share/tesseract-ocr/5/tessdata";
                logger.warn("Could not find tessdata directory, using default: {}", path);
            }
        }
        tessDataPath = path;
        logger.info("Tesseract CLI configured with datapath: {}", tessDataPath);
    }


    private final UserSettingsDao userSettingsDao;

    public OcrTreatmentHandler(UserSettingsDao userSettingsDao) {
        this.userSettingsDao = userSettingsDao;
    }

    @PostConstruct
    void registerSettingObservers() {
        updateSemaphoreFromValue(userSettingsDao.getSetting(UserSettingName.CONCURRENT_OCR_HANDLERS).orElse("2"));
        userSettingsDao.addSettingObserver(UserSettingName.CONCURRENT_OCR_HANDLERS, OcrTreatmentHandler::updateSemaphoreFromValue);
    }

    private static void updateSemaphoreFromValue(String value) {
        int limit = 2;
        try {
            limit = Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            logger.warn("Invalid ConcurrentOCRHandlers value: {}", value);
        }
        semaphore.resize(limit);
        logger.info("OCR concurrency limit updated to {}", limit);
    }



    @Override
    public boolean canProcess(Item item) {
        String ext = TreatmentUtils.getFileExtension(item.getName());
        if (TreatmentUtils.isSupportedImageExtension(ext)) {
            Long sizeBytes = item.getSizeBytes();
            if (sizeBytes != null && sizeBytes > HandlerLimits.MAX_IMAGE_SIZE_BYTES) {
                logger.info("Skipping OCR for {}: file size {}MB exceeds image limit of {}MB",
                    item.getFullPath(), sizeBytes / (1024 * 1024), HandlerLimits.MAX_IMAGE_SIZE_BYTES / (1024 * 1024));
                return false;
            }
            return true;
        }
        if ("pdf".equals(ext)) {
            Long sizeBytes = item.getSizeBytes();
            if (sizeBytes != null && sizeBytes > HandlerLimits.MAX_PDF_SIZE_BYTES) {
                logger.info("Skipping OCR for {}: file size {}MB exceeds PDF limit of {}MB",
                    item.getFullPath(), sizeBytes / (1024 * 1024), HandlerLimits.MAX_PDF_SIZE_BYTES / (1024 * 1024));
                return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public TreatmentOutput apply(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        return TreatmentOutput.of(applyResults(item, dataProvider, dataSource, treatmentName));
    }

    private List<TreatmentResult> applyResults(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        try {
            Runnable beforeAcquireHook = beforeSemaphoreAcquireHook;
            if (beforeAcquireHook != null) {
                beforeAcquireHook.run();
            }
            semaphore.acquire();
            try {
                Runnable acquiredHook = semaphoreAcquiredHook;
                if (acquiredHook != null) {
                    acquiredHook.run();
                }
                List<TreatmentResult> results = new ArrayList<>();
                String extension = TreatmentUtils.getFileExtension(item.getName());
                if (TreatmentUtils.isSupportedImageExtension(extension)) {
                    results.addAll(processImageFile(item, dataProvider, treatmentName));
                } else if ("pdf".equals(extension)) {
                    results.addAll(processPdfFile(item, dataProvider, treatmentName));
                } else {
                    logger.info("OCR treatment skipped for unsupported file type: {}", item.getName());
                }
                return results;
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("OCR handler interrupted while waiting for concurrency slot", e);
            throw new IllegalStateException("OCR interrupted while waiting for concurrency slot", e);
        }
    }

    private List<TreatmentResult> processImageFile(Item item, DataProviderInterface dataProvider, String treatmentName) {
        List<TreatmentResult> results = new ArrayList<>();

        // Add treatment type identifier
        results.add(new TreatmentResult(FIELD_TREATMENT_TYPE, treatmentName));

        File tempFile = null;

        try (InputStream inputStream = dataProvider.getInputStream(item)) {
            if (inputStream == null) {
                logger.warn("Unable to get input stream for OCR on item: {}", item.getFullPath());
                throw new IllegalStateException("Unable to get input stream for OCR: " + item.getFullPath());
            }

            // Create temporary file for Tesseract
            tempFile = Files.createTempFile("ocr-", "-" + item.getName()).toFile();
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Run Tesseract via command line
            String ocrText = runTesseract(tempFile);

            if (ocrText != null && !ocrText.trim().isEmpty()) {
                String sanitizedText = TreatmentUtils.sanitizeForUtf8Storage(ocrText.trim());
                if (!sanitizedText.isEmpty()) {
                    // Limit to HandlerLimits.MAX_OCR_CONTENT_LENGTH
                    if (sanitizedText.length() > HandlerLimits.MAX_OCR_CONTENT_LENGTH) {
                        sanitizedText = sanitizedText.substring(0, HandlerLimits.MAX_OCR_CONTENT_LENGTH);
                        results.add(new TreatmentResult(FIELD_CONTENT_TRUNCATED, "true"));
                    }

                    // Chunk OCR content to ensure full searchability in FTS
                    List<String> chunks = TreatmentUtils.chunkText(sanitizedText, TreatmentUtils.FTS_CHUNK_SIZE);

                    List<String> keys = TreatmentUtils.chunkKeys(FIELD_OCR_CONTENT, 0, chunks.size());
                    for (int i = 0; i < chunks.size(); i++) {
                        results.add(new TreatmentResult(keys.get(i), chunks.get(i)));
                    }

                    logger.info("OCR extracted {} characters from image: {} (split into {} chunks)",
                            sanitizedText.length(), item.getName(), chunks.size());
                }
            }

        } catch (Exception e) {
            logger.error("Error performing OCR on file {}: {}", item.getFullPath(), e.getMessage(), e);
            throw new IllegalStateException("OCR failed for " + item.getFullPath(), e);
        } finally {
            TreatmentUtils.cleanupTempFile(tempFile);
        }

        return results;
    }

    private List<TreatmentResult> processPdfFile(Item item, DataProviderInterface dataProvider, String treatmentName) {
        List<TreatmentResult> results = new ArrayList<>();

        // Add treatment type identifier
        results.add(new TreatmentResult(FIELD_TREATMENT_TYPE, treatmentName));

        File tempFile = null;
        PDDocument document = null;

        try (InputStream inputStream = dataProvider.getInputStream(item)) {
            if (inputStream == null) {
                logger.warn("Unable to get input stream for OCR on PDF: {}", item.getFullPath());
                throw new IllegalStateException("Unable to get input stream for OCR: " + item.getFullPath());
            }

            // Create temporary file for PDF processing
            tempFile = Files.createTempFile("ocr-pdf-", "-" + item.getName()).toFile();
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Load PDF document using PDFBox 3.x API
            document = Loader.loadPDF(tempFile);
            
            // Check if this is a "true" PDF with extractable text content
            // True PDFs (created digitally) have text that can be extracted directly
            // Scanned PDFs (created from images) have little or no extractable text
            if (isTruePdf(document)) {
                logger.info("Skipping OCR for PDF with extractable text content: {}", item.getName());
                return results;
            }
            
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            // Use streaming accumulator to reduce memory usage
            // Accumulates up to 3x chunk size, then emits complete chunks
            StringBuilder pageAccumulator = new StringBuilder();
            int pageCount = document.getNumberOfPages();
            boolean contentTruncated = false;
            int pagesProcessed = 0;
            int chunkIndex = 0;
            int totalCharactersExtracted = 0;
            final int ACCUMULATOR_THRESHOLD = TreatmentUtils.FTS_CHUNK_SIZE * 3; // 1.5 MB

            // Render each page to image and OCR.
            // Run OSD on each page until two real detections agree, then reuse that language for the rest.
            LanguagePin pin = LanguagePin.NONE;

            for (int page = 0; page < pageCount; page++) {
                // Check if we've exceeded the content limit
                if (totalCharactersExtracted >= HandlerLimits.MAX_OCR_CONTENT_LENGTH) {
                    contentTruncated = true;
                    logger.info("OCR content limit reached at page {} of {}, stopping extraction for: {}",
                            page, pageCount, item.getName());
                    break;
                }

                try {
                    // Render page at 200 DPI for balance of quality vs memory
                    BufferedImage image = pdfRenderer.renderImageWithDPI(page, 200);

                    // Until pinned, run OSD on every page to detect the script language.
                    // Once pinned, skip the per-page OSD overhead.
                    String pageText;
                    if (pin.pinned() == null) {
                        File tempPageFile = Files.createTempFile("ocr-page-", ".png").toFile();
                        try {
                            ImageIO.write(image, "png", tempPageFile);
                            String detectedLang = detectLanguage(tempPageFile);
                            pin = pin.observe(detectedLang);
                            if (pin.pinned() != null) {
                                logger.info("Two OSD detections agree on language '{}', skipping OSD for remaining pages", pin.pinned());
                            }
                            pageText = runTesseract(tempPageFile, detectedLang != null ? detectedLang : DEFAULT_LANG);
                        } finally {
                            TreatmentUtils.cleanupTempFile(tempPageFile);
                        }
                    } else {
                        pageText = runTesseract(image, pin.pinned());
                    }

                    // Release native image resources immediately to prevent memory leak
                    image.flush();

                    if (pageText != null && !pageText.trim().isEmpty()) {
                        String trimmedPageText = pageText.trim();
                        String sanitizedPageText = TreatmentUtils.sanitizeForUtf8Storage(trimmedPageText);

                        // Check if adding this page would exceed the limit
                        if (totalCharactersExtracted + sanitizedPageText.length() > HandlerLimits.MAX_OCR_CONTENT_LENGTH) {
                            // Add partial content up to the limit
                            int remainingSpace = HandlerLimits.MAX_OCR_CONTENT_LENGTH - totalCharactersExtracted;
                            if (remainingSpace > 0) {
                                pageAccumulator.append(sanitizedPageText, 0, remainingSpace);
                                totalCharactersExtracted += remainingSpace;
                            }
                            contentTruncated = true;
                            pagesProcessed = page + 1;
                            logger.info("OCR content limit reached during page {} of {}, stopping extraction for: {}",
                                    page, pageCount, item.getName());
                            break;
                        }

                        pageAccumulator.append(sanitizedPageText);
                        if (page < pageCount - 1) {
                            pageAccumulator.append("\n\n");
                        }
                        totalCharactersExtracted += sanitizedPageText.length();

                        // Chunk when accumulator reaches threshold to reduce memory usage
                        if (pageAccumulator.length() >= ACCUMULATOR_THRESHOLD) {
                            List<String> chunks = TreatmentUtils.chunkText(
                                pageAccumulator.toString(),
                                TreatmentUtils.FTS_CHUNK_SIZE
                            );

                            // Write all but last chunk (last chunk might be incomplete)
                            for (int i = 0; i < chunks.size() - 1; i++) {
                                String key = FIELD_OCR_CONTENT + "-" + chunkIndex++;
                                results.add(new TreatmentResult(key, chunks.get(i)));
                            }

                            // Keep last chunk as it might need more text to reach whitespace boundary
                            pageAccumulator = new StringBuilder(chunks.get(chunks.size() - 1));
                        }
                    }
                    pagesProcessed = page + 1;
                } catch (Exception e) {
                    logger.warn("OCR error for PDF page {} of {}: {}",
                            page, item.getFullPath(), e.getMessage());
                    throw new IllegalStateException("OCR failed on PDF page " + page + " for " + item.getFullPath(), e);
                }
            }

            // Process any remaining text in the accumulator
            if (pageAccumulator.length() > 0) {
                List<String> finalChunks = TreatmentUtils.chunkText(
                    pageAccumulator.toString(),
                    TreatmentUtils.FTS_CHUNK_SIZE
                );

                // Bare key only if the whole document is this one chunk; chunks emitted above are already numbered
                List<String> keys = TreatmentUtils.chunkKeys(FIELD_OCR_CONTENT, chunkIndex, finalChunks.size());
                for (int i = 0; i < finalChunks.size(); i++) {
                    results.add(new TreatmentResult(keys.get(i), finalChunks.get(i)));
                }
                chunkIndex += finalChunks.size();

                if (contentTruncated) {
                    results.add(new TreatmentResult(FIELD_CONTENT_TRUNCATED, "true"));
                    logger.info("OCR extracted {} characters of truncated content from {}/{} pages in PDF: {} (split into {} chunks)",
                            totalCharactersExtracted, pagesProcessed, pageCount, item.getName(), chunkIndex);
                } else {
                    logger.info("OCR extracted {} characters from {} pages in PDF: {} (split into {} chunks)",
                            totalCharactersExtracted, pagesProcessed, item.getName(), chunkIndex);
                }
            }

        } catch (Exception e) {
            logger.error("Error performing OCR on PDF {}: {}", item.getFullPath(), e.getMessage(), e);
            throw new IllegalStateException("OCR failed for PDF " + item.getFullPath(), e);
        } finally {
            // Close document
            if (document != null) {
                try {
                    document.close();
                } catch (Exception e) {
                    logger.warn("Error closing PDF document: {}", e.getMessage());
                }
            }
            // Clean up temp file
            TreatmentUtils.cleanupTempFile(tempFile);
        }

        return results;
    }

    /**
     * Determines if a PDF is a "true" PDF (created digitally with actual text content)
     * vs a scanned PDF (created from images with no extractable text).
     * 
     * True PDFs have complex element structure with extractable text and should NOT be OCR'd
     * since the CONTENT treatment can already extract their text.
     * 
     * Scanned PDFs contain primarily images and need OCR to extract text.
     * 
     * @param document the PDF document to analyze
     * @return true if the PDF has substantial extractable text content
     */
    private boolean isTruePdf(PDDocument document) {
        try {
            PDFTextStripper textStripper = new PDFTextStripper();
            String extractedText = textStripper.getText(document);
            
            if (extractedText == null) {
                return false;
            }
            
            // Count non-whitespace characters
            String trimmedText = extractedText.replaceAll("\\s+", "");
            int charCount = trimmedText.length();
            
            logger.info("PDF text extraction found {} non-whitespace characters", charCount);
            
            return charCount >= HandlerLimits.MIN_TEXT_CHARS_FOR_TRUE_PDF;
            
        } catch (Exception e) {
            logger.warn("Error checking PDF text content: {}", e.getMessage());
            // If we can't determine, assume it needs OCR
            return false;
        }
    }

    /**
     * OSD script → Tesseract -l candidates, best first. Codes are .traineddata file stems
     * (underscores, not the hyphens of the Debian package names). Script models ("Cyrillic")
     * are trained on every language of their script. The first candidate whose every component
     * is installed wins; otherwise the installed subset of the last candidate; otherwise
     * DEFAULT_LANG. Choices are measured: within ~1.2x the single-language model's OCR time,
     * the one with the better word score, the language model on a tie. A '+' combo costs
     * roughly one recognition pass per language, which is why only Latin has one.
     */
    static final Map<String, List<String>> SCRIPT_TO_LANGS = Map.ofEntries(
        Map.entry("Latin",      List.of("eng+fra+deu+spa")),
        Map.entry("Cyrillic",   List.of("Cyrillic", "rus")),
        Map.entry("Arabic",     List.of("Arabic", "ara")),
        Map.entry("Hebrew",     List.of("heb")),
        Map.entry("Greek",      List.of("Greek", "ell")),
        Map.entry("Han",        List.of("chi_sim")),
        Map.entry("HanS",       List.of("chi_sim")),
        Map.entry("HanT",       List.of("chi_tra")),
        Map.entry("Japanese",   List.of("jpn")),
        // OSD reports a Korean page as "Korean", not by its script name "Hangul"; both are mapped
        Map.entry("Korean",     List.of("kor")),
        Map.entry("Hangul",     List.of("kor")),
        Map.entry("Devanagari", List.of("Devanagari", "hin")),
        Map.entry("Bengali",    List.of("ben")),
        Map.entry("Tamil",      List.of("tam")),
        Map.entry("Telugu",     List.of("Telugu", "tel")),
        Map.entry("Kannada",    List.of("Kannada", "kan")),
        Map.entry("Thai",       List.of("tha"))
    );

    private static final String DEFAULT_LANG = "eng";

    /**
     * Per-document OSD pinning state for a scanned PDF. A null detection means OSD produced no
     * result (blank or sparse page, error, timeout) and is never evidence of a language, so two
     * failures can't agree. The language pins once a real detection equals the previous real one.
     */
    record LanguagePin(String lastDetected, String pinned) {
        static final LanguagePin NONE = new LanguagePin(null, null);

        LanguagePin observe(String detected) {
            if (detected == null || pinned != null) {
                return this;
            }
            return new LanguagePin(detected, detected.equals(lastDetected) ? detected : null);
        }
    }

    /** OSD script → the -l value resolved against this host's tessdata; scripts with nothing installed are absent. */
    private static final Map<String, String> SCRIPT_TO_RESOLVED_LANG =
        resolveAll(scanInstalled(new File(tessDataPath)));

    private static Map<String, String> resolveAll(Map<String, String> installed) {
        Map<String, String> resolved = new HashMap<>();
        new TreeMap<>(SCRIPT_TO_LANGS).forEach((script, candidates) -> {
            String lang = resolveLang(candidates, installed);
            if (lang == null) {
                logger.warn("OCR script '{}': no model installed (wanted one of {}), will use '{}'",
                    script, candidates, DEFAULT_LANG);
            } else {
                resolved.put(script, lang);
                logger.info("OCR script '{}' → language '{}'", script, lang);
            }
        });
        return resolved;
    }

    /**
     * Installed model name → the token to pass to -l: "Cyrillic", or "script/Cyrillic" for a
     * tessdata tree that keeps script models in a script/ subdirectory (the upstream layout).
     */
    static Map<String, String> scanInstalled(File tessDir) {
        Map<String, String> found = new HashMap<>();
        for (String sub : new String[] {"", "script"}) {
            File[] files = new File(tessDir, sub).listFiles((d, n) -> n.endsWith(".traineddata"));
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String name = f.getName().substring(0, f.getName().length() - ".traineddata".length());
                found.putIfAbsent(name, sub.isEmpty() ? name : sub + "/" + name);
            }
        }
        logger.info("Installed Tesseract models: {}", found);
        return found;
    }

    /** Returns the -l value to use for these candidates, or null if nothing for them is installed. */
    static String resolveLang(List<String> candidates, Map<String, String> installed) {
        for (String candidate : candidates) {
            String[] parts = candidate.split("\\+");
            if (Arrays.stream(parts).allMatch(installed::containsKey)) {
                return Arrays.stream(parts).map(installed::get).collect(Collectors.joining("+"));
            }
        }
        String partial = Arrays.stream(candidates.get(candidates.size() - 1).split("\\+"))
            .filter(installed::containsKey).map(installed::get).collect(Collectors.joining("+"));
        return partial.isEmpty() ? null : partial;
    }

    private static CompletableFuture<String> readStdout(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static CompletableFuture<String> readStderr(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    /**
     * Run Tesseract's OSD (Orientation and Script Detection) pass on the image file
     * to determine the dominant script, then return the corresponding Tesseract language string.
     * Returns DEFAULT_LANG if the script is unrecognised or nothing for it is installed — those are
     * real detections. Returns null if OSD produced no result (non-zero exit, no Script: line,
     * exception or timeout), so callers can tell a failure from a detection.
     */
    private String detectLanguage(File imageFile) {
        ProcessBuilder pb = new ProcessBuilder(
            "tesseract", imageFile.getAbsolutePath(), "stdout",
            "--tessdata-dir", tessDataPath,
            "--psm", "0",
            "-l", "osd"
        );
        Process process = null;
        try {
            process = pb.start();
            CompletableFuture<String> stdoutFuture = readStdout(process);
            CompletableFuture<String> stderrFuture = readStderr(process);

            String output = stdoutFuture.get(30, TimeUnit.SECONDS);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String stderr;
                try {
                    stderr = stderrFuture.get(5, TimeUnit.SECONDS).trim();
                } catch (TimeoutException e) {
                    stderr = "(stderr unavailable)";
                }
                logger.warn("OSD exited with code {} for {}: {}", exitCode, imageFile.getName(), stderr);
                return null;
            }

            for (String line : output.lines().toList()) {
                if (line.startsWith("Script:")) {
                    String script = line.substring("Script:".length()).trim();
                    String lang = SCRIPT_TO_RESOLVED_LANG.get(script);
                    if (lang != null) {
                        logger.info("OSD detected script '{}' → language '{}'", script, lang);
                        return lang;
                    }
                    if (SCRIPT_TO_LANGS.containsKey(script)) {
                        logger.info("OSD script '{}' detected but no model for it installed; using '{}'", script, DEFAULT_LANG);
                        return DEFAULT_LANG;
                    }
                    logger.info("OSD detected unrecognised script '{}', falling back to '{}'", script, DEFAULT_LANG);
                    return DEFAULT_LANG;
                }
            }
        } catch (Exception e) {
            logger.warn("OSD script detection failed for {}: {}", imageFile.getName(), e.getMessage());
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    /**
     * Spawn a tesseract CLI subprocess to OCR the given image file using the specified language.
     * Returns the extracted text. Throws on failure or timeout — callers are responsible for handling.
     *
     * A crash in the tesseract binary kills only the subprocess — the server is unaffected.
     */
    private String runTesseract(File imageFile, String lang) throws Exception {
        long startTime = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(
            "tesseract", imageFile.getAbsolutePath(), "stdout",
            "--tessdata-dir", tessDataPath,
            "-l", lang,
            "--psm", "1",
            "--oem", "1"
        );
        Process process = pb.start();
        try {
            CompletableFuture<String> stdoutFuture = readStdout(process);
            CompletableFuture<String> stderrFuture = readStderr(process);

            String result = stdoutFuture.get(HandlerLimits.OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int exitCode = process.waitFor();
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Tesseract OCR completed in {}ms (lang={}) for file: {}", duration, lang, imageFile.getName());

            if (exitCode != 0) {
                String stderr;
                try {
                    stderr = stderrFuture.get(5, TimeUnit.SECONDS).trim();
                } catch (TimeoutException e) {
                    stderr = "(stderr unavailable)";
                }
                logger.error("Tesseract exited with code {} for file {}: {}", exitCode, imageFile.getName(), stderr);
                throw new IOException("Tesseract exited with code " + exitCode + " for " + imageFile.getName());
            }
            return result;
        } finally {
            process.destroyForcibly();
        }
    }

    /** Detect language via OSD then OCR the image file. */
    private String runTesseract(File imageFile) throws Exception {
        String lang = detectLanguage(imageFile);
        return runTesseract(imageFile, lang != null ? lang : DEFAULT_LANG);
    }

    /** Write the BufferedImage to a temporary PNG and OCR it with the given language. */
    private String runTesseract(BufferedImage image, String lang) throws Exception {
        File tempImageFile = Files.createTempFile("ocr-page-", ".png").toFile();
        try {
            ImageIO.write(image, "png", tempImageFile);
            return runTesseract(tempImageFile, lang);
        } finally {
            TreatmentUtils.cleanupTempFile(tempImageFile);
        }
    }
}
