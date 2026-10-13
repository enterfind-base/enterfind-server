package enterfind.server.scan.treatment;

import static enterfind.server.model.FieldNameConstants.FIELD_CONTENT;
import static enterfind.server.model.FieldNameConstants.FIELD_CONTENT_TRUNCATED;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import enterfind.server.dao.ItemDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;

/**
 * Treatment handler that extracts content and metadata from files using Apache Tika.
 * OCR is explicitly disabled here - use the OCR treatment for OCR functionality.
 */
@Component
public class ContentTreatmentHandler implements TreatmentHandler {

    private static final Logger logger = LoggerFactory.getLogger(ContentTreatmentHandler.class);
    private static final AutoDetectParser PARSER = new AutoDetectParser();

    // Timeout for Tika parsing operations (in seconds)
    private static final int TIKA_TIMEOUT_SECONDS = 300; // 5 minutes

    // Maximum content length: 20 MB (matching OCR handler limit)
    private static final int MAX_CONTENT_LENGTH = 20 * 1024 * 1024;

    // Tika spools the input stream to a scratch file for the image parser (it needs a
    // seekable file). The drewnoakes metadata-extractor library then reads that temp file's
    // FileSystemDirectory and emits "File Name"/"File Size"/"File Modified Date" tags
    // describing the temp file, not the original. These leak in as file_name/file_size/
    // file_modified_date - pure noise, and file_modified_date is the ingest time, not the
    // file's real date. We detect the artifact by file_name's value (always apache-tika-*.tmp
    // on this path) and drop all three; the genuine values live elsewhere (item name,
    // size_bytes, time_modified, and EXIF dates).
    private static final Pattern TIKA_TEMP_FILE_NAME = Pattern.compile("apache-tika-\\d+\\.tmp");
    private static final Set<String> FILESYSTEM_DIRECTORY_KEYS =
            Set.of("file_name", "file_size", "file_modified_date");

    // Executor service for timeout-enforced parsing
    // Fixed thread pool prevents unbounded thread creation under load
    private static final ExecutorService tikaExecutor = Executors.newFixedThreadPool(3);

    // ItemDao for updating MIME types - constructor-injected by Spring
    private final ItemDao itemDao;

    // Pre-configured to skip OCR - we handle OCR separately in OcrTreatmentHandler
    private static final TesseractOCRConfig OCR_CONFIG;
    static {
        OCR_CONFIG = new TesseractOCRConfig();
        OCR_CONFIG.setSkipOcr(true);
    }

    public ContentTreatmentHandler(ItemDao itemDao) {
        this.itemDao = itemDao;
    }

    @Override
    public TreatmentOutput apply(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        return TreatmentOutput.of(applyResults(item, dataProvider, dataSource, treatmentName));
    }

