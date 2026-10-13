package enterfind.server.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.input.CreateLocalFileSystemDataSourceInput;
import enterfind.server.model.input.CreateRemoteFileSystemDataSourceInput;
import enterfind.server.model.input.CreateS3BucketDataSourceInput;
import enterfind.server.model.input.UpdateLocalFileSystemDataSourceInput;
import enterfind.server.model.input.UpdateRemoteFileSystemDataSourceInput;
import enterfind.server.model.input.UpdateS3BucketDataSourceInput;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.LocalFilesDataProvider;
import enterfind.server.scan.RemoteFileSystemDataProvider;
import enterfind.server.scan.S3BucketDataProvider;
import enterfind.server.util.CronExpressionValidator;

/**
 * Enumeration of supported datasource types
 */
public enum DataSourceType {
    LOCAL_FILESYSTEM {
        @Override
        public DataProviderInterface getDataProviderForTypeAndSource(DataSourceInterface dataSource) {
            String datasourceId = extractDatasourceId(dataSource);
            return DATA_PROVIDER_CACHE.computeIfAbsent(datasourceId, id -> {
                LocalFileSystemDataSource localDS = (LocalFileSystemDataSource) dataSource;
                return new LocalFilesDataProvider(localDS);
            });
        }
        
        @Override
        public String extractDatasourceId(DataSourceInterface dataSource) {
            return ((LocalFileSystemDataSource) dataSource).id();
        }
        
        @Override
        public Map<String, Object> serializeConfig(DataSourceInterface dataSource) {
            LocalFileSystemDataSource lfs = (LocalFileSystemDataSource) dataSource;
            Map<String, Object> config = new HashMap<>();
            config.put(FIELD_LOCAL_PATH, lfs.localPath());
            config.put(FIELD_SKIP_FILES, lfs.skipFiles());
            return config;
        }
        
        @Override
        public DataSourceInterface createDataSourceInstance(String id, String name,
                String cronPattern, LocalDateTime dateCreated, LocalDateTime dateLastScanned,
                DataSourceStatus status, JsonNode config) throws JacksonException {
            return new LocalFileSystemDataSource(
                id, name, cronPattern, dateCreated, dateLastScanned, status,
                config.has(FIELD_LOCAL_PATH) && !config.get(FIELD_LOCAL_PATH).isNull() ? config.get(FIELD_LOCAL_PATH).asString() : "",
                config.has(FIELD_SKIP_FILES) && !config.get(FIELD_SKIP_FILES).isNull() ? config.get(FIELD_SKIP_FILES).asString() : null
            );
        }
        
        @Override
        public DataSourceInterface buildFromCreateInput(Object input) {
            CreateLocalFileSystemDataSourceInput createInput = (CreateLocalFileSystemDataSourceInput) input;
            // Validate cron pattern
            CronExpressionValidator.isValid(createInput.cronPattern());
            return new LocalFileSystemDataSource(
                null,
                createInput.name(),
                createInput.cronPattern(),
                null,
                null,
                DataSourceStatus.IDLE,
                createInput.localPath(),
                createInput.skipFiles()
            );
        }
        
        @Override
        public DataSourceInterface buildFromUpdateInput(DataSourceInterface existing, Object input) {
            LocalFileSystemDataSource lfs = (LocalFileSystemDataSource) existing;
            UpdateLocalFileSystemDataSourceInput updateInput = (UpdateLocalFileSystemDataSourceInput) input;
            // Validate cron pattern
            if (updateInput.cronPattern() != null) {
                CronExpressionValidator.isValid(updateInput.cronPattern());
            }
            return new LocalFileSystemDataSource(
                lfs.id(),
                updateInput.name() != null ? updateInput.name() : lfs.name(),
                updateInput.cronPattern() != null ? updateInput.cronPattern() : lfs.cronPattern(),
                lfs.dateCreated(),
                lfs.dateLastScanned(),
                lfs.status(),
                updateInput.localPath() != null ? updateInput.localPath() : lfs.localPath(),
                updateInput.skipFiles() != null ? updateInput.skipFiles() : lfs.skipFiles()
            );
        }
    },
    
