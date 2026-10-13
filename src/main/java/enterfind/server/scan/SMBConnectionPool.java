package enterfind.server.scan;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.RemoteFileSystemDataSource;

/**
 * Manages a pool of SMB connections for remote file system access.
 * Uses Apache Commons Pool to create, validate, and manage connections efficiently.
 */
public class SMBConnectionPool {
    
    private static final Logger logger = LoggerFactory.getLogger(SMBConnectionPool.class);
    
    // Default pool configuration values
    private static final int DEFAULT_MAX_TOTAL = 10;
    private static final int DEFAULT_MAX_IDLE = 5;
    private static final int DEFAULT_MIN_IDLE = 2;
    private static final long DEFAULT_MAX_WAIT_MILLIS = 30000;
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 300000; // 5 minutes
    private static final boolean DEFAULT_TEST_ON_BORROW = true;
    private static final boolean DEFAULT_TEST_ON_RETURN = false;
    private static final boolean DEFAULT_TEST_WHILE_IDLE = true;
    
    private final RemoteFileSystemDataSource dataSource;
    private final GenericObjectPool<SMBConnectionWrapper> pool;
    
    /**
     * Create a new connection pool with the given data source and default configuration
     */
    public SMBConnectionPool(RemoteFileSystemDataSource dataSource) {
        this(dataSource, createDefaultPoolConfig());
    }
    
    /**
     * Create a new connection pool with custom configuration
     */
    public SMBConnectionPool(RemoteFileSystemDataSource dataSource, GenericObjectPoolConfig<SMBConnectionWrapper> config) {
        this.dataSource = dataSource;
        
        SMBConnectionFactory factory = new SMBConnectionFactory(dataSource);
        this.pool = new GenericObjectPool<>(factory, config);
        
        logger.info("Created SMB connection pool for datasource {} with maxTotal={}, maxIdle={}, minIdle={}", 
            dataSource.id(), config.getMaxTotal(), config.getMaxIdle(), config.getMinIdle());
    }
    
    /**
     * Create default pool configuration with sensible defaults
     */
    @SuppressWarnings("deprecation")
    private static GenericObjectPoolConfig<SMBConnectionWrapper> createDefaultPoolConfig() {
        GenericObjectPoolConfig<SMBConnectionWrapper> config = new GenericObjectPoolConfig<>();
        
        // Set connection pool sizes
        config.setMaxTotal(DEFAULT_MAX_TOTAL);
        config.setMaxIdle(DEFAULT_MAX_IDLE);
        config.setMinIdle(DEFAULT_MIN_IDLE);
        
        // Set timeout and idle settings
        config.setMaxWaitMillis(DEFAULT_MAX_WAIT_MILLIS);
        config.setMinEvictableIdleTimeMillis(DEFAULT_IDLE_TIMEOUT_MILLIS);
        
        // Set validation policies
        config.setTestOnBorrow(DEFAULT_TEST_ON_BORROW);
        config.setTestOnReturn(DEFAULT_TEST_ON_RETURN);
        config.setTestWhileIdle(DEFAULT_TEST_WHILE_IDLE);
        
        // Enable eviction of idle connections
        config.setEvictionPolicy(new org.apache.commons.pool2.impl.DefaultEvictionPolicy<>());
        
        return config;
    }
    
    /**
     * Borrow a connection from the pool
     */
    public SMBConnectionWrapper borrowConnection() {
        try {
            SMBConnectionWrapper connection = pool.borrowObject();
            logger.info("Borrowed connection from pool for datasource: {}", dataSource.id());
            return connection;
        } catch (Exception e) {
            logger.error("Failed to borrow connection from pool for datasource {}: {}", 
                dataSource.id(), e.getMessage());
            throw new SystemIssueException("Failed to get SMB connection from pool for datasource: " + dataSource.id(), e);
        }
    }
    
    /**
     * Return a connection to the pool
     */
    public void returnConnection(SMBConnectionWrapper connection) {
        if (connection != null) {
            try {
                pool.returnObject(connection);
                logger.info("Returned connection to pool for datasource: {}", dataSource.id());
            } catch (Exception e) {
                logger.error("Failed to return connection to pool for datasource {}: {}", 
                    dataSource.id(), e.getMessage());
                // Try to invalidate the connection
                try {
                    pool.invalidateObject(connection);
                } catch (Exception invalidateException) {
                    logger.error("Failed to invalidate invalid connection for datasource {}: {}", 
                        dataSource.id(), invalidateException.getMessage());
                }
            }
        }
    }
    
    /**
     * Invalidate a broken connection (remove it from the pool)
     */
    public void invalidateConnection(SMBConnectionWrapper connection) {
        if (connection != null) {
            try {
                pool.invalidateObject(connection);
                logger.info("Invalidated broken connection for datasource: {}", dataSource.id());
            } catch (Exception e) {
                logger.error("Failed to invalidate connection for datasource {}: {}", 
                    dataSource.id(), e.getMessage());
            }
        }
    }
    
    /**
     * Close the connection pool and release all resources
     */
    public void close() {
        try {
            pool.close();
            logger.info("Closed SMB connection pool for datasource: {}", dataSource.id());
        } catch (Exception e) {
            logger.warn("Error closing connection pool for datasource {}: {}", 
                dataSource.id(), e.getMessage());
        }
    }
    
    /**
     * Get the number of active connections
     */
    public int getNumActive() {
        return pool.getNumActive();
    }
    
    /**
     * Get the number of idle connections
     */
    public int getNumIdle() {
        return pool.getNumIdle();
    }
    
    /**
     * Clear the connection pool
     */
    public void clear() {
        try {
            pool.clear();
            logger.info("Cleared connection pool for datasource: {}", dataSource.id());
        } catch (Exception e) {
            logger.error("Error clearing connection pool for datasource {}: {}", 
                dataSource.id(), e.getMessage());
        }
    }
}
