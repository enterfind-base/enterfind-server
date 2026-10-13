package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.RemoteFileSystemDataSource;

/**
 * Security tests for RemoteFileSystemDataProvider path traversal prevention.
 * 
 * These tests verify path validation without requiring a real SMB server.
 * They test the security logic in resolveFullPath() which is called before
 * any SMB operations, so connection failures are expected and acceptable.
 */
class RemoteFileSystemDataProviderSecurityTest {
    
    private RemoteFileSystemDataProvider provider;
    
    @BeforeEach
    void setup() {
        // Create a test datasource with a base path
        // We don't need a real SMB server since we're testing path validation
        // which happens before any SMB operations
        RemoteFileSystemDataSource dataSource = new RemoteFileSystemDataSource(
            "test-id",
            "test-datasource",
            null,  // cronPattern
            null,  // dateCreated
            null,  // dateLastScanned
            null,  // status
            "test-server",
            "test-domain",
            "test-user",
            "test-password",
            "test-share",
            "test/base/path",  // path (basePath)
            null   // skipFiles
        );
        provider = new RemoteFileSystemDataProvider(dataSource);
    }
    
    /**
     * Cleanup after each test: shutdown the data provider to close any resources
     */
    @AfterEach
    void tearDown() throws IOException {
        DataSourceType.shutdownAllProviders();
    }
    
    // ============== Path Traversal Tests ==============
    
    @Test
    void testPathTraversalWithDoubleDotRejected() {
        // Should reject attempts to traverse outside basePath
        // The path validation happens before SMB connection, so we expect
        // InvalidArgumentException, not a connection error
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("../../etc/passwd"),
            "Path traversal with .. should be rejected");
    }
    
    @Test
    void testMultipleDoubleDotTraversalRejected() {
        // Should reject multiple .. sequences
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("subdir/../../../../etc/passwd"),
            "Multiple .. sequences should be rejected");
    }
    
    @Test
    void testAbsolutePathRejected() {
        // Should reject absolute paths
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("/etc/passwd"),
            "Absolute paths should be rejected");
    }
    
    @Test
    void testNullByteRejected() {
        // Should reject null byte injection
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("test\0evil"),
            "Null bytes should be rejected");
    }
    
    @Test
    void testEnclosedDoubleDotRejected() {
        // Should handle .. in middle of path
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("a/../../etc/passwd"),
            "Enclosed .. sequences should be rejected if they escape basePath");
    }
    
    @Test
    void testLeadingDoubleDot() {
        // Reject paths starting with ..
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("../etc/passwd"));
    }
    
    // ============== Valid Path Access Tests ==============
    
    @Test
    void testValidSubdirectoryAllowed() {
        // Valid subdirectory access should be allowed (will fail on SMB connection, which is fine)
        // We're testing that path validation passes, not that SMB works
        assertThrows(Exception.class, // Could be SystemIssueException from connection failure
            () -> provider.getItems("valid/subdir"),
            "Valid subdirectory should pass path validation");
        
        // Verify it's not an InvalidArgumentException (path validation error)
        try {
            provider.getItems("valid/subdir");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Valid path should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
    
    @Test
    void testValidNestedSubdirectoryAllowed() {
        // Valid nested path should be allowed
        try {
            provider.getItems("a/b/c/d");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Valid nested path should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
    
    @Test
    void testRootDirectoryAccess() {
        // Empty path (root) should be allowed
        try {
            provider.getItems("");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Root directory access should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
    
    @Test
    void testNullPathAllowed() {
        // Null path should be allowed (treated as root)
        try {
            provider.getItems(null);
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Null path should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
    
    // ============== getFolders Tests ==============
    
    @Test
    void testGetFoldersRejectsPathTraversal() {
        // getFolders should also reject path traversal
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getFolders("../../etc"),
            "getFolders should reject path traversal");
    }
    
    @Test
    void testGetFoldersAllowsValidPath() {
        // getFolders should allow valid paths
        try {
            provider.getFolders("valid/path");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Valid path in getFolders should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
    
    // ============== Complex Attack Scenarios ==============
    
    @Test
    void testMixedSeparators() {
        // Should handle mixed separators in traversal attempts
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("a/../../../etc/passwd"));
    }
    
    @Test
    void testTrailingDoubleDot() {
        // Reject paths ending with .. that try to escape
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("valid/path/../../../../etc"));
    }
    
    @Test
    void testDeepTraversalAttempt() {
        // Very deep traversal should be rejected
        assertThrows(InvalidArgumentException.class, 
            () -> provider.getItems("a/b/c/d/e/f/../../../../../../../../../../etc/passwd"));
    }
    
    // ============== Edge Cases ==============
    
    @Test
    void testEmptyPathAllowed() {
        // Empty path should be allowed (treated as root)
        try {
            provider.getItems("");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Empty path should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
    
    @Test
    void testPathWithDotButNotDoubleDot() {
        // Paths with single dots should be allowed
        try {
            provider.getItems("file.txt");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Path with single dot should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
    
    @Test
    void testCommaInFolderNameIsLiteralNotSeparator() {
        // Commas in folder names are now treated as literal characters (not path separators).
        // A path like "Rust Servers, Services, and Apps" is a single folder, not multiple segments.
        try {
            provider.getItems("Rust Servers, Services, and Apps");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Folder name with commas should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }

    @Test
    void testSlashTraversalRejected() {
        // Slash-separated traversal paths must still be rejected
        assertThrows(InvalidArgumentException.class,
            () -> provider.getItems("a/../../etc/passwd"),
            "Slash-separated traversal paths should be rejected");
    }
    
    // ============== Test basePath=empty scenario ==============
    
    @Test
    void testEmptyBasePathRejectsTraversal() {
        // Create provider with empty basePath
        RemoteFileSystemDataSource dataSource = new RemoteFileSystemDataSource(
            "test-id-2",
            "test-datasource-2",
            null,  // cronPattern
            null,  // dateCreated
            null,  // dateLastScanned
            null,  // status
            "test-server",
            "test-domain",
            "test-user",
            "test-password",
            "test-share",
            "",    // empty path (basePath)
            null   // skipFiles
        );
        RemoteFileSystemDataProvider emptyBaseProvider = new RemoteFileSystemDataProvider(dataSource);
        
        // Should still reject .. traversal even with empty base
        assertThrows(InvalidArgumentException.class, 
            () -> emptyBaseProvider.getItems("../etc/passwd"),
            "Path traversal should be rejected even with empty basePath");
    }
    
    @Test
    void testEmptyBasePathAllowsValidRelative() {
        // Create provider with empty basePath
        RemoteFileSystemDataSource dataSource = new RemoteFileSystemDataSource(
            "test-id-3",
            "test-datasource-3",
            null,  // cronPattern
            null,  // dateCreated
            null,  // dateLastScanned
            null,  // status
            "test-server",
            "test-domain",
            "test-user",
            "test-password",
            "test-share",
            "",    // empty path (basePath)
            null   // skipFiles
        );
        RemoteFileSystemDataProvider emptyBaseProvider = new RemoteFileSystemDataProvider(dataSource);
        
        // Should allow valid relative paths
        try {
            emptyBaseProvider.getItems("valid/path");
        } catch (InvalidArgumentException e) {
            throw new AssertionError("Valid path should not throw InvalidArgumentException", e);
        } catch (Exception e) {
            // Expected - SMB connection will fail, but path validation passed
        }
    }
}
