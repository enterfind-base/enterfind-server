package enterfind.server.scan;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.RemoteFileSystemDataSource;
import enterfind.server.util.MimeTypeDetector;

/**
 * Data provider for scanning remote SMB file systems using SMBJ library with connection pooling.
 * Uses Apache Commons Pool to maintain and reuse SMB connections efficiently.
 */
public class RemoteFileSystemDataProvider implements DataProviderInterface {
    
    private static final Logger logger = LoggerFactory.getLogger(RemoteFileSystemDataProvider.class);
    
    private RemoteFileSystemDataSource dataSource;
    private String basePath;
    private SMBConnectionPool connectionPool;

    /**
     * Initialize the remote file system data provider with the given datasource
     */
    public RemoteFileSystemDataProvider(RemoteFileSystemDataSource dataSource) {
        this.dataSource = dataSource;
        this.basePath = dataSource.path() != null ? dataSource.path() : "";
        
        // Initialize SMB connection pool
        initializeConnectionPool();
    }

    @Override
    public List<Item> getItems(String path) {
        if (dataSource == null) {
            throw new InvalidArgumentException("DataSource must be set before scanning items");
        }

        List<Item> items = new ArrayList<>();
        String fullPath = resolveFullPath(path);
        
        SMBConnectionWrapper connectionWrapper = connectionPool.borrowConnection();
        try {
            DiskShare diskShare = connectionWrapper.getDiskShare();
            List<FileIdBothDirectoryInformation> files = diskShare.list(fullPath);
            
            for (FileIdBothDirectoryInformation fileInfo : files) {
                // Skip . and .. directories
                if (".".equals(fileInfo.getFileName()) || "..".equals(fileInfo.getFileName())) {
                    continue;
                }
                
                // Only process regular files, not directories
                if (!hasFileAttribute(fileInfo, FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())) {
                    Item item = createItemFromFileInfo(fileInfo, path);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
        } catch (Exception e) {
            connectionPool.invalidateConnection(connectionWrapper);
            throw new SystemIssueException("Error reading remote directory: " + fullPath, e);
        } finally {
            connectionPool.returnConnection(connectionWrapper);
        }

        return items;
    }

    @Override
    public List<Folder> getFolders(String path) {
        if (dataSource == null) {
            throw new InvalidArgumentException("DataSource must be set before scanning folders");
        }

        List<Folder> folders = new ArrayList<>();
        String fullPath = resolveFullPath(path);
        
        SMBConnectionWrapper connectionWrapper = connectionPool.borrowConnection();
        try {
            DiskShare diskShare = connectionWrapper.getDiskShare();
            List<FileIdBothDirectoryInformation> files = diskShare.list(fullPath);
            
            for (FileIdBothDirectoryInformation fileInfo : files) {
                // Skip . and .. directories
                if (".".equals(fileInfo.getFileName()) || "..".equals(fileInfo.getFileName())) {
                    continue;
                }
                
                // Only process directories
                if (hasFileAttribute(fileInfo, FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue())) {
                    Folder folder = createFolderFromFileInfo(fileInfo, path);
                    if (folder != null) {
                        folders.add(folder);
                    }
                }
            }
        } catch (Exception e) {
            connectionPool.invalidateConnection(connectionWrapper);
            throw new SystemIssueException("Error reading remote directory: " + fullPath, e);
        } finally {
            connectionPool.returnConnection(connectionWrapper);
        }

        return folders;
    }

    @Override
    public InputStream getInputStream(Item item) {
        String fullPath = item.getFullPath();
        if (fullPath == null || fullPath.isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to create input stream");
        }

        // Path is already slash-separated
        String smbPath = resolveFullPath(fullPath);
        
        SMBConnectionWrapper connectionWrapper = connectionPool.borrowConnection();
        try {
            DiskShare diskShare = connectionWrapper.getDiskShare();
            File smbFile = diskShare.openFile(smbPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null);
            
            InputStream underlyingStream = smbFile.getInputStream();
            // Wrap the input stream to ensure the connection is returned to the pool when closed
            return new SMBInputStream(underlyingStream, smbFile, connectionPool, connectionWrapper);
        } catch (Exception e) {
            connectionPool.invalidateConnection(connectionWrapper);
            throw new SystemIssueException("Error creating input stream for remote file: " + smbPath, e);
        }
    }

    /**
     * Initialize the SMB connection pool
     */
    private void initializeConnectionPool() {
        try {
            this.connectionPool = new SMBConnectionPool(dataSource);
            logger.info("Successfully initialized SMB connection pool for datasource: {}", dataSource.id());
        } catch (Exception e) {
            throw new SystemIssueException("Failed to initialize SMB connection pool for server: " + dataSource.server(), e);
        }
    }

    /**
     * Resolve the full SMB path based on the base path and relative path.
     * Implements security checks to prevent path traversal attacks:
     * - Rejects null bytes
     * - Rejects absolute paths
     * - Normalizes paths to prevent .. traversal
     * - Validates the resolved path is within basePath boundaries
     */
    private String resolveFullPath(String path) {
        if (path == null || path.isEmpty()) {
            return basePath.isEmpty() ? "" : basePath;
        }
        
        // Path is already slash-separated
        String relativePath = path;
        
        // Security check: reject null bytes (potential security attack)
        if (relativePath.contains("\0")) {
            throw new InvalidArgumentException(
                "Access denied: Path contains null bytes");
        }
        
        // Security check: reject absolute paths (e.g., /etc/passwd)
        Path testPath = Paths.get(relativePath);
        if (testPath.isAbsolute()) {
            throw new InvalidArgumentException(
                "Access denied: Absolute paths are not allowed");
        }
        
        // Build the full path
        String fullPath;
        if (basePath.isEmpty()) {
            fullPath = relativePath;
        } else {
            fullPath = basePath + "/" + relativePath;
        }
        
        // Security check: normalize path and validate boundaries
        // For SMB paths, we use Java NIO path normalization to detect .. traversal
        Path normalizedRelative = Paths.get(relativePath).normalize();
        Path normalizedBase = basePath.isEmpty() ? Paths.get("") : Paths.get(basePath).normalize();
        Path normalizedFull = basePath.isEmpty() 
            ? normalizedRelative 
            : Paths.get(basePath, relativePath).normalize();
        
        // Verify the normalized full path starts with the normalized base path
        // This prevents .. traversal attacks like "../../etc/passwd"
        if (!basePath.isEmpty()) {
            // Convert both to strings for comparison (SMB paths use /)
            String normalizedFullStr = normalizedFull.toString().replace("\\", "/");
            String normalizedBaseStr = normalizedBase.toString().replace("\\", "/");
            
            // Check if normalized path escapes the base path
            if (!normalizedFullStr.equals(normalizedBaseStr) && 
                !normalizedFullStr.startsWith(normalizedBaseStr + "/")) {
                throw new InvalidArgumentException(
                    "Access denied: Requested path is outside the allowed base directory");
            }
        } else {
            // If basePath is empty, ensure relativePath doesn't start with ..
            String normalizedStr = normalizedRelative.toString().replace("\\", "/");
            if (normalizedStr.startsWith("..") || normalizedStr.contains("/..")) {
                throw new InvalidArgumentException(
                    "Access denied: Path traversal not allowed");
            }
        }
        
        return fullPath;
    }

    /**
     * Create an Item object from SMB file information
     */
    private Item createItemFromFileInfo(FileIdBothDirectoryInformation fileInfo, String parentPath) {
        try {
            Item item = new Item();
            item.setName(fileInfo.getFileName());
            item.setPath(parentPath);
            
            // Convert file times to LocalDateTime
            item.setTimeCreated(convertToLocalDateTime(fileInfo.getCreationTime().toEpochMillis()));
            item.setTimeModified(convertToLocalDateTime(fileInfo.getLastWriteTime().toEpochMillis()));
            
            // Set file size
            item.setSizeBytes(fileInfo.getEndOfFile());

            // Determine MIME type based on file extension
            String mimeType = MimeTypeDetector.detectMimeType(fileInfo.getFileName());
            item.setMimeType(mimeType);
            
            return item;
        } catch (Exception e) {
            // Log error but don't stop scanning
            logger.error("Error creating item from SMB file info for: {} - {}", fileInfo.getFileName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create a Folder object from SMB directory information
     */
    private Folder createFolderFromFileInfo(FileIdBothDirectoryInformation fileInfo, String parentPath) {
        try {
            Folder folder = new Folder();
            folder.setName(fileInfo.getFileName());
            folder.setPath(parentPath);
            
            // Convert file times to LocalDateTime
            folder.setTimeCreated(convertToLocalDateTime(fileInfo.getCreationTime().toEpochMillis()));
            folder.setTimeModified(convertToLocalDateTime(fileInfo.getLastWriteTime().toEpochMillis()));
            
            return folder;
        } catch (Exception e) {
            // Log error but don't stop scanning
            logger.error("Error creating folder from SMB file info for: {} - {}", fileInfo.getFileName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Convert milliseconds to LocalDateTime in system default timezone
     */
    private LocalDateTime convertToLocalDateTime(long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /**
     * Check if file has specific attribute
     */
    private boolean hasFileAttribute(FileIdBothDirectoryInformation fileInfo, long attribute) {
        return (fileInfo.getFileAttributes() & attribute) != 0;
    }

    @Override
    public void writeFile(Item item, InputStream data) {
        if (item.getFullPath() == null || item.getFullPath().isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to write");
        }
        String smbPath = resolveFullPath(item.getFullPath());
        SMBConnectionWrapper connectionWrapper = connectionPool.borrowConnection();
        try {
            DiskShare diskShare = connectionWrapper.getDiskShare();
            com.hierynomus.smbj.share.File smbFile = diskShare.openFile(
                smbPath,
                java.util.EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null);
            try (java.io.OutputStream os = smbFile.getOutputStream()) {
                data.transferTo(os);
            } finally {
                smbFile.close();
            }
        } catch (Exception e) {
            connectionPool.invalidateConnection(connectionWrapper);
            throw new SystemIssueException("Error writing file to SMB: " + smbPath, e);
        } finally {
            connectionPool.returnConnection(connectionWrapper);
        }
    }

    @Override
    public void deleteFile(Item item) {
        if (item.getFullPath() == null || item.getFullPath().isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to delete");
        }
        String smbPath = resolveFullPath(item.getFullPath());
        SMBConnectionWrapper connectionWrapper = connectionPool.borrowConnection();
        try {
            DiskShare diskShare = connectionWrapper.getDiskShare();
            diskShare.rm(smbPath);
        } catch (Exception e) {
            connectionPool.invalidateConnection(connectionWrapper);
            throw new SystemIssueException("Error deleting file from SMB: " + smbPath, e);
        } finally {
            connectionPool.returnConnection(connectionWrapper);
        }
    }

    /**
     * Shutdown the remote file system data provider and close the connection pool
     */
    @Override
    public void shutdown() {
        try {
            if (connectionPool != null) {
                connectionPool.close();
            }
            logger.info("Successfully closed SMB connection pool for datasource: {}",
                dataSource != null ? dataSource.id() : "unknown");
        } catch (Exception e) {
            logger.warn("Error closing connection pool: {}", e.getMessage());
        }
    }


}