    REMOTE_FILESYSTEM {
        @Override
        public DataProviderInterface getDataProviderForTypeAndSource(DataSourceInterface dataSource) {
            String datasourceId = extractDatasourceId(dataSource);
            return DATA_PROVIDER_CACHE.computeIfAbsent(datasourceId, id -> {
                RemoteFileSystemDataSource remoteDS = (RemoteFileSystemDataSource) dataSource;
                return new RemoteFileSystemDataProvider(remoteDS);
            });
        }
        
        @Override
        public String extractDatasourceId(DataSourceInterface dataSource) {
            return ((RemoteFileSystemDataSource) dataSource).id();
        }
        
        @Override
        public Map<String, Object> serializeConfig(DataSourceInterface dataSource) {
            RemoteFileSystemDataSource rfs = (RemoteFileSystemDataSource) dataSource;
            Map<String, Object> config = new HashMap<>();
            config.put(FIELD_SERVER, rfs.server());
            config.put(FIELD_DOMAIN, rfs.domain());
            config.put(FIELD_USERNAME, rfs.username());
            config.put(FIELD_PASSWORD, rfs.password());
            config.put(FIELD_SHARE_NAME, rfs.shareName());
            config.put(FIELD_PATH, rfs.path());
            config.put(FIELD_SKIP_FILES, rfs.skipFiles());
            return config;
        }
        
        @Override
        public DataSourceInterface createDataSourceInstance(String id, String name,
                String cronPattern, LocalDateTime dateCreated, LocalDateTime dateLastScanned,
                DataSourceStatus status, JsonNode config) throws JacksonException {
            return new RemoteFileSystemDataSource(
                id, name, cronPattern, dateCreated, dateLastScanned, status,
                config.has(FIELD_SERVER) && !config.get(FIELD_SERVER).isNull() ? config.get(FIELD_SERVER).asString() : "",
                config.has(FIELD_DOMAIN) && !config.get(FIELD_DOMAIN).isNull() ? config.get(FIELD_DOMAIN).asString() : null,
                config.has(FIELD_USERNAME) && !config.get(FIELD_USERNAME).isNull() ? config.get(FIELD_USERNAME).asString() : "",
                config.has(FIELD_PASSWORD) && !config.get(FIELD_PASSWORD).isNull() ? config.get(FIELD_PASSWORD).asString() : "",
                config.has(FIELD_SHARE_NAME) && !config.get(FIELD_SHARE_NAME).isNull() ? config.get(FIELD_SHARE_NAME).asString() : "",
                config.has(FIELD_PATH) && !config.get(FIELD_PATH).isNull() ? config.get(FIELD_PATH).asString() : "",
                config.has(FIELD_SKIP_FILES) && !config.get(FIELD_SKIP_FILES).isNull() ? config.get(FIELD_SKIP_FILES).asString() : null
            );
        }
        
        @Override
        public DataSourceInterface buildFromCreateInput(Object input) {
            CreateRemoteFileSystemDataSourceInput createInput = (CreateRemoteFileSystemDataSourceInput) input;
            // Validate cron pattern
            CronExpressionValidator.isValid(createInput.cronPattern());
            return new RemoteFileSystemDataSource(
                null,
                createInput.name(),
                createInput.cronPattern(),
                null,
                null,
                DataSourceStatus.IDLE,
                createInput.server(),
                createInput.domain(),
                createInput.username(),
                createInput.password(),
                createInput.shareName(),
                createInput.path() != null ? createInput.path() : "",
                createInput.skipFiles()
            );
        }
        
        @Override
        public DataSourceInterface buildFromUpdateInput(DataSourceInterface existing, Object input) {
            RemoteFileSystemDataSource rfs = (RemoteFileSystemDataSource) existing;
            UpdateRemoteFileSystemDataSourceInput updateInput = (UpdateRemoteFileSystemDataSourceInput) input;
            // Validate cron pattern
            if (updateInput.cronPattern() != null) {
                CronExpressionValidator.isValid(updateInput.cronPattern());
            }
            return new RemoteFileSystemDataSource(
                rfs.id(),
                updateInput.name() != null ? updateInput.name() : rfs.name(),
                updateInput.cronPattern() != null ? updateInput.cronPattern() : rfs.cronPattern(),
                rfs.dateCreated(),
                rfs.dateLastScanned(),
                rfs.status(),
                updateInput.server() != null ? updateInput.server() : rfs.server(),
                updateInput.domain() != null ? updateInput.domain() : rfs.domain(),
                updateInput.username() != null ? updateInput.username() : rfs.username(),
                updateInput.password() != null ? updateInput.password() : rfs.password(),
                updateInput.shareName() != null ? updateInput.shareName() : rfs.shareName(),
                updateInput.path() != null ? updateInput.path() : rfs.path(),
                updateInput.skipFiles() != null ? updateInput.skipFiles() : rfs.skipFiles()
            );
        }
    },
    
