package enterfind.server.scan;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hierynomus.smbj.share.File;

/**
 * Wrapper InputStream that automatically returns the SMB connection to the pool when closed.
 * This ensures proper resource management when reading files from SMB shares.
 */
public class SMBInputStream extends FilterInputStream {

    private static final Logger logger = LoggerFactory.getLogger(SMBInputStream.class);

    private final SMBConnectionPool connectionPool;
    private final SMBConnectionWrapper connectionWrapper;
    private final File smbFile;
    private volatile boolean closed = false;

    /**
     * Create a new SMBInputStream that wraps the given input stream and manages connection lifecycle.
     *
     * @param inputStream       The underlying input stream from the SMB file
     * @param smbFile           The SMB file handle (will be closed when this stream is closed)
     * @param connectionPool    The connection pool to return the connection to
     * @param connectionWrapper The connection wrapper to return to the pool
     */
    public SMBInputStream(InputStream inputStream, File smbFile, SMBConnectionPool connectionPool,
            SMBConnectionWrapper connectionWrapper) {
        super(inputStream);
        this.smbFile = smbFile;
        this.connectionPool = connectionPool;
        this.connectionWrapper = connectionWrapper;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        IOException exception = null;

        // First, close the underlying input stream
        try {
            super.close();
        } catch (IOException e) {
            exception = e;
            logger.warn("Error closing underlying input stream: {}", e.getMessage());
        }

        // Close the SMB file handle
        try {
            if (smbFile != null) {
                smbFile.close();
            }
        } catch (Exception e) {
            logger.warn("Error closing SMB file: {}", e.getMessage());
            if (exception == null) {
                exception = new IOException("Error closing SMB file", e);
            }
        }

        // Return the connection to the pool
        try {
            if (connectionPool != null && connectionWrapper != null) {
                connectionPool.returnConnection(connectionWrapper);
                logger.info("Returned SMB connection to pool after stream close");
            }
        } catch (Exception e) {
            logger.warn("Error returning connection to pool: {}", e.getMessage());
            // Try to invalidate the connection if return fails
            try {
                if (connectionPool != null && connectionWrapper != null) {
                    connectionPool.invalidateConnection(connectionWrapper);
                }
            } catch (Exception invalidateException) {
                logger.error("Error invalidating connection: {}", invalidateException.getMessage());
            }
        }

        if (exception != null) {
            throw exception;
        }
    }
}
