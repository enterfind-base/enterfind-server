package enterfind.server.controller;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.ItemPreviewDao;
import enterfind.server.dao.FaceDao;
import enterfind.server.exception.NotFoundException;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.FaceDetection;
import enterfind.server.model.ItemPreview;
import enterfind.server.model.ItemPreviewStatus;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Controller to handle file downloads, preview thumbnails, and detected face crops.
 * URL patterns:
 *   /content/{datasource-type}/{datasource-name}/path/to/file.ext
 *   /preview/{datasource-type}/{datasource-name}/path/to/file.ext
 *   /detected-face/{datasource-type}/{datasource-name}/path/to/file.ext/{faceIndex}
 */
@RestController
public class DownloadController {

    private static final Logger logger = LoggerFactory.getLogger(DownloadController.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final int FACE_CROP_SIZE = 150; // Output size for face crops

    /**
     * Stored types /content may serve inline: raster images, which a browser renders without running
     * script. Everything else, SVG and HTML included, is an attachment whatever ?inline= says, because
     * under remote access every instance shares one origin, so a script in any instance's file could
     * read the login token of every instance open in that browser. An attachment still renders in an
     * img tag, which ignores Content-Disposition.
     */
    private static final Set<String> INLINE_SAFE_TYPES = Set.of(
        "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
        "image/bmp", "image/avif", "image/x-icon", "image/vnd.microsoft.icon");

    private final DataSourceDao dataSourceDao;
    private final ItemDao itemDao;
    private final ItemPreviewDao itemPreviewDao;
    private final FaceDao faceDao;
    private final AuthContext authContext;

    public DownloadController(DataSourceDao dataSourceDao, ItemDao itemDao,
                              ItemPreviewDao itemPreviewDao, FaceDao faceDao,
                              AuthContext authContext) {
        this.dataSourceDao = dataSourceDao;
        this.itemDao = itemDao;
        this.itemPreviewDao = itemPreviewDao;
        this.faceDao = faceDao;
        this.authContext = authContext;
    }

    /**
     * Helper class to hold parsed file path information
     */
    private static class FilePathInfo {
        final DataSourceInterface dataSource;
        final DataSourceType type;
        final String decodedDataSourceName;
        final String fileName;
        final String folderPart;
        final String slashPath;
        final String dataSourceId;

        FilePathInfo(DataSourceInterface dataSource, DataSourceType type, String decodedDataSourceName,
                     String fileName, String folderPart, String slashPath, String dataSourceId) {
            this.dataSource = dataSource;
            this.type = type;
            this.decodedDataSourceName = decodedDataSourceName;
            this.fileName = fileName;
            this.folderPart = folderPart;
            this.slashPath = slashPath;
            this.dataSourceId = dataSourceId;
        }
    }

    /**
     * Parse and extract file path information from request
     */
    private FilePathInfo parseFilePath(String dataSourceType, String dataSourceName, HttpServletRequest request) {
        // Normalize datasource type token (allow dashes or lower-case)
        final String normalizedType = dataSourceType.replace('-', '_').toUpperCase();
        final DataSourceType type = DataSourceType.valueOf(normalizedType);

        // URL-decode the datasource name
        final String decodedDataSourceName = urlDecode(dataSourceName);

        DataSourceInterface ds = dataSourceDao.findByNameAndType(decodedDataSourceName, type)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + decodedDataSourceName + " (" + type + ")"));

        // Extract the remaining path after the datasource name
        final String pathWithinHandler = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        final String bestPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        
        if (bestPattern == null || pathWithinHandler == null) {
            throw new NotFoundException("Invalid request path");
        }
        
        final String relativePath = java.util.Objects.requireNonNullElse(
            PATH_MATCHER.extractPathWithinPattern(bestPattern, pathWithinHandler), "");

        if (relativePath.isEmpty()) {
            throw new NotFoundException("No file specified");
        }