    S3_BUCKET {
        @Override
        public DataProviderInterface getDataProviderForTypeAndSource(DataSourceInterface dataSource) {
            String datasourceId = extractDatasourceId(dataSource);
            return DATA_PROVIDER_CACHE.computeIfAbsent(datasourceId, id -> {
                S3BucketDataSource s3DS = (S3BucketDataSource) dataSource;
                return new S3BucketDataProvider(s3DS);
            });
        }
        
        @Override
        public String extractDatasourceId(DataSourceInterface dataSource) {
            return ((S3BucketDataSource) dataSource).id();
        }
        
        @Override
        public Map<String, Object> serializeConfig(DataSourceInterface dataSource) {
            S3BucketDataSource s3 = (S3BucketDataSource) dataSource;
            Map<String, Object> config = new HashMap<>();
            config.put(FIELD_NAME_OR_URL, s3.nameOrUrl());
            config.put(FIELD_ACCESS_KEY, s3.accessKey());
            config.put(FIELD_SECRET_KEY, s3.secretKey());
            config.put(FIELD_PATH, s3.path());
            config.put(FIELD_REGION, s3.region());
            config.put(FIELD_SKIP_FILES, s3.skipFiles());
            return config;
        }
        
        @Override
        public DataSourceInterface createDataSourceInstance(String id, String name,
                String cronPattern, LocalDateTime dateCreated, LocalDateTime dateLastScanned,
                DataSourceStatus status, JsonNode config) throws JacksonException {
            return new S3BucketDataSource(
                id, name, cronPattern, dateCreated, dateLastScanned, status,
                config.has(FIELD_NAME_OR_URL) && !config.get(FIELD_NAME_OR_URL).isNull() ? config.get(FIELD_NAME_OR_URL).asString() : "",
                config.has(FIELD_ACCESS_KEY) && !config.get(FIELD_ACCESS_KEY).isNull() ? config.get(FIELD_ACCESS_KEY).asString() : "",
                config.has(FIELD_SECRET_KEY) && !config.get(FIELD_SECRET_KEY).isNull() ? config.get(FIELD_SECRET_KEY).asString() : "",
                config.has(FIELD_PATH) && !config.get(FIELD_PATH).isNull() ? config.get(FIELD_PATH).asString() : "",
                config.has(FIELD_REGION) && !config.get(FIELD_REGION).isNull() ? config.get(FIELD_REGION).asString() : null,
                config.has(FIELD_SKIP_FILES) && !config.get(FIELD_SKIP_FILES).isNull() ? config.get(FIELD_SKIP_FILES).asString() : null
            );
        }
        
        @Override
        public DataSourceInterface buildFromCreateInput(Object input) {
            CreateS3BucketDataSourceInput createInput = (CreateS3BucketDataSourceInput) input;
            // Validate cron pattern
            CronExpressionValidator.isValid(createInput.cronPattern());
            return new S3BucketDataSource(
                null,
                createInput.name(),
                createInput.cronPattern(),
                null,
                null,
                DataSourceStatus.IDLE,
                createInput.nameOrUrl(),
                createInput.accessKey(),
                createInput.secretKey(),
                createInput.path() != null ? createInput.path() : "",
                createInput.region(),
                createInput.skipFiles()
            );
        }
        
        @Override
        public DataSourceInterface buildFromUpdateInput(DataSourceInterface existing, Object input) {
            S3BucketDataSource s3 = (S3BucketDataSource) existing;
            UpdateS3BucketDataSourceInput updateInput = (UpdateS3BucketDataSourceInput) input;
            // Validate cron pattern
            if (updateInput.cronPattern() != null) {
                CronExpressionValidator.isValid(updateInput.cronPattern());
            }
            return new S3BucketDataSource(
                s3.id(),
                updateInput.name() != null ? updateInput.name() : s3.name(),
                updateInput.cronPattern() != null ? updateInput.cronPattern() : s3.cronPattern(),
                s3.dateCreated(),
                s3.dateLastScanned(),
                s3.status(),
                updateInput.nameOrUrl() != null ? updateInput.nameOrUrl() : s3.nameOrUrl(),
                updateInput.accessKey() != null ? updateInput.accessKey() : s3.accessKey(),
                updateInput.secretKey() != null ? updateInput.secretKey() : s3.secretKey(),
                updateInput.path() != null ? updateInput.path() : s3.path(),
                updateInput.region() != null ? updateInput.region() : s3.region(),
                updateInput.skipFiles() != null ? updateInput.skipFiles() : s3.skipFiles()
            );
        }
    };
    
    // Configuration field name constants
    public static final String FIELD_LOCAL_PATH = "localPath";
    public static final String FIELD_SERVER = "server";
    public static final String FIELD_DOMAIN = "domain";
    public static final String FIELD_USERNAME = "username";
    public static final String FIELD_PASSWORD = "password";
    public static final String FIELD_SHARE_NAME = "shareName";
    public static final String FIELD_PATH = "path";
    public static final String FIELD_NAME_OR_URL = "nameOrUrl";
    public static final String FIELD_ACCESS_KEY = "accessKey";
    public static final String FIELD_SECRET_KEY = "secretKey";
    public static final String FIELD_REGION = "region";
    public static final String FIELD_SKIP_FILES = "skipFiles";

