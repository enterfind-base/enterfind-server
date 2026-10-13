package enterfind.server.scan;

import java.io.IOException;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.RemoteFileSystemDataSource;

/**
 * Factory for creating and managing pooled SMB connections.
 * Implements Apache Commons Pool's PooledObjectFactory interface.
 */
public class SMBConnectionFactory implements PooledObjectFactory<SMBConnectionWrapper> {
    
    private static final Logger logger = LoggerFactory.getLogger(SMBConnectionFactory.class);
    
    private final RemoteFileSystemDataSource dataSource;
    
    public SMBConnectionFactory(RemoteFileSystemDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create a new SMB connection wrapper
     */
    @Override
    public PooledObject<SMBConnectionWrapper> makeObject() throws Exception {
        try {
            SMBClient smbClient = new SMBClient();
            Connection connection = smbClient.connect(dataSource.server());

            // Create authentication context
            AuthenticationContext authContext;
            if (dataSource.domain() != null && !dataSource.domain().isEmpty()) {
                authContext = new AuthenticationContext(dataSource.username(),
                    dataSource.password().toCharArray(), dataSource.domain());
            } else {
                authContext = new AuthenticationContext(dataSource.username(),
                    dataSource.password().toCharArray(), "");
            }

            Session session = connection.authenticate(authContext);
            DiskShare diskShare = (DiskShare) session.connectShare(dataSource.shareName());

            SMBConnectionWrapper wrapper = new SMBConnectionWrapper(smbClient, connection, session, diskShare);
            
            logger.info("Created new SMB connection pool object for datasource: {}", dataSource.id());
            return new DefaultPooledObject<>(wrapper);
            
        } catch (Exception e) {
            logger.error("Failed to create SMB connection for datasource {}: {}", 
                dataSource.id(), e.getMessage());
            throw new SystemIssueException("Failed to create SMB connection to server: " + dataSource.server(), e);
        }
    }

    /**
     * Destroy a connection wrapper when evicted from the pool
     */
    @Override
    public void destroyObject(PooledObject<SMBConnectionWrapper> p) throws Exception {
        try {
            p.getObject().close();
            logger.info("Destroyed SMB connection pool object for datasource: {}", dataSource.id());
        } catch (IOException e) {
            logger.warn("Error destroying SMB connection for datasource {}: {}", 
                dataSource.id(), e.getMessage());
        }
    }

    /**
     * Validate that a connection is still alive and usable
     */
    @Override
    public boolean validateObject(PooledObject<SMBConnectionWrapper> p) {
        try {
            SMBConnectionWrapper wrapper = p.getObject();
            // Check if the connection is still connected
            if (wrapper.getConnection() == null || !wrapper.getConnection().isConnected()) {
                logger.warn("Connection validation failed for datasource {}: connection is not connected", 
                    dataSource.id());
                return false;
            }
            logger.info("Connection validation passed for datasource: {}", dataSource.id());
            return true;
        } catch (Exception e) {
            logger.warn("Connection validation error for datasource {}: {}", 
                dataSource.id(), e.getMessage());
            return false;
        }
    }

    /**
     * Activate a connection when it's borrowed from the pool
     */
    @Override
    public void activateObject(PooledObject<SMBConnectionWrapper> p) throws Exception {
        logger.info("Activated SMB connection for datasource: {}", dataSource.id());
    }

    /**
     * Passivate a connection when it's returned to the pool
     */
    @Override
    public void passivateObject(PooledObject<SMBConnectionWrapper> p) throws Exception {
        logger.info("Passivated SMB connection for datasource: {}", dataSource.id());
    }
}
