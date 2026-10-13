package enterfind.server.scan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.UUID;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

/**
 * Shared SMB helpers for integration tests.
 * Encapsulates connection details, directory creation, file writing, and recursive deletion.
 */
class SmbTestHelper {

    static final String HOST = "localhost";
    static final String USER = "smbtest";
    static final String PASSWORD = "smbtest123";
    static final String SHARE = "scratch";
    private static final String TEST_PATH_PREFIX = "enterfind-server-tests/";

    @FunctionalInterface
    interface ShareOperation {
        void execute(DiskShare share) throws IOException;
    }

    /** Opens a connection to the test Samba share and runs the given operation. */
    static void withConnection(ShareOperation operation) throws IOException {
        try (SMBClient smbClient = new SMBClient()) {
            Connection connection = smbClient.connect(HOST);
            AuthenticationContext authContext = new AuthenticationContext(USER, PASSWORD.toCharArray(), "");
            Session session = connection.authenticate(authContext);
            DiskShare share = (DiskShare) session.connectShare(SHARE);
            try {
                operation.execute(share);
            } finally {
                share.close();
                session.close();
                connection.close();
            }
        }
    }

    /** Returns a unique path for a test run, e.g. {@code enterfind-server-tests/<uuid>}. */
    static String newTestPath() {
        return TEST_PATH_PREFIX + UUID.randomUUID();
    }

    /**
     * Creates {@code path} and all intermediate directories on the share.
     * Safe to call when any ancestor already exists.
     */
    static void mkdir(DiskShare share, String path) {
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (current.length() > 0) current.append("/");
            current.append(part);
            String segment = current.toString();
            if (!share.folderExists(segment)) {
                share.mkdir(segment);
            }
        }
    }

    /** Writes {@code content} (UTF-8) to the given path on the share. */
    static void writeFile(DiskShare share, String path, String content) throws IOException {
        try (File smbFile = openForWrite(share, path);
             OutputStream out = smbFile.getOutputStream()) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Writes bytes from {@code in} to the given path on the share. */
    static void writeFile(DiskShare share, String path, InputStream in) throws IOException {
        try (File smbFile = openForWrite(share, path);
             OutputStream out = smbFile.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /** Recursively deletes a folder and all its contents on the share. */
    static void deleteRecursively(DiskShare share, String folderPath) {
        if (!share.folderExists(folderPath)) return;
        for (FileIdBothDirectoryInformation entry : share.list(folderPath)) {
            String name = entry.getFileName();
            if (".".equals(name) || "..".equals(name)) continue;
            String childPath = folderPath + "/" + name;
            if ((entry.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0) {
                deleteRecursively(share, childPath);
                if (share.folderExists(childPath)) share.rmdir(childPath, false);
            } else {
                if (share.fileExists(childPath)) share.rm(childPath);
            }
        }
        if (share.folderExists(folderPath)) share.rmdir(folderPath, false);
    }

    private static File openForWrite(DiskShare share, String path) {
        return share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.allOf(SMB2ShareAccess.class),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        );
    }

    private SmbTestHelper() {}
}