    // Static cache for data providers, keyed by datasource ID
    private static final Map<String, DataProviderInterface> DATA_PROVIDER_CACHE = new ConcurrentHashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(DataSourceType.class);
    
    /**
     * Create appropriate data provider based on datasource type
     * @param dataSource the datasource to create a provider for
     * @return the appropriate DataProviderInterface implementation
     */
    public abstract DataProviderInterface getDataProviderForTypeAndSource(DataSourceInterface dataSource);
    
    /**
     * Extract datasource ID from the datasource interface
     * @param dataSource the datasource to extract ID from  
     * @return the datasource ID
     */
    public abstract String extractDatasourceId(DataSourceInterface dataSource);
    
    /**
     * Serialize datasource configuration to a Map for JSON conversion
     * @param dataSource the datasource to serialize config from
     * @return Map containing type-specific configuration fields
     */
    public abstract Map<String, Object> serializeConfig(DataSourceInterface dataSource);
    
    /**
     * Create datasource instance from type and configuration
     * @param id the datasource ID
     * @param name the datasource name
     * @param cronPattern the cron pattern
     * @param dateCreated the creation date
     * @param dateLastScanned the last scan date
     * @param status the datasource status
     * @param config the configuration as JsonNode
     * @return the appropriate DataSourceInterface implementation
     * @throws JacksonException if config parsing fails
     */
    public abstract DataSourceInterface createDataSourceInstance(String id, String name,
            String cronPattern, LocalDateTime dateCreated, LocalDateTime dateLastScanned,
            DataSourceStatus status, JsonNode config) throws JacksonException;
    
    /**
     * Build datasource instance from create input
     * @param input the create input object (will be cast to appropriate type)
     * @return the appropriate DataSourceInterface implementation
     */
    public abstract DataSourceInterface buildFromCreateInput(Object input);
    
    /**
     * Build updated datasource instance from update input
     * @param existing the existing datasource
     * @param input the update input object (will be cast to appropriate type)
     * @return the updated DataSourceInterface implementation
     */
    public abstract DataSourceInterface buildFromUpdateInput(DataSourceInterface existing, Object input);
    
    /**
     * Invalidate and shutdown a specific data provider from the cache.
     * Should be called when a datasource is updated or deleted to ensure fresh credentials are used.
     *
     * @param datasourceId the ID of the datasource to invalidate
     */
    public static void invalidateProvider(String datasourceId) {
        DataProviderInterface provider = DATA_PROVIDER_CACHE.remove(datasourceId);
        if (provider != null) {
            try {
                provider.shutdown();
                logger.info("Invalidated and shut down data provider for datasource: {}", datasourceId);
            } catch (Exception e) {
                logger.warn("Error shutting down data provider for datasource {}: {}", datasourceId, e.getMessage());
            }
        }
    }

    /**
     * Seed the provider cache with a specific provider, so a test can drive a scan against a
     * provider that fails in a controlled way. {@code getDataProviderForTypeAndSource} uses
     * {@code computeIfAbsent}, so a pre-seeded entry wins and the real provider is never built.
     *
     * <p>Exists solely for tests: the suite uses no mocking framework, and there is otherwise no
     * way to inject a failing listing for one subtree. Clear it with
     * {@link #invalidateProvider(String)} or {@link #shutdownAllProviders()}.
     */
    public static void registerProviderForTesting(String datasourceId, DataProviderInterface provider) {
        DATA_PROVIDER_CACHE.put(datasourceId, provider);
    }

    /**
     * Shutdown all cached data providers and clean up resources
     */
    public static void shutdownAllProviders() {
        java.util.List<String> failedShutdowns = new java.util.ArrayList<>();
        java.util.List<String> successfulShutdowns = new java.util.ArrayList<>();

        for (java.util.Map.Entry<String, DataProviderInterface> entry : DATA_PROVIDER_CACHE.entrySet()) {
            String datasourceId = entry.getKey();
            DataProviderInterface provider = entry.getValue();
            try {
                provider.shutdown();
                successfulShutdowns.add(datasourceId);
            } catch (Exception e) {
                failedShutdowns.add(datasourceId + ": " + e.getMessage());
            }
        }
        DATA_PROVIDER_CACHE.clear();

        if (!failedShutdowns.isEmpty()) {
            throw new SystemIssueException("Errors occurred while shutting down data providers: " + failedShutdowns);
        }
    }
}