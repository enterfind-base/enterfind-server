package enterfind.server.scan;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.NotFoundException;
import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.util.MimeTypeDetector;

/**
 * Data provider for scanning local file systems
 */
public class LocalFilesDataProvider implements DataProviderInterface {
    
    private static final Logger logger = LoggerFactory.getLogger(LocalFilesDataProvider.class);
    
    private LocalFileSystemDataSource dataSource;
    private String basePath;

    /**
     * Initialize the local file system data provider with the given datasource
     */
    public LocalFilesDataProvider(LocalFileSystemDataSource dataSource) {
        this.dataSource = dataSource;
        this.basePath = dataSource.localPath();
    }

    @Override
    public List<Item> getItems(String path) {
        if (dataSource == null) {
            throw new InvalidArgumentException("DataSource must be set before scanning items");
        }

        List<Item> items = new ArrayList<>();
        Path directoryPath = resolveDirectoryPath(path);
        
        if (!Files.exists(directoryPath) || !Files.isDirectory(directoryPath)) {
            // Fail if root directory doesn't exist, but allow empty subdirectories
            if (path == null) {
                throw new NotFoundException("Root directory does not exist or is not a directory: " + directoryPath);
            }
            return items; // Return empty list if subdirectory doesn't exist
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath)) {
            for (Path filePath : stream) {
                // SECURITY: Skip symbolic links to prevent symlink attacks
                if (Files.isSymbolicLink(filePath)) {
                    logger.warn("Skipping symbolic link (security risk): {}", filePath);
                    continue;
                }
                
                if (Files.isRegularFile(filePath)) {
                    Item item = createItemFromPath(filePath, path);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
        } catch (IOException e) {
            throw new SystemIssueException("Error reading directory: " + directoryPath, e);
        }

        return items;
    }

    @Override
    public List<Folder> getFolders(String path) {
        if (dataSource == null) {
            throw new InvalidArgumentException("DataSource must be set before scanning folders");
        }

        List<Folder> folders = new ArrayList<>();
        Path directoryPath = resolveDirectoryPath(path);
        
        if (!Files.exists(directoryPath) || !Files.isDirectory(directoryPath)) {
            // Fail if root directory doesn't exist, but allow empty subdirectories
            if (path == null) {
                throw new NotFoundException("Root directory does not exist or is not a directory: " + directoryPath);
            }
            return folders; // Return empty list if subdirectory doesn't exist
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath)) {
            for (Path folderPath : stream) {
                // SECURITY: Skip symbolic links to prevent symlink attacks
                if (Files.isSymbolicLink(folderPath)) {
                    logger.warn("Skipping symbolic link (security risk): {}", folderPath);
                    continue;
                }
                
                if (Files.isDirectory(folderPath)) {
                    Folder folder = createFolderFromPath(folderPath, path);
                    if (folder != null) {
                        folders.add(folder);
                    }
                }
            }
        } catch (IOException e) {
            throw new SystemIssueException("Error reading directory: " + directoryPath, e);
        }

        return folders;
    }

    @Override
    public InputStream getInputStream(Item item) {
        String fullPath = item.getFullPath();
        if (item.getFullPath() == null || item.getFullPath().isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to create input stream");
        }

        // Convert slash-separated path back to file system path using platform-specific separator
        String relativePath = fullPath.replace("/", File.separator);
        
        // Validate path security before opening stream
        Path resolvedPath = resolveDirectoryPath(relativePath);
        
        try {
            return new FileInputStream(resolvedPath.toFile());
        } catch (IOException e) {
            throw new SystemIssueException("Error creating input stream for: " + resolvedPath, e);
        }
    }

