
package enterfind.server.controller;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.Schema;
import enterfind.server.dao.SearchDAO;
import enterfind.server.dao.SortDirection;
import enterfind.server.dao.SystemSettingsDao;
import enterfind.server.dao.FaceDao;
import enterfind.server.dao.FaceGroupDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.FaceEmbeddingMode;
import enterfind.server.model.ItemFace;
import enterfind.server.dao.UserSettingsDao;
import enterfind.server.mcp.McpGraphQlContext;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.NotFoundException;
import enterfind.server.model.About;
import enterfind.server.model.AiProvider;
import enterfind.server.model.AiSearchInterpretation;
import enterfind.server.model.CronExplanation;
import enterfind.server.model.CronInterpretation;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStats;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.FacetValue;
import enterfind.server.model.SearchCondition;
import enterfind.server.model.SearchResult;
import enterfind.server.model.SkipPatternExplanation;
import enterfind.server.model.SkipPatternInterpretation;
import enterfind.server.model.TestDataSourceResult;
import enterfind.server.model.TreatmentInfo;
import enterfind.server.model.AiSettingName;
import enterfind.server.model.UserSetting;
import enterfind.server.model.UserSettingName;
import enterfind.server.model.input.AddDataSourceTreatmentInput;
import enterfind.server.model.input.CreateLocalFileSystemDataSourceInput;
import enterfind.server.model.input.CreateRemoteFileSystemDataSourceInput;
import enterfind.server.model.input.CreateS3BucketDataSourceInput;
import enterfind.server.model.input.SearchConditionInput;
import enterfind.server.model.input.UpdateDataSourceTreatmentConfigInput;
import enterfind.server.model.input.UpdateLocalFileSystemDataSourceInput;
import enterfind.server.model.input.UpdateRemoteFileSystemDataSourceInput;
import enterfind.server.model.input.UpdateS3BucketDataSourceInput;
import enterfind.server.config.AiQueryBudgetTracker;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Folder;
import enterfind.server.scan.Item;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.Treatments;
import enterfind.server.scan.treatment.FaceEmbeddingModelService;
import enterfind.server.security.AuthContext;
import enterfind.server.service.AiProviderFactory;
import enterfind.server.service.AiService;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.ItemStorageService;
import enterfind.server.util.ProfileHelper;
import reactor.core.publisher.Flux;

/**
 * GraphQL controller for handling GraphQL queries
 */
