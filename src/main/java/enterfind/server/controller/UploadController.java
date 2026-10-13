package enterfind.server.controller;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.NotFoundException;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceType;
import enterfind.server.security.AuthContext;
import enterfind.server.service.ItemStorageService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Controller for uploading and deleting files in existing datasources.
 *
 * URL patterns (mirroring the download endpoint):
 *   PUT    /content/{datasource-type}/{datasource-name}/path/to/file.ext?t={adminToken}
 *   DELETE /content/{datasource-type}/{datasource-name}/path/to/file.ext?t={adminToken}
 */
@RestController
public class UploadController {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final DataSourceDao dataSourceDao;
    private final ItemStorageService itemStorageService;
    private final AuthContext authContext;

    public UploadController(DataSourceDao dataSourceDao, ItemStorageService itemStorageService,
                            AuthContext authContext) {
        this.dataSourceDao = dataSourceDao;
        this.itemStorageService = itemStorageService;
        this.authContext = authContext;
    }

    /**
     * Upload a file into an existing datasource.
     * Inserts an ITEM row, writes the bytes to the underlying storage, then queues treatments.
     */
    @PutMapping("/content/{dataSourceType}/{dataSourceName}/**")
    public ResponseEntity<Void> uploadFile(
            @PathVariable String dataSourceType,
            @PathVariable String dataSourceName,
            @RequestParam(required = false) String t,
            HttpServletRequest request) throws IOException {

        authContext.requireMutationAdmin(t);

        ParsedPath parsed = parsePath(dataSourceType, dataSourceName, request);

        try (InputStream is = request.getInputStream()) {
            itemStorageService.uploadItem(parsed.dataSource, parsed.type,
                parsed.fileName, parsed.slashPath, request.getContentLengthLong(), is);
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Delete a file from an existing datasource.
     * Removes bytes from underlying storage, then deletes the ITEM row (cascades to treatments).
     */
    @DeleteMapping("/content/{dataSourceType}/{dataSourceName}/**")
    public ResponseEntity<Void> deleteFile(
            @PathVariable String dataSourceType,
            @PathVariable String dataSourceName,
            @RequestParam(required = false) String t,
            HttpServletRequest request) {

        authContext.requireMutationAdmin(t);

        ParsedPath parsed = parsePath(dataSourceType, dataSourceName, request);

        itemStorageService.deleteItem(parsed.dataSource, parsed.type,
            parsed.fileName, parsed.slashPath);

        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Path parsing helpers
    // -----------------------------------------------------------------------

    private record ParsedPath(
        DataSourceInterface dataSource,
        DataSourceType type,
        String fileName,
        String slashPath
    ) {}

    private ParsedPath parsePath(String dataSourceType, String dataSourceName, HttpServletRequest request) {
        final String normalizedType = dataSourceType.replace('-', '_').toUpperCase();
        final DataSourceType type;
        try {
            type = DataSourceType.valueOf(normalizedType);
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentException("Unknown datasource type: " + dataSourceType);
        }

        final String decodedDataSourceName = urlDecode(dataSourceName);
        DataSourceInterface ds = dataSourceDao.findByNameAndType(decodedDataSourceName, type)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + decodedDataSourceName + " (" + type + ")"));

        final String pathWithinHandler =
            (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        final String bestPattern =
            (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        if (bestPattern == null || pathWithinHandler == null) {
            throw new InvalidArgumentException("Invalid request path");
        }

        final String relativePath = java.util.Objects.requireNonNullElse(
            PATH_MATCHER.extractPathWithinPattern(bestPattern, pathWithinHandler), "");

        if (relativePath.isEmpty()) {
            throw new InvalidArgumentException("No file path specified");
        }

        // Double-decode mirrors the double-encode used when building download/upload URLs
        final String decodedRelativePath = urlDecode(urlDecode(relativePath));

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

        if (fileName.isEmpty()) {
            throw new InvalidArgumentException("No file name specified");
        }

        final String slashPath = (folderPart != null && !folderPart.isEmpty()) ? folderPart : null;

        return new ParsedPath(ds, type, fileName, slashPath);
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