    /**
     * Resolve the directory path based on the base path and relative path.
     * Ensures the resolved path stays within the configured basePath (prevents path traversal).
     */
    private Path resolveDirectoryPath(String path) {
        if (path == null || path.isEmpty()) {
            return Paths.get(basePath);
        }
        
        // Convert slash-separated path back to file system path using platform-specific separator
        String relativePath = path.replace("/", File.separator);
        
        // SECURITY: Reject paths containing null bytes
        if (relativePath.contains("\0")) {
            throw new InvalidArgumentException("Path contains invalid null bytes");
        }
        
        // SECURITY: Reject absolute paths
        Path testPath = Paths.get(relativePath);
        if (testPath.isAbsolute()) {
            throw new InvalidArgumentException("Absolute paths are not allowed");
        }
        
        // Construct the full path
        Path fullPath = Paths.get(basePath, relativePath);
        
        // SECURITY: Canonicalize both paths and verify traversal didn't escape basePath
        try {
            Path basePathCanonical = Paths.get(basePath).toRealPath();
            Path fullPathCanonical = fullPath.toRealPath();
            
            // Ensure the resolved path is within the basePath
            if (!fullPathCanonical.startsWith(basePathCanonical)) {
                throw new InvalidArgumentException(
                    "Access denied: Requested path is outside the allowed base directory");
            }
            
            return fullPathCanonical;
        } catch (IOException e) {
            // If path doesn't exist yet (new directory), validate without canonicalization
            try {
                Path basePathCanonical = Paths.get(basePath).toRealPath();
                Path normalizedPath = fullPath.normalize();
                
                if (!normalizedPath.startsWith(basePathCanonical)) {
                    throw new InvalidArgumentException(
                        "Access denied: Requested path is outside the allowed base directory");
                }
                
                return normalizedPath;
            } catch (IOException ex) {
                throw new SystemIssueException("Error validating path security: " + fullPath, ex);
            }
        }
    }

    /**
     * Create an Item object from a file path
     */
    private Item createItemFromPath(Path filePath, String parentPath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            
            Item item = new Item();
            item.setName(filePath.getFileName().toString());
            item.setPath(parentPath);
            
            // Convert file times to LocalDateTime
            item.setTimeCreated(convertToLocalDateTime(attrs.creationTime().toInstant()));
            item.setTimeModified(convertToLocalDateTime(attrs.lastModifiedTime().toInstant()));
            
            // Set file size
            item.setSizeBytes(attrs.size());

            // Determine MIME type using hybrid approach
            String mimeType = null;
            try {
                // First try OS-level detection
                mimeType = Files.probeContentType(filePath);
            } catch (IOException e) {
                // Ignore errors from probeContentType
            }

            // Fall back to extension-based detection if OS-level detection failed
            if (mimeType == null) {
                mimeType = MimeTypeDetector.detectMimeType(filePath.getFileName().toString());
            }

            item.setMimeType(mimeType);
            
            return item;
        } catch (IOException e) {
            // Log error but don't stop scanning
            logger.error("Error reading file attributes for: {} - {}", filePath, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create a Folder object from a directory path
     */
    private Folder createFolderFromPath(Path folderPath, String parentPath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(folderPath, BasicFileAttributes.class);
            
            Folder folder = new Folder();
            folder.setName(folderPath.getFileName().toString());
            folder.setPath(parentPath);
            
            // Convert file times to LocalDateTime
            folder.setTimeCreated(convertToLocalDateTime(attrs.creationTime().toInstant()));
            folder.setTimeModified(convertToLocalDateTime(attrs.lastModifiedTime().toInstant()));
            
            return folder;
        } catch (IOException e) {
            // Log error but don't stop scanning
            logger.error("Error reading folder attributes for: {} - {}", folderPath, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Convert Instant to LocalDateTime in system default timezone
     */
    private LocalDateTime convertToLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    @Override
    public void writeFile(Item item, InputStream data) {
        if (item.getFullPath() == null || item.getFullPath().isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to write");
        }
        Path resolvedPath = resolveDirectoryPath(item.getFullPath());
        // Ensure parent directories exist
        Path parent = resolvedPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SystemIssueException("Error creating parent directories: " + parent, e);
            }
        }
        try {
            Files.copy(data, resolvedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SystemIssueException("Error writing file: " + resolvedPath, e);
        }
    }

    @Override
    public void deleteFile(Item item) {
        if (item.getFullPath() == null || item.getFullPath().isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to delete");
        }
        Path resolvedPath = resolveDirectoryPath(item.getFullPath());
        try {
            Files.deleteIfExists(resolvedPath);
        } catch (IOException e) {
            throw new SystemIssueException("Error deleting file: " + resolvedPath, e);
        }
    }

    @Override
    public void shutdown() {
        // Local file system provider doesn't maintain connections, so nothing to clean up
        logger.info("Shutting down local file system data provider for datasource: {}", 
            dataSource != null ? dataSource.id() : "unknown");
    }
}