@Controller
public class GraphQLController {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLController.class);
    private final SearchDAO searchDAO;

    private final ProfileHelper profileHelper;
    private final DataSourceDao dataSourceDao;
    private final ItemStorageService itemStorageService;
    private final TreatmentDao treatmentDao;
    private final ObjectMapper objectMapper;
    private final Scanner scanner;
    private final Schema schema;
    private final UserSettingsDao userSettingsDao;
    private final SystemSettingsDao systemSettingsDao;
    private final AiService aiService;
    private final AiProviderFactory aiProviderFactory;
    private final AuthContext authContext;
    private final AiQueryBudgetTracker aiQueryBudgetTracker;
    private final FaceEmbeddingModelService faceEmbeddingModelService;
    private final FaceGroupDao faceGroupDao;
    private final FaceDao faceDao;

    @Value("${spring.application.name:enterfind-server}")
    private String applicationName;

    @Value("${app.version:0.0.1-SNAPSHOT}")
    private String applicationVersion;

    @Value("${application.description:Enterfind Server Application}")
    private String applicationDescription;

    public GraphQLController(ProfileHelper profileHelper, DataSourceDao dataSourceDao,
                           ItemStorageService itemStorageService, TreatmentDao treatmentDao, ObjectMapper objectMapper,
                           SearchDAO searchDAO, Scanner scanner, Schema schema,
                           UserSettingsDao userSettingsDao, SystemSettingsDao systemSettingsDao, AiService aiService,
                           AiProviderFactory aiProviderFactory, AuthContext authContext,
                           AiQueryBudgetTracker aiQueryBudgetTracker,
                           FaceEmbeddingModelService faceEmbeddingModelService,
                           FaceGroupDao faceGroupDao, FaceDao faceDao) {
        this.profileHelper = profileHelper;
        this.dataSourceDao = dataSourceDao;
        this.itemStorageService = itemStorageService;
        this.treatmentDao = treatmentDao;
        this.objectMapper = objectMapper;
        this.searchDAO = searchDAO;
        this.scanner = scanner;
        this.schema = schema;
        this.userSettingsDao = userSettingsDao;
        this.systemSettingsDao = systemSettingsDao;
        this.aiService = aiService;
        this.aiProviderFactory = aiProviderFactory;
        this.authContext = authContext;
        this.aiQueryBudgetTracker = aiQueryBudgetTracker;
        this.faceEmbeddingModelService = faceEmbeddingModelService;
        this.faceGroupDao = faceGroupDao;
        this.faceDao = faceDao;
    }

    // ================= Face embedding model + identity GraphQL API =================

    @QueryMapping
    public FaceEmbeddingModelStatus faceEmbeddingModel(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return toStatusView(faceEmbeddingModelService.getStatus());
    }

    @MutationMapping
    public FaceEmbeddingModelStatus setFaceEmbeddingModel(@Argument String token, @Argument String mode,
            @Argument String url, @Argument Boolean nonCommercialAck) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        FaceEmbeddingMode parsedMode;
        try {
            parsedMode = FaceEmbeddingMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentException("Unknown embedding mode: " + mode);
        }
        return toStatusView(
                faceEmbeddingModelService.setModel(parsedMode, url, Boolean.TRUE.equals(nonCommercialAck)));
    }

    @MutationMapping
    public FaceEmbeddingModelStatus setFaceGroupingThreshold(@Argument String token,
            @Argument Double threshold) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        if (threshold == null) {
            throw new InvalidArgumentException("A threshold is required");
        }
        return toStatusView(faceEmbeddingModelService.setGroupingThreshold(threshold));
    }

    @MutationMapping
    public Boolean renameFaceLabel(@Argument String token, @Argument String oldLabel, @Argument String newLabel) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        faceGroupDao.renameFaceLabel(oldLabel, newLabel);
        return true;
    }

    /** Map the service status to the GraphQL type, with mode as its String name. */
    private FaceEmbeddingModelStatus toStatusView(FaceEmbeddingModelService.FaceEmbeddingModelStatus status) {
        return new FaceEmbeddingModelStatus(status.mode().name(), status.url(),
                status.outputDim(), status.enabledDatasourceCount(), status.threshold(),
                status.derivedThreshold());
    }

    /** GraphQL view of the face embedding model status; mode is a plain String for the schema. */
    public record FaceEmbeddingModelStatus(String mode, String url, int outputDim, int enabledDatasourceCount,
            double threshold, double derivedThreshold) {}
    // ================= User Settings GraphQL API =================
    @QueryMapping
    public UserSetting userSetting(@Argument String token, @Argument String settingName) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        UserSettingName enumName = UserSettingName.fromString(settingName);
        return userSettingsDao.getAllSettings().stream()
            .filter(s -> s.settingName() == enumName)
            .map(s -> new UserSetting(s.settingName().toString(), s.value()))
            .findFirst().orElse(null);
    }

    @QueryMapping
    public List<UserSetting> userSettings(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return userSettingsDao.getAllSettings().stream()
            .map(s -> new UserSetting(s.settingName().toString(), s.value()))
            .toList();
    }

    @QueryMapping
    public List<UserSetting> aiSettings(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        // Sensitive keys are edited write-only: the DAO returns the real (decrypted) value, but this
        // read path is serialized to the browser, so redact secrets here rather than leak them.
        return systemSettingsDao.getAllAiSettings().stream()
            .map(s -> new UserSetting(
                s.settingName().toString(),
                AiSettingName.SENSITIVE.contains(s.settingName()) ? "" : s.value()))
            .toList();
    }

    @QueryMapping
    public AiProvider aiProvider(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return aiProviderFactory.getConfiguredProvider();
    }

    @MutationMapping
    public UserSetting setUserSetting(@Argument String token, @Argument String settingName, @Argument String value) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        UserSettingName enumName = UserSettingName.fromString(settingName);
        if (enumName == UserSettingName.API_SESSION_DURATION_MINUTES) {
            requireIntegerInRange(enumName, value, 1, AuthenticationService.MAX_API_SESSION_DURATION_MINUTES);
        }
        userSettingsDao.setSetting(enumName, value);
        return new UserSetting(enumName.toString(), value);
    }

    private static void requireIntegerInRange(UserSettingName name, String value, int min, int max) {
        String message = name + " must be a whole number between " + min + " and " + max;
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidArgumentException(message);
        }
        if (parsed < min || parsed > max) {
            throw new InvalidArgumentException(message);
        }
    }

    @MutationMapping
    public UserSetting setAiSetting(@Argument String token, @Argument String settingName, @Argument String value) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        AiSettingName enumName = AiSettingName.fromString(settingName);
        if (enumName == AiSettingName.AI_PROVIDER) {
            try {
                AiProvider.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new InvalidArgumentException("Unknown AI provider: " + value);
            }
        }
        systemSettingsDao.setSetting(enumName, value);
        aiProviderFactory.clearCache();
        return new UserSetting(enumName.toString(), value);
    }

    // ================= AI Helper (Multi-Provider) GraphQL API =================
    @MutationMapping
    public Boolean testAiConnection(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        aiQueryBudgetTracker.consumeOrThrow();
        return aiService.testConnection();
    }

    @MutationMapping
    public CronInterpretation interpretCronExpression(@Argument String token, @Argument String naturalLanguageInput) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        aiQueryBudgetTracker.consumeOrThrow();
        String cronExpression = aiService.interpretCronExpression(naturalLanguageInput);
        if (cronExpression == null) {
            return null;
        }
        return new CronInterpretation(cronExpression, naturalLanguageInput);
    }

    @MutationMapping
    public CronExplanation explainCronExpression(@Argument String token, @Argument String cronExpression) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        aiQueryBudgetTracker.consumeOrThrow();
        String naturalLanguageExplanation = aiService.explainCronExpression(cronExpression);
        if (naturalLanguageExplanation == null) {
            return null;
        }
        return new CronExplanation(cronExpression, naturalLanguageExplanation);
    }

    @MutationMapping
    public SkipPatternInterpretation interpretSkipPattern(@Argument String token, @Argument String naturalLanguageInput) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        aiQueryBudgetTracker.consumeOrThrow();
        String skipPattern = aiService.interpretSkipPattern(naturalLanguageInput);
        if (skipPattern == null) {
            return null;
        }
        return new SkipPatternInterpretation(skipPattern, naturalLanguageInput);
    }

    @MutationMapping
    public SkipPatternExplanation explainSkipPattern(@Argument String token, @Argument String skipPattern) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        aiQueryBudgetTracker.consumeOrThrow();
        String naturalLanguageExplanation = aiService.explainSkipPattern(skipPattern);
        if (naturalLanguageExplanation == null) {
            return null;
        }
        return new SkipPatternExplanation(skipPattern, naturalLanguageExplanation);
    }

    @MutationMapping
    public AiSearchInterpretation interpretSearchQuery(
            @Argument String token,
            @Argument Map<String, Object> input) {
        checkSchemaReady();
        authContext.requireReader(token);
        aiQueryBudgetTracker.consumeOrThrow();
        String naturalLanguageQuery = (String) input.get("naturalLanguageQuery");
        
        // Get available keys - use provided keys or fetch from database
        @SuppressWarnings("unchecked")
        List<String> providedKeys = (List<String>) input.get("availableKeys");
        List<String> availableKeys = providedKeys != null && !providedKeys.isEmpty() 
            ? providedKeys
            : searchDAO.getAvailableItemAttributeKeys(null, null, null, null);
        
        return aiService.interpretSearchQuery(naturalLanguageQuery, availableKeys);
    }

    private void checkSchemaReady() {
        if (schema.isSchemaUpdateInProgress()) {
            throw new IllegalStateException("Schema update in progress. Please try again shortly.");
        }
    }
    @QueryMapping
    public SearchResult search(@Argument String token,
                            @Argument List<SearchConditionInput> searchConditions,
                            @Argument String name,
                            @Argument String folder,
                            @Argument String searchString,
                            @Argument Integer skip,
                            @Argument Integer howmany,
                            @Argument SortDirection sortDirection) {
        checkSchemaReady();
        authContext.requireReader(token);
        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;
        int skipVal = (skip == null) ? 0 : skip;
        int howmanyVal = (howmany == null) ? 100 : howmany;
        SortDirection direction = (sortDirection == null) ? SortDirection.DESC : sortDirection;

        // Get the search results
        List<Item> items = searchDAO.search(conds, name, folder, searchString, skipVal, howmanyVal, direction);

        // Get the total count
        int totalHits = searchDAO.countSearchResults(conds, name, folder, searchString);

        // Convert SearchConditionInput to SearchCondition for output
        List<SearchCondition> outputConditions = conds.stream()
            .map(c -> new SearchCondition(c.getName(), c.getOperation(), c.getValue()))
            .toList();

        // Build and return the result (facets are now fetched separately via getFacets query)
        return new SearchResult(outputConditions, name, folder, searchString, skipVal, howmanyVal, totalHits, direction, items);
    }

    @QueryMapping
    public List<Map<String, String>> itemAttributes(
            @Argument String token,
            @Argument String dataSourceName,
            @Argument DataSourceType dataSourceType,
            @Argument String name,
            @Argument String folder,
            @Argument List<String> keyNames) {
        checkSchemaReady();
        authContext.requireReader(token);
        // Find the datasource to get its ID
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(dataSourceName, dataSourceType)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + dataSourceName + " (" + dataSourceType + ")"));

        String datasourceId = dataSourceType.extractDatasourceId(dataSource);
        return searchDAO.getItemTreatmentResults(datasourceId, name, folder, keyNames);
    }

    @QueryMapping
    public About about() {
        checkSchemaReady();
        return new About(
            applicationName,
            applicationVersion,
            applicationDescription,
            profileHelper.getActiveProfile(),
            java.time.ZoneId.systemDefault().getId(),
            userSettingsDao.getSetting(UserSettingName.BANNER).orElse("")
        );
    }

    @QueryMapping
    public List<TreatmentInfo> availableTreatments() {
        checkSchemaReady();
        return java.util.Arrays.stream(Treatments.values())
            .map(TreatmentInfo::from)
            .toList();
    }

    @QueryMapping
    public List<String> itemAttributeKeys(@Argument String token) {
        checkSchemaReady();
        authContext.requireReader(token);
        return searchDAO.getAllItemAttributeKeys();
    }

    @QueryMapping
    public List<String> availableItemAttributeKeys(@Argument String token,
                                                     @Argument List<SearchConditionInput> searchConditions,
                                                     @Argument String name,
                                                     @Argument String folder,
                                                     @Argument String searchString) {
        checkSchemaReady();
        authContext.requireReader(token);
        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;
        return searchDAO.getAvailableItemAttributeKeys(conds, name, folder, searchString);
    }

    @QueryMapping
    public List<String> standardFields() {
        checkSchemaReady();
        return enterfind.server.model.FieldNameConstants.getStandardFieldsInPriorityOrder();
    }

    @QueryMapping
    public List<FacetValue> getFacets(@Argument String token,
                                       @Argument String key,
                                       @Argument List<SearchConditionInput> searchConditions,
                                       @Argument String name,
                                       @Argument String folder,
                                       @Argument String searchString) {
        checkSchemaReady();
        authContext.requireReader(token);
        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;
        return searchDAO.getFacetValues(key, conds, name, folder, searchString);
    }

    // DataSource Queries

    @QueryMapping
    public List<DataSourceInterface> dataSources(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return dataSourceDao.findAll();
    }

    @QueryMapping
    public DataSourceInterface dataSource(@Argument String token, @Argument String name, @Argument DataSourceType type) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return dataSourceDao.findByNameAndType(name, type).orElse(null);
    }

    @QueryMapping
    public DataSourceStats dataSourceStats(@Argument String token, @Argument String name, @Argument DataSourceType type) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(name, type)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + name + " (" + type + ")"));

        String dataSourceId = type.extractDatasourceId(dataSource);
        return dataSourceDao.getDataSourceStats(dataSourceId);
    }

    @SubscriptionMapping
    public Flux<DataSourceStats> dataSourceStatsUpdates(
            @Argument String token,
            @Argument String name,
            @Argument DataSourceType type,
            @Argument Integer intervalSeconds) {
        checkSchemaReady();
        // Use token-based auth for subscriptions
        authContext.requireAdmin(token);
        int interval = (intervalSeconds == null || intervalSeconds <= 0) ? 5 : intervalSeconds;

        // Verify that the data source exists before starting the subscription
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(name, type)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + name + " (" + type + ")"));

        String dataSourceId = type.extractDatasourceId(dataSource);

        // Return a Flux that emits stats at the specified interval
        return Flux.interval(Duration.ofSeconds(interval))
            .map(sequence -> dataSourceDao.getDataSourceStats(dataSourceId))
            .doOnCancel(() -> logger.info("Subscription cancelled for data source: {}", name));
    }

    @QueryMapping
    public TestDataSourceResult testDataSource(@Argument String token, @Argument String name, @Argument DataSourceType type) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(name, type)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + name + " (" + type + ")"));

        try {
            DataProviderInterface dataProvider = type.getDataProviderForTypeAndSource(dataSource);

            // Get items and folders at the root (null path means root)
            List<Item> items = dataProvider.getItems(null);
            List<Folder> folders = dataProvider.getFolders(null);

            // Populate dataSourceName and dataSourceType for GraphQL
            String typeString = type.name();
            for (Item item : items) {
                item.setDataSourceName(name);
                item.setDataSourceType(typeString);
            }
            for (Folder folder : folders) {
                folder.setDataSourceName(name);
                folder.setDataSourceType(typeString);
            }

            return TestDataSourceResult.success(items, folders);
        } catch (Exception e) {
            return TestDataSourceResult.failure(e.getMessage());
        }
    }

    // DataSource Mutations

    @MutationMapping
    public DataSourceInterface createLocalFileSystemDataSource(@Argument String token, @Argument CreateLocalFileSystemDataSourceInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        DataSourceInterface dataSource = DataSourceType.LOCAL_FILESYSTEM.buildFromCreateInput(input);
        return dataSourceDao.create(dataSource);
    }

    @MutationMapping
    public DataSourceInterface createRemoteFileSystemDataSource(@Argument String token, @Argument CreateRemoteFileSystemDataSourceInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        DataSourceInterface dataSource = DataSourceType.REMOTE_FILESYSTEM.buildFromCreateInput(input);
        return dataSourceDao.create(dataSource);
    }

    @MutationMapping
    public DataSourceInterface createS3BucketDataSource(@Argument String token, @Argument CreateS3BucketDataSourceInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        DataSourceInterface dataSource = DataSourceType.S3_BUCKET.buildFromCreateInput(input);
        return dataSourceDao.create(dataSource);
    }

    @MutationMapping
    public DataSourceInterface updateLocalFileSystemDataSource(@Argument String token, @Argument String name, @Argument UpdateLocalFileSystemDataSourceInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        return dataSourceDao.findByNameAndType(name, DataSourceType.LOCAL_FILESYSTEM)
            .map(existing -> {
                String datasourceId = DataSourceType.LOCAL_FILESYSTEM.extractDatasourceId(existing);
                DataSourceInterface updated = DataSourceType.LOCAL_FILESYSTEM.buildFromUpdateInput(existing, input);
                DataSourceInterface result = dataSourceDao.updateByNameAndType(name, DataSourceType.LOCAL_FILESYSTEM, updated).orElse(null);
                // Invalidate cached provider to ensure fresh credentials are used
                DataSourceType.invalidateProvider(datasourceId);
                return result;
            })
            .orElse(null);
    }

    @MutationMapping
    public DataSourceInterface updateRemoteFileSystemDataSource(@Argument String token, @Argument String name, @Argument UpdateRemoteFileSystemDataSourceInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        return dataSourceDao.findByNameAndType(name, DataSourceType.REMOTE_FILESYSTEM)
            .map(existing -> {
                String datasourceId = DataSourceType.REMOTE_FILESYSTEM.extractDatasourceId(existing);
                DataSourceInterface updated = DataSourceType.REMOTE_FILESYSTEM.buildFromUpdateInput(existing, input);
                DataSourceInterface result = dataSourceDao.updateByNameAndType(name, DataSourceType.REMOTE_FILESYSTEM, updated).orElse(null);
                // Invalidate cached provider to ensure fresh credentials are used
                DataSourceType.invalidateProvider(datasourceId);
                return result;
            })
            .orElse(null);
    }

    @MutationMapping
    public DataSourceInterface updateS3BucketDataSource(@Argument String token, @Argument String name, @Argument UpdateS3BucketDataSourceInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        return dataSourceDao.findByNameAndType(name, DataSourceType.S3_BUCKET)
            .map(existing -> {
                String datasourceId = DataSourceType.S3_BUCKET.extractDatasourceId(existing);
                DataSourceInterface updated = DataSourceType.S3_BUCKET.buildFromUpdateInput(existing, input);
                DataSourceInterface result = dataSourceDao.updateByNameAndType(name, DataSourceType.S3_BUCKET, updated).orElse(null);
                // Invalidate cached provider to ensure fresh credentials are used
                DataSourceType.invalidateProvider(datasourceId);
                return result;
            })
            .orElse(null);
    }

    @MutationMapping
    public Boolean deleteDataSource(@Argument String token, @Argument String name, @Argument DataSourceType type) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        // Get datasource ID before deleting so we can invalidate the cache
        dataSourceDao.findByNameAndType(name, type).ifPresent(existing -> {
            String datasourceId = type.extractDatasourceId(existing);
            DataSourceType.invalidateProvider(datasourceId);
        });
        return dataSourceDao.deleteByNameAndType(name, type);
    }

    @MutationMapping
    public Integer clearDataSourceData(@Argument String token, @Argument String name, @Argument DataSourceType type) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(name, type)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + name + " (" + type + ")"));

        String dataSourceId = type.extractDatasourceId(dataSource);
        return dataSourceDao.clearDataSourceData(dataSourceId);
    }

    @MutationMapping
    public DataSourceInterface scanDataSource(@Argument String token, @Argument String name, @Argument DataSourceType type) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(name, type)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + name + " (" + type + ")"));

        // Check if datasource is already scanning
        if (dataSource.getStatus() == DataSourceStatus.SCANNING) {
            throw new InvalidArgumentException("Data source is already scanning: " + name + " (" + type + ")");
        }

        // Trigger async scan - returns immediately while scan runs in background
        scanner.scanAsync(dataSource);

        // Return current state of data source (scan will update dateLastScanned when complete)
        return dataSource;
    }

    // Treatment Queries and Mutations

    @QueryMapping
    public List<DataSourceTreatment> dataSourceTreatments(@Argument String token, @Argument String dataSourceName, @Argument DataSourceType dataSourceType) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        // Find the datasource to get its ID
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(dataSourceName, dataSourceType)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + dataSourceName + " (" + dataSourceType + ")"));

        String dataSourceId = dataSourceType.extractDatasourceId(dataSource);
        return treatmentDao.getTreatmentsByDataSource(dataSourceId);
    }

    @MutationMapping
    public DataSourceTreatment addDataSourceTreatment(@Argument String token, @Argument AddDataSourceTreatmentInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        logger.info("[addDataSourceTreatment] Called with input: dataSourceName={}, dataSourceType={}, treatment={}, config={}",
                input.getDataSourceName(), input.getDataSourceType(), input.getTreatment(), input.getConfig());
        try {
            // Find the datasource to get its ID
            DataSourceInterface dataSource = dataSourceDao.findByNameAndType(input.getDataSourceName(), input.getDataSourceType())
                .orElseThrow(() -> new NotFoundException("Data source not found: " + input.getDataSourceName() + " (" + input.getDataSourceType() + ")"));

            String dataSourceId = input.getDataSourceType().extractDatasourceId(dataSource);

            // Auto-enable what we can supply; block on what only the operator can decide. Recognition
            // needs an embedding model we cannot choose or license for them, so enabling it without
            // one is rejected outright rather than allowed-and-stalled — an unclaimable row is never
            // created, so there is no stuck counter to explain.
            if (input.getTreatment() == Treatments.FACE_RECOGNITION && !faceEmbeddingModelService.isReady()) {
                throw new InvalidArgumentException(
                    "Face recognition requires an embedding model. Configure one in Settings first.");
            }

            JsonNode config = null;
            if (input.getConfig() != null && !input.getConfig().isEmpty()) {
                config = objectMapper.readTree(input.getConfig());
            }
            return treatmentDao.addTreatment(dataSourceId, input.getTreatment(), config);
        } catch (JacksonException e) {
            logger.error("[addDataSourceTreatment] Invalid JSON configuration: {}", input.getConfig(), e);
            throw new InvalidArgumentException("Invalid JSON configuration", e);
        } catch (Exception e) {
            logger.error("[addDataSourceTreatment] Error adding treatment: {} to datasourceName: {}: {}", input.getTreatment(), input.getDataSourceName(), e.getMessage(), e);
            throw e;
        }
    }

    @MutationMapping
    public Boolean removeDataSourceTreatment(@Argument String token, @Argument String dataSourceName, @Argument DataSourceType dataSourceType, @Argument Treatments treatment) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        // Find the datasource to get its ID
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(dataSourceName, dataSourceType)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + dataSourceName + " (" + dataSourceType + ")"));

        String dataSourceId = dataSourceType.extractDatasourceId(dataSource);
        return treatmentDao.removeTreatment(dataSourceId, treatment);
    }

    @MutationMapping
    public DataSourceTreatment updateDataSourceTreatmentConfig(@Argument String token, @Argument UpdateDataSourceTreatmentConfigInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);
        try {
            // Find the datasource to get its ID
            DataSourceInterface dataSource = dataSourceDao.findByNameAndType(input.getDataSourceName(), input.getDataSourceType())
                .orElseThrow(() -> new NotFoundException("Data source not found: " + input.getDataSourceName() + " (" + input.getDataSourceType() + ")"));

            String dataSourceId = input.getDataSourceType().extractDatasourceId(dataSource);

            JsonNode config = null;
            if (input.getConfig() != null && !input.getConfig().isEmpty()) {
                config = objectMapper.readTree(input.getConfig());
            }
            return treatmentDao.updateTreatmentConfig(dataSourceId, input.getTreatment(), config)
                .orElse(null);
        } catch (JacksonException e) {
            throw new InvalidArgumentException("Invalid JSON configuration", e);
        }
    }

    /**
     * Detected faces of an item paired with their identity labels. A lazy field resolver, so a
     * search that does not select it costs nothing; the item view selects it and gets the crop
     * index and its caption from the same row. Auth is checked at the parent query, as for the
     * other Item field resolvers.
     */
    @SchemaMapping(typeName = "Item", field = "faces")
    public List<ItemFace> getFaces(Item item) {
        // Items composed for a provider call rather than read from search carry no id.
        if (item.getId() == null) {
            return List.of();
        }
        return faceDao.getFacesByItemId(item.getId());
    }

    /**
     * Through MCP the link is absolute, so the model never has to know or guess the host, and when
     * the connector's header was exchanged for a session it also carries that session as {@code ?t=}:
     * the model holds no token to add. Through {@code /graphql} neither context value is set and the
     * link stays relative, which the web UI resolves against its own origin.
     */
    @SchemaMapping(typeName = "Item", field = "downloadLink")
    public String getDownloadLink(Item item,
            @ContextValue(name = McpGraphQlContext.SESSION_TOKEN, required = false) String token,
            @ContextValue(name = McpGraphQlContext.PUBLIC_BASE_URL, required = false) String baseUrl) {
        return withSessionToken(item.getDownloadLink(), token, baseUrl);
    }

    /** As {@link #getDownloadLink}. */
    @SchemaMapping(typeName = "Item", field = "previewLink")
    public String getPreviewLink(Item item,
            @ContextValue(name = McpGraphQlContext.SESSION_TOKEN, required = false) String token,
            @ContextValue(name = McpGraphQlContext.PUBLIC_BASE_URL, required = false) String baseUrl) {
        return withSessionToken(item.getPreviewLink(), token, baseUrl);
    }

    private static String withSessionToken(String relativeLink, String token, String baseUrl) {
        if (relativeLink == null || baseUrl == null) {
            return relativeLink;
        }
        String absolute = baseUrl + relativeLink;
        if (token == null) {
            return absolute;
        }
        // Item links never carry a query string of their own
        return absolute + "?t=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    @SchemaMapping(typeName = "Item", field = "rawBase64Content")
    public String getRawBase64Content(Item item) {
        // Note: rawBase64Content requires reader permission but we can't easily get the token here.
        // This is a field resolver, so auth should be checked at the parent query level.
        try {
            DataSourceType type = DataSourceType.valueOf(item.getDataSourceType());
            DataSourceInterface ds = dataSourceDao.findByNameAndType(item.getDataSourceName(), type)
                .orElseThrow(() -> new NotFoundException("Data source not found: " + item.getDataSourceName() + " (" + type + ")"));

            DataProviderInterface provider = type.getDataProviderForTypeAndSource(ds);

            // Create a copy of the item for the provider
            Item providerItem = new Item();
            providerItem.setName(item.getName());
            providerItem.setPath(item.getPath()); // path is already comma-separated in the database

            try (InputStream is = provider.getInputStream(providerItem);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                if (is == null) {
                    return null;
                }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                byte[] bytes = baos.toByteArray();
                return Base64.getEncoder().encodeToString(bytes);
            }
        } catch (Exception e) {
            logger.warn("Failed to read content for item {}/{}: {}", item.getDataSourceName(), item.getName(), e.getMessage());
            return null;
        }
    }

    // ================= Item Management GraphQL API =================

    @MutationMapping
    public Boolean deleteItem(@Argument String token,
                              @Argument String dataSourceName,
                              @Argument DataSourceType dataSourceType,
                              @Argument String name,
                              @Argument String folder) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        DataSourceInterface dataSource = dataSourceDao.findByNameAndType(dataSourceName, dataSourceType)
            .orElseThrow(() -> new NotFoundException("Data source not found: " + dataSourceName + " (" + dataSourceType + ")"));

        boolean deleted = itemStorageService.deleteItem(dataSource, dataSourceType, name, folder);

        logger.info("Deleted item: {}/{} from datasource: {}", folder, name, dataSourceName);
        return deleted;
    }

}
