package enterfind.server.scan;

import java.io.IOException;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

/**
 * Wrapper for SMB connection objects to be used in the connection pool.
 * Encapsulates SMBClient, Connection, Session, and DiskShare.
 */
public class SMBConnectionWrapper {
    
    private final SMBClient smbClient;
    private final Connection connection;
    private final Session session;
    private final DiskShare diskShare;
    
    public SMBConnectionWrapper(SMBClient smbClient, Connection connection, Session session, DiskShare diskShare) {
        this.smbClient = smbClient;
        this.connection = connection;
        this.session = session;
        this.diskShare = diskShare;
    }
    
    public SMBClient getSmbClient() {
        return smbClient;
    }
    
    public Connection getConnection() {
        return connection;
    }
    
    public Session getSession() {
        return session;
    }
    
    public DiskShare getDiskShare() {
        return diskShare;
    }
    
    /**
     * Close all SMB resources
     */
    public void close() throws IOException {
        IOException lastException = null;
        
        try {
            if (diskShare != null) {
                diskShare.close();
            }
        } catch (IOException e) {
            lastException = e;
        }
        
        try {
            if (session != null) {
                session.close();
            }
        } catch (IOException e) {
            lastException = e;
        }
        
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (IOException e) {
            lastException = e;
        }
        
        // SMBClient.close() may not throw IOException, so we ignore any exceptions
        if (smbClient != null) {
            try {
                smbClient.close();
            } catch (Exception e) {
                // Ignore exceptions from SMBClient.close()
            }
        }
        
        if (lastException != null) {
            throw lastException;
        }
    }
}