        // URL-decode the relative path
        final String decodedRelativePath = urlDecode(urlDecode(relativePath));

        // Parse filename and folder from path
        final String fileName;
        final String folderPart;
        int lastSlash = decodedRelativePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            folderPart = decodedRelativePath.substring(0, lastSlash);
            fileName = decodedRelativePath.substring(lastSlash + 1);
        } else {
            folderPart = null;
            fileName = decodedRelativePath;
        }

        final String slashPath = (folderPart != null && !folderPart.isEmpty()) ? folderPart : null;

        final String dataSourceId = type.extractDatasourceId(ds);

        return new FilePathInfo(ds, type, decodedDataSourceName, fileName, folderPart, slashPath, dataSourceId);
    }

    @GetMapping("/content/{dataSourceType}/{dataSourceName}/**")
    public ResponseEntity<InputStreamResource> downloadFile(
            @PathVariable String dataSourceType,
            @PathVariable String dataSourceName,
            @RequestParam(required = false) String t,
            @RequestParam(required = false, defaultValue = "false") boolean inline,
            HttpServletRequest request) {

        authContext.requireReader(t);
        FilePathInfo pathInfo = parseFilePath(dataSourceType, dataSourceName, request);

        // Build Item expected by DataProvider (path is comma-separated)
        final Item item = new Item();
        item.setName(pathInfo.fileName);
        item.setPath(pathInfo.slashPath);

        DataProviderInterface provider = pathInfo.type.getDataProviderForTypeAndSource(pathInfo.dataSource);
        
        // Verify the file exists at the provider before attempting to open a stream
        boolean exists = false;
        try {
            for (Item listed : provider.getItems(pathInfo.slashPath)) {
                if (pathInfo.fileName.equals(listed.getName())) {
                    exists = true;
                    break;
                }
            }
        } catch (NotFoundException e) {
            // If the provider indicates the path doesn't exist, respond 404
            throw new NotFoundException("File not found: " + pathInfo.fileName);
        }

        if (!exists) {
            throw new NotFoundException("File not found: " + pathInfo.fileName);
        }

        final InputStream is = provider.getInputStream(item);

        if (is == null) {
            throw new NotFoundException("File not found: " + pathInfo.fileName);
        }

        // Prepare response
        InputStreamResource resource = new InputStreamResource(is);
        HttpHeaders headers = new HttpHeaders();

        // Attempt to get stored mime type and size from DB to set proper headers
        itemDao.findItemByDatasourceAndPath(pathInfo.dataSourceId, pathInfo.slashPath, pathInfo.fileName).ifPresent(dbItem -> {
            String mime = dbItem.getMimeType();
            if (mime != null && !mime.isBlank()) {
                try {
                    MediaType mt = MediaType.parseMediaType(mime);
                    headers.setContentType(mt);
                } catch (IllegalArgumentException e) {
                    // fallback to octet-stream if stored mime is invalid
                    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                }
            }

            if (dbItem.getSizeBytes() != null) {
                headers.setContentLength(dbItem.getSizeBytes());
            }
        });

        logger.info("Streaming download for datasource={} type={} file={}/{}", 
            pathInfo.decodedDataSourceName, pathInfo.type, pathInfo.folderPart, pathInfo.fileName);

        // Ensure a content type is always present
        if (headers.getContentType() == null) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }

        MediaType contentType = headers.getContentType();
        boolean inlineSafe = INLINE_SAFE_TYPES.contains(contentType.getType() + "/" + contentType.getSubtype());
        ContentDisposition.Builder disposition = inline && inlineSafe ? ContentDisposition.inline() : ContentDisposition.attachment();
        headers.setContentDisposition(disposition.filename(pathInfo.fileName, StandardCharsets.UTF_8).build());
        // Backstops if a file is rendered as a page anyway: no script, an opaque origin, no sniffing
        // of e.g. HTML stored under an image type
        headers.set("Content-Security-Policy", "sandbox");
        headers.set("X-Content-Type-Options", "nosniff");

        return ResponseEntity.ok()
            .headers(headers)
            .body(resource);
    }

    @GetMapping("/preview/{dataSourceType}/{dataSourceName}/**")
    public ResponseEntity<InputStreamResource> getPreview(
            @PathVariable String dataSourceType,
            @PathVariable String dataSourceName,
            @RequestParam(required = false) String t,
            HttpServletRequest request) {

        authContext.requireReader(t);
        FilePathInfo pathInfo = parseFilePath(dataSourceType, dataSourceName, request);

        // Find the item in database
        Item item = itemDao.findItemByDatasourceAndPath(pathInfo.dataSourceId, pathInfo.slashPath, pathInfo.fileName)
            .orElseThrow(() -> new NotFoundException("File not found: " + pathInfo.fileName));

        // Get preview for this item
        ItemPreview preview = itemPreviewDao.getByItemId(item.getId()).orElse(null);
        
        if (preview == null) {
            logger.info("No preview record found for item: {}", pathInfo.fileName);
            return ResponseEntity.notFound().build();
        }

        if (preview.getStatus() != ItemPreviewStatus.DONE || preview.getPreviewData() == null) {
            logger.info("Preview not ready for item: {}, status: {}", pathInfo.fileName, preview.getStatus());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        // Serve the preview image
        ByteArrayInputStream inputStream = new ByteArrayInputStream(preview.getPreviewData());
        InputStreamResource resource = new InputStreamResource(inputStream);

        HttpHeaders headers = new HttpHeaders();
        
        // Set content type
        MediaType contentType = MediaType.IMAGE_JPEG; // default
        String ct = preview.getContentType();
        if (ct != null && !ct.isEmpty()) {
            try {
                contentType = MediaType.parseMediaType(ct);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid content type for preview {}: {}", pathInfo.fileName, ct);
            }
        }
        headers.setContentType(contentType);
        headers.setContentLength(preview.getPreviewData().length);
        headers.setCacheControl("max-age=3600"); // Cache for 1 hour

        logger.info("Serving preview for item: {}, size: {} bytes", pathInfo.fileName, preview.getPreviewData().length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    /**
     * Get a cropped face image for a detected face.
     * URL pattern: /detected-face/{dataSourceType}/{dataSourceName}/path/to/file.jpg/{faceIndex}
     * The last path segment is the face's 0-based index within the item.
     *
     * <p>Keyed on index rather than on the face's row id, because detections are deleted and
     * re-inserted whenever an item is reprocessed, so ids do not survive a rescan. Nor is it
     * keyed on the face *label*, which could address neither an unlabeled face nor the same
     * person appearing twice in one image.
     */
    @GetMapping("/detected-face/{dataSourceType}/{dataSourceName}/**")
    public ResponseEntity<InputStreamResource> getDetectedFace(
            @PathVariable String dataSourceType,
            @PathVariable String dataSourceName,
            @RequestParam(required = false) String t,
            HttpServletRequest request) {

        authContext.requireReader(t);
        // Parse the path - we need to extract both file path and face index
        FacePathInfo facePathInfo = parseFacePathInfo(dataSourceType, dataSourceName, request);

        if (facePathInfo == null) {
            logger.info("Invalid detected-face request path");
            return ResponseEntity.notFound().build();
        }

        // Find the item in database
        Item item = itemDao.findItemByDatasourceAndPath(
                facePathInfo.fileInfo.dataSourceId,
                facePathInfo.fileInfo.slashPath,
                facePathInfo.fileInfo.fileName
        ).orElse(null);

        if (item == null) {
            logger.info("Item not found: {}/{}", facePathInfo.fileInfo.folderPart, facePathInfo.fileInfo.fileName);
            return ResponseEntity.notFound().build();
        }

        // Find the face detection at this index within the item. A miss is expected and benign
        // while an item is being reprocessed: the handler deletes and re-inserts its detections
        // outside the transaction that later commits the new facecount, so there is a brief window
        // where a live facecount has no rows (or fewer rows) behind it. item.html's onerror handler
        // on the thumbnail exists to absorb exactly this - do not remove it as defensive clutter.
        FaceDetection detection = faceDao.getFaceDetectionByItemAndIndex(
                item.getId(), facePathInfo.faceIndex
        ).orElse(null);

        if (detection == null) {
            logger.info("Face detection not found for item {} at index {}",
                    item.getId(), facePathInfo.faceIndex);
            return ResponseEntity.notFound().build();
        }

        // Get the original image and crop the face
        byte[] faceCropData = cropFaceFromImage(facePathInfo.fileInfo, detection);

        if (faceCropData == null) {
            logger.info("Failed to crop face from image: {}", facePathInfo.fileInfo.fileName);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // Return the cropped face
        ByteArrayInputStream inputStream = new ByteArrayInputStream(faceCropData);
        InputStreamResource resource = new InputStreamResource(inputStream);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);
        headers.setContentLength(faceCropData.length);
        headers.setCacheControl("max-age=86400"); // Cache for 24 hours (face crops are stable)

        logger.info("Serving detected face #{} for item: {}, size: {} bytes",
                facePathInfo.faceIndex, facePathInfo.fileInfo.fileName, faceCropData.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    /**
     * Helper class to hold face path information (file path + face index)
     */
    private static class FacePathInfo {
        final FilePathInfo fileInfo;
        final int faceIndex;

        FacePathInfo(FilePathInfo fileInfo, int faceIndex) {
            this.fileInfo = fileInfo;
            this.faceIndex = faceIndex;
        }
    }

    /**
     * Parse the path for detected-face endpoint.
     * Expected format: /detected-face/{type}/{name}/path/to/file.jpg/{faceIndex}
     * Returns null if path is invalid.
     */
    private FacePathInfo parseFacePathInfo(String dataSourceType, String dataSourceName, HttpServletRequest request) {
        // Normalize datasource type token
        final String normalizedType = dataSourceType.replace('-', '_').toUpperCase();
        final DataSourceType type;
        try {
            type = DataSourceType.valueOf(normalizedType);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // URL-decode the datasource name
        final String decodedDataSourceName = urlDecode(dataSourceName);

        DataSourceInterface ds = dataSourceDao.findByNameAndType(decodedDataSourceName, type).orElse(null);
        if (ds == null) {
            return null;
        }

        // Extract the remaining path
        final String pathWithinHandler = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        final String bestPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        if (bestPattern == null || pathWithinHandler == null) {
            return null;
        }

        final String relativePath = java.util.Objects.requireNonNullElse(
                PATH_MATCHER.extractPathWithinPattern(bestPattern, pathWithinHandler), "");

        if (relativePath.isEmpty()) {
            return null;
        }

        // URL-decode the relative path
        final String decodedRelativePath = urlDecode(urlDecode(relativePath));

        // The last segment is the face index; everything before it is the file path.
        int lastSlash = decodedRelativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            return null; // Need at least filename/faceIndex
        }

        // Validated as numeric here so a malformed URL is rejected cleanly with a 404, rather than
        // falling through to an ambiguous lookup.
        final int faceIndex;
        try {
            faceIndex = Integer.parseInt(decodedRelativePath.substring(lastSlash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (faceIndex < 0) {
            return null;
        }
        final String filePathPart = decodedRelativePath.substring(0, lastSlash);

        // Now parse the file path part
        final String fileName;
        final String folderPart;
        int fileLastSlash = filePathPart.lastIndexOf('/');
        if (fileLastSlash >= 0) {
            folderPart = filePathPart.substring(0, fileLastSlash);
            fileName = filePathPart.substring(fileLastSlash + 1);
        } else {
            folderPart = null;
            fileName = filePathPart;
        }

        final String slashPath = (folderPart != null && !folderPart.isEmpty()) ? folderPart : null;

        final String dataSourceId = type.extractDatasourceId(ds);

        FilePathInfo fileInfo = new FilePathInfo(ds, type, decodedDataSourceName,
                fileName, folderPart, slashPath, dataSourceId);

        return new FacePathInfo(fileInfo, faceIndex);
    }

    /**
     * Crop a face from the original image using stored bbox coordinates.
     * Returns JPEG bytes or null on failure.
     */
    private byte[] cropFaceFromImage(FilePathInfo fileInfo, FaceDetection detection) {
        Path tempFile = null;
        try {
            // Get data provider for the source
            DataProviderInterface provider = fileInfo.type.getDataProviderForTypeAndSource(fileInfo.dataSource);

            // Build item for data provider
            Item item = new Item();
            item.setName(fileInfo.fileName);
            item.setPath(fileInfo.slashPath);

            try (InputStream inputStream = provider.getInputStream(item)) {
                if (inputStream == null) {
                    logger.warn("Unable to get input stream for face crop: {}", fileInfo.fileName);
                    return null;
                }

                // Copy to temp file for ImageIO
                tempFile = Files.createTempFile("face-crop-", "-" + fileInfo.fileName);
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

                BufferedImage originalImage = ImageIO.read(tempFile.toFile());
                if (originalImage == null) {
                    logger.warn("Unable to read image for face crop: {}", fileInfo.fileName);
                    return null;
                }

                // Calculate pixel coordinates from normalized bbox
                int imgWidth = originalImage.getWidth();
                int imgHeight = originalImage.getHeight();

                int x = Math.max(0, (int) (detection.getBboxX() * imgWidth));
                int y = Math.max(0, (int) (detection.getBboxY() * imgHeight));
                int w = (int) (detection.getBboxW() * imgWidth);
                int h = (int) (detection.getBboxH() * imgHeight);

                // Add some padding around the face (20%)
                int padX = (int) (w * 0.2);
                int padY = (int) (h * 0.2);
                x = Math.max(0, x - padX);
                y = Math.max(0, y - padY);
                w = Math.min(imgWidth - x, w + 2 * padX);
                h = Math.min(imgHeight - y, h + 2 * padY);

                // Ensure we have a valid crop region
                if (w <= 0 || h <= 0) {
                    logger.warn("Invalid crop region for face: x={}, y={}, w={}, h={}", x, y, w, h);
                    return null;
                }

                // Crop the face region
                BufferedImage cropped = originalImage.getSubimage(x, y, w, h);

                // Resize to standard output size (square, maintaining aspect ratio with center crop)
                BufferedImage resized = resizeToSquare(cropped, FACE_CROP_SIZE);

                // Convert to JPEG bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, "JPEG", baos);

                // Clean up
                originalImage.flush();
                cropped.flush();
                resized.flush();

                return baos.toByteArray();
            }
        } catch (Exception e) {
            logger.error("Error cropping face from image {}: {}", fileInfo.fileName, e.getMessage(), e);
            return null;
        } finally {
            // Clean up temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    logger.warn("Failed to delete temp file: {}", tempFile);
                }
            }
        }
    }

    /**
     * Resize an image to a square of the given size, using center crop if needed.
     */
    private BufferedImage resizeToSquare(BufferedImage source, int size) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();

        // First, crop to square from center
        int cropSize = Math.min(srcW, srcH);
        int cropX = (srcW - cropSize) / 2;
        int cropY = (srcH - cropSize) / 2;

        BufferedImage squared = source.getSubimage(cropX, cropY, cropSize, cropSize);

        // Now resize to target size
        BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(squared, 0, 0, size, size, null);
        g2d.dispose();

        return resized;
    }

    private static String urlDecode(String s) {
        if (s == null) return null;
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