    private List<TreatmentResult> applyResults(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        List<TreatmentResult> results = new ArrayList<>();

        // Add treatment type identifier
        results.add(new TreatmentResult(FIELD_TREATMENT_TYPE, treatmentName));

        try (InputStream inputStream = dataProvider.getInputStream(item)) {
            if (inputStream == null) {
                logger.warn("Unable to get input stream for item: {}", item.getFullPath());
                return results;
            }

            // Prepare Tika components for parsing
            // Use WriteOutContentHandler with limit to prevent OOM on very large files
            WriteOutContentHandler writeOutHandler = new WriteOutContentHandler(MAX_CONTENT_LENGTH);
            BodyContentHandler handler = new BodyContentHandler(writeOutHandler);
            Metadata metadata = new Metadata();
            ParseContext parseContext = new ParseContext();
            parseContext.set(TesseractOCRConfig.class, OCR_CONFIG);

            boolean contentTruncated = false;
            try {
                // Extract content and metadata using Apache Tika with timeout
                Callable<Void> parsingTask = () -> {
                    PARSER.parse(inputStream, handler, metadata, parseContext);
                    return null;
                };

                Future<Void> future = tikaExecutor.submit(parsingTask);
                try {
                    future.get(TIKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    logger.error("Tika parsing timeout ({} seconds) for file: {}", TIKA_TIMEOUT_SECONDS, item.getFullPath());
                    throw new TikaException("Parsing timeout after " + TIKA_TIMEOUT_SECONDS + " seconds");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Tika parsing interrupted for file: {}", item.getFullPath());
                    throw new TikaException("Parsing interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof TikaException) {
                        throw (TikaException) cause;
                    } else if (cause instanceof SAXException) {
                        throw (SAXException) cause;
                    } else {
                        throw new TikaException("Parsing failed", cause);
                    }
                }
            } catch (SAXException | TikaException e) {
                // Check if this is a write limit exception (content exceeded limit)
                if (WriteLimitReachedException.isWriteLimitReached(e)) {
                    contentTruncated = true;
                    logger.info("Content extraction truncated at {} bytes for file: {}",
                            MAX_CONTENT_LENGTH, item.getFullPath());
                } else {
                    // Re-throw other SAX/Tika exceptions to be handled below
                    throw e;
                }
            }

            // Extract text content and sanitize for UTF-8 storage
            // Content is available even if truncated due to write limit
            String content = handler.toString();
            logger.info("Extracted content for {} (length: {})", item.getFullPath(), (content != null ? content.length() : 0));
            if (content != null && !content.trim().isEmpty()) {
                String sanitizedContent = TreatmentUtils.sanitizeForUtf8Storage(content.trim());
                logger.info("Sanitized content for {} (length: {})", item.getFullPath(), (sanitizedContent != null ? sanitizedContent.length() : 0));
                if (sanitizedContent != null && !sanitizedContent.isEmpty()) {
                    // Chunk content to ensure full searchability in FTS
                    List<String> chunks = TreatmentUtils.chunkText(sanitizedContent, TreatmentUtils.FTS_CHUNK_SIZE);

                    List<String> keys = TreatmentUtils.chunkKeys(FIELD_CONTENT, 0, chunks.size());
                    for (int i = 0; i < chunks.size(); i++) {
                        results.add(new TreatmentResult(keys.get(i), chunks.get(i)));
                    }

                    if (contentTruncated) {
                        results.add(new TreatmentResult(FIELD_CONTENT_TRUNCATED, "true"));
                        logger.info("Extracted {} characters of truncated content from: {} (split into {} chunks)",
                                sanitizedContent.length(), item.getName(), chunks.size());
                    } else {
                        logger.info("Extracted {} characters of content from: {} (split into {} chunks)",
                                sanitizedContent.length(), item.getName(), chunks.size());
                    }
                }
            }

            // Detect the Tika temp-file artifact once via the reliable file_name signature.
            // When present, the FileSystemDirectory triplet (file_name/file_size/
            // file_modified_date) all describe the scratch file and must be dropped.
            String fileSystemName = metadata.get("File Name");
            boolean tikaTempArtifact = fileSystemName != null
                    && TIKA_TEMP_FILE_NAME.matcher(fileSystemName).matches();

            // Extract all metadata as key-value pairs and sanitize them
            String[] metadataNames = metadata.names();
            for (String name : metadataNames) {
                // Skip X-TIKA internal metadata fields
                if (name.toLowerCase().startsWith("x-tika")) {
                    continue;
                }
                String value = metadata.get(name);
                if (value != null && !value.trim().isEmpty()) {
                    String sanitizedValue = TreatmentUtils.sanitizeForUtf8Storage(value.trim());
                    if (!sanitizedValue.isEmpty()) {
                        // Use metadata key as-is, but ensure it's a valid key
                        String key = name.toLowerCase().replaceAll("[^a-zA-Z0-9_-]", "_");
                        // Drop the temp-file FileSystemDirectory tags (see field comment above)
                        if (tikaTempArtifact && FILESYSTEM_DIRECTORY_KEYS.contains(key)) {
                            continue;
                        }
                        // Drop noise attributes: unparsed maker-note keys and blob-shaped values
                        if (TreatmentUtils.isNoiseAttribute(key, sanitizedValue)) {
                            continue;
                        }
                        results.add(new TreatmentResult(key, sanitizedValue));
                    }
                }
            }

            logger.info("Extracted {} metadata fields from: {}", metadataNames.length, item.getName());

            // Check if Tika detected a Content-Type and if item's MIME type needs updating
            String tikaContentType = metadata.get(HttpHeaders.CONTENT_TYPE);
            if (tikaContentType != null && !tikaContentType.trim().isEmpty()) {
                // Clean up the content type (remove charset and other parameters)
                String cleanContentType = tikaContentType.split(";")[0].trim();
                String currentMimeType = item.getMimeType();

                // Update MIME type if it's missing or doesn't match Tika's detection
                if (currentMimeType == null || currentMimeType.isEmpty() || !currentMimeType.equals(cleanContentType)) {
                    item.setMimeType(cleanContentType);
                    itemDao.updateItem(item);
                    logger.info("Updated MIME type for item {} ({}): '{}' -> '{}'",
                        item.getId(), item.getName(), currentMimeType, cleanContentType);
                }
            }

        } catch (TikaException e) {
            logger.warn("Tika parsing error for file {}: {}", item.getFullPath(), e.getMessage());
            // Return empty results instead of failing - this allows processing to continue
        } catch (SAXException e) {
            logger.warn("SAX parsing error for file {}: {}", item.getFullPath(), e.getMessage());
            // Return empty results instead of failing
        } catch (Exception e) {
            logger.error("Error extracting content from file {}: {}", item.getFullPath(), e.getMessage(), e);
            // Return empty results instead of failing
        }

        return results;
    }
}
