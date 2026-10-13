/**
 * Get all user settings
 * @returns {Promise<Array>} - List of user settings
 */
export async function getUserSettings() {
    const query = `
        query GetUserSettings($token: String) {
            userSettings(token: $token) {
                settingName
                value
            }
        }
    `;
    const data = await graphqlRequest(query);
    return data.userSettings;
}

/**
 * Get all AI provider settings.
 * @returns {Promise<Array>} - List of AI settings
 */
export async function getAiSettings() {
    const query = `
        query GetAiSettings($token: String) {
            aiSettings(token: $token) {
                settingName
                value
            }
        }
    `;
    const data = await graphqlRequest(query);
    return data.aiSettings;
}

/**
 * Update a user setting
 * @param {string} settingName - Name of the setting
 * @param {string} value - New value
 * @returns {Promise<object>} - Updated user setting
 */
export async function setUserSetting(settingName, value) {
    const mutation = `
        mutation SetUserSetting($token: String, $settingName: String!, $value: String!) {
            setUserSetting(token: $token, settingName: $settingName, value: $value) {
                settingName
                value
            }
        }
    `;
    const data = await graphqlRequest(mutation, { settingName, value });
    return data.setUserSetting;
}

/**
 * Update an AI provider setting.
 * @param {string} settingName - Name of the AI setting
 * @param {string} value - New value
 * @returns {Promise<object>} - Updated AI setting
 */
export async function setAiSetting(settingName, value) {
    const mutation = `
        mutation SetAiSetting($token: String, $settingName: String!, $value: String!) {
            setAiSetting(token: $token, settingName: $settingName, value: $value) {
                settingName
                value
            }
        }
    `;
    const data = await graphqlRequest(mutation, { settingName, value });
    return data.setAiSetting;
}

/**
 * Get the current AI provider
 * @returns {Promise<string>} - AI provider enum value (NONE, OLLAMA, OPENAI, ANTHROPIC)
 */
export async function getAiProvider() {
    const query = `
        query GetAiProvider($token: String) {
            aiProvider(token: $token)
        }
    `;
    const data = await graphqlRequest(query);
    return data.aiProvider;
}


/**
 * Get the instance-global face embedding model status.
 * @returns {Promise<{mode, url, outputDim, enabledDatasourceCount, threshold, derivedThreshold}>}
 */
export async function getFaceEmbeddingModel() {
    const query = `
        query GetFaceEmbeddingModel($token: String) {
            faceEmbeddingModel(token: $token) {
                mode
                url
                outputDim
                enabledDatasourceCount
                threshold
                derivedThreshold
            }
        }
    `;
    const data = await graphqlRequest(query);
    return data.faceEmbeddingModel;
}

/**
 * Set the instance-global face embedding model. For CUSTOM, url is fetched and probed inline and a
 * model that fails is never stored; for BUILTIN, nonCommercialAck must be true. Throws (leaving the
 * previous selection in force) on any failure — the error message carries the probe's numbers.
 * @param {('NONE'|'BUILTIN'|'CUSTOM')} mode
 * @param {string} [url]
 * @param {boolean} [nonCommercialAck]
 * @returns {Promise<{mode, url, outputDim, enabledDatasourceCount, threshold, derivedThreshold}>}
 */
export async function setFaceEmbeddingModel(mode, url = null, nonCommercialAck = false) {
    const mutation = `
        mutation SetFaceEmbeddingModel($token: String, $mode: String!, $url: String, $nonCommercialAck: Boolean) {
            setFaceEmbeddingModel(token: $token, mode: $mode, url: $url, nonCommercialAck: $nonCommercialAck) {
                mode
                url
                outputDim
                enabledDatasourceCount
                threshold
                derivedThreshold
            }
        }
    `;
    const data = await graphqlRequest(mutation, { mode, url, nonCommercialAck });
    return data.setFaceEmbeddingModel;
}

/**
 * Set the grouping threshold and re-cluster every stored face at it. No re-embedding happens — the
 * vectors are untouched, only how they are clustered — but every group is rebuilt and human labels
 * are carried over best-effort, so this reshuffles identity across every enabled datasource.
 * Rejected while recognition is still processing.
 * @param {number} threshold
 * @returns {Promise<{mode, url, outputDim, enabledDatasourceCount, threshold, derivedThreshold}>}
 */
export async function setFaceGroupingThreshold(threshold) {
    const mutation = `
        mutation SetFaceGroupingThreshold($token: String, $threshold: Float!) {
            setFaceGroupingThreshold(token: $token, threshold: $threshold) {
                mode
                url
                outputDim
                enabledDatasourceCount
                threshold
                derivedThreshold
            }
        }
    `;
    const data = await graphqlRequest(mutation, { threshold });
    return data.setFaceGroupingThreshold;
}

/**
 * Rename a detected-face group label. Updates the group and every detectedface result carrying it.
 * @param {string} oldLabel
 * @param {string} newLabel
 * @returns {Promise<boolean>}
 */
export async function renameFaceLabel(oldLabel, newLabel) {
    const mutation = `
        mutation RenameFaceLabel($token: String, $oldLabel: String!, $newLabel: String!) {
            renameFaceLabel(token: $token, oldLabel: $oldLabel, newLabel: $newLabel)
        }
    `;
    const data = await graphqlRequest(mutation, { oldLabel, newLabel });
    return data.renameFaceLabel;
}

/**
 * Interpret a natural language search query using AI
 * @param {string} naturalLanguageQuery - The user's natural language query
 * @returns {Promise<object>} - Interpreted search parameters
 *   - success: boolean - whether interpretation succeeded
 *   - searchConditions: array of {name, operation, value} - same format as search() input
 *   - searchString: string - same as search() searchString param
 *   - explanation: string - interpretation or error explanation
 *   - confidence: number - 0.0 to 1.0 (only when success=true)
 */
export async function interpretSearchQuery(naturalLanguageQuery) {
    const mutation = `
        mutation InterpretSearchQuery($token: String, $input: AiSearchInterpretationInput!) {
            interpretSearchQuery(token: $token, input: $input) {
                naturalLanguageQuery
                success
                searchConditions {
                    name
                    operation
                    value
                }
                searchString
                explanation
                confidence
            }
        }
    `;
    const data = await graphqlRequest(mutation, { 
        input: { naturalLanguageQuery } 
    });
    return data.interpretSearchQuery;
}
/**
 * EnterFind API Client
 * Provides GraphQL API communication layer
 */

import { clearAuth, getAuthToken, handleAuthError, setAuth } from './auth.js';
import { buildWebSocketUrl, getApiUrl, getLoginUrl, getRemoteContext } from './remote.js';

/**
 * Execute a GraphQL query or mutation
 * Token is automatically injected into variables for authenticated operations.
 * @param {string} query - GraphQL query string
 * @param {object} variables - Variables for the query
 * @returns {Promise<object>} - Response data
 */
async function graphqlRequest(query, variables = {}) {
    try {
        // Inject token into variables if we have one and the query accepts it
        const token = getAuthToken();
        const enrichedVariables = { ...variables };
        if (token && !('token' in enrichedVariables)) {
            enrichedVariables.token = token;
        }

        const response = await fetch(getApiUrl(), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ query, variables: enrichedVariables }),
        });

        if (!response.ok) {
            // Handle 401 Unauthorized by redirecting to login
            if (response.status === 401) {
                clearAuth();
                window.location.href = getLoginUrl();
                throw new Error('Authentication required');
            }
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const result = await response.json();

        // Check for auth errors and redirect if needed
        if (handleAuthError(result)) {
            throw new Error('Authentication required');
        }

        if (result.errors) {
            console.error('GraphQL errors:', result.errors);
            throw new Error(result.errors[0]?.message || 'GraphQL error');
        }

        return result.data;
    } catch (error) {
        console.error('API request failed:', error);
        throw error;
    }
}

// Server timezone - fetched from about query and cached
let serverTimezone = null;

/**
 * Fetch server timezone from GraphQL API
 * @returns {Promise<string>} - Server timezone (e.g., "UTC", "America/New_York")
 */
export async function fetchServerTimezone() {
    if (serverTimezone !== null) return serverTimezone;
    
    try {
        const query = '{ about { timezone } }';
        const data = await graphqlRequest(query);
        serverTimezone = data?.about?.timezone || 'UTC';
        return serverTimezone;
    } catch {
        serverTimezone = 'UTC'; // Default to UTC if fetch fails
        return serverTimezone;
    }
}

/**
 * Get the cached server timezone (returns null if not yet fetched)
 * @returns {string|null} - Server timezone or null
 */
export function getServerTimezone() {
    return serverTimezone;
}

/**
 * Search for items
 * @param {object} params - Search parameters
 * @param {string} params.searchString - Free text search across all field values
 * @param {Array} params.searchConditions - KVP conditions (matches individual values in treatment results)
 * @param {string} params.name - Filter by item name (direct column match, substring)
 * @param {string} params.folder - Filter by folder path in forward-slash format (e.g., "level1/level2"), matches as substring
 * @param {number} params.skip - Number of results to skip
 * @param {number} params.howmany - Number of results to return
 * @param {string} params.sortDirection - Sort direction: 'ASC' or 'DESC' (default: DESC for newest first)
 * @param {boolean} params.includeFaces - Also fetch each item's detected faces (index + label).
 *        Off by default: it is a per-item lookup, and only the single-item view needs it — a result
 *        page of 20 would pay for it 20 times over for nothing.
 * @returns {Promise<object>} - Search results
 *
 * Note: The 'folder' parameter matches against the full path as a substring.
 * To match individual path components, use searchConditions with name='path'.
 */
export async function searchItems({ searchString, searchConditions = [], name, folder, skip = 0, howmany = 20, sortDirection = 'DESC', includeFaces = false }) {
    const facesField = includeFaces ? 'faces { faceIndex label }' : '';
    const query = `
        query Search($token: String, $searchConditions: [SearchConditionInput!], $name: String, $folder: String, $searchString: String, $skip: Int, $howmany: Int, $sortDirection: SortDirection) {
            search(token: $token, searchConditions: $searchConditions, name: $name, folder: $folder, searchString: $searchString, skip: $skip, howmany: $howmany, sortDirection: $sortDirection) {
                searchConditions {
                    name
                    operation
                    value
                }
                name
                folder
                searchString
                offset
                howmany
                totalHits
                sortDirection
                items {
                    name
                    folder
                    sizeBytes
                    mimeType
                    dataSourceName
                    dataSourceType
                    dateCreated
                    dateModified
                    ${facesField}
                }
            }
        }
    `;

    const data = await graphqlRequest(query, { searchConditions, name, folder, searchString, skip, howmany, sortDirection });
    return data.search;
}

/**
 * Get facet values for a specific key, optionally filtered by search criteria.
 * This is more efficient than fetching all facets with search results.
 * @param {string} key - The attribute key to get facet values for
 * @param {object} params - Optional search parameters to filter the facet values
 * @param {Array} params.searchConditions - KVP conditions
 * @param {string} params.name - Filter by item name
 * @param {string} params.folder - Filter by folder path
 * @param {string} params.searchString - Free text search
 * @returns {Promise<Array>} - List of facet values with counts
 */
export async function getFacets(key, { searchConditions = [], name, folder, searchString } = {}) {
    const query = `
        query GetFacets($token: String, $key: String!, $searchConditions: [SearchConditionInput!], $name: String, $folder: String, $searchString: String) {
            getFacets(token: $token, key: $key, searchConditions: $searchConditions, name: $name, folder: $folder, searchString: $searchString) {
                value
                count
            }
        }
    `;

    const data = await graphqlRequest(query, { key, searchConditions, name, folder, searchString });
    return data.getFacets;
}

/**
 * Get all item attribute keys for building search conditions
 * @returns {Promise<Array<string>>} - List of available keys
 */
export async function getItemAttributeKeys() {
    const query = `
        query GetItemAttributeKeys($token: String) {
            itemAttributeKeys(token: $token)
        }
    `;

    const data = await graphqlRequest(query);
    return data.itemAttributeKeys;
}

/**
 * Get available item attribute keys based on current search conditions.
 * Only returns keys that exist for items matching the search criteria.
 * @param {object} params - Optional search parameters to filter the available keys
 * @param {Array} params.searchConditions - KVP conditions
 * @param {string} params.name - Filter by item name
 * @param {string} params.folder - Filter by folder path
 * @param {string} params.searchString - Free text search
 * @returns {Promise<Array<string>>} - List of available keys for matching items
 */
export async function getAvailableItemAttributeKeys({ searchConditions = [], name, folder, searchString } = {}) {
    const query = `
        query GetAvailableItemAttributeKeys($token: String, $searchConditions: [SearchConditionInput!], $name: String, $folder: String, $searchString: String) {
            availableItemAttributeKeys(token: $token, searchConditions: $searchConditions, name: $name, folder: $folder, searchString: $searchString)
        }
    `;

    const data = await graphqlRequest(query, { searchConditions, name, folder, searchString });
    return data.availableItemAttributeKeys;
}

/**
 * Get standard fields in priority order.
 * These are the core fields that should be displayed prominently in the UI.
 * @returns {Promise<Array<string>>} - List of standard field names in priority order
 */
export async function getStandardFields() {
    const query = `
        query GetStandardFields {
            standardFields
        }
    `;

    const data = await graphqlRequest(query);
    return data.standardFields;
}

/**
 * Get all data sources
 * @returns {Promise<Array>} - List of data sources
 */
export async function getDataSources() {
    const query = `
        query GetDataSources($token: String) {
            dataSources(token: $token) {
                name
                cronPattern
                skipFiles
                dateCreated
                dateLastScanned
                status
                type
                ... on LocalFileSystemDataSource {
                    localPath
                }
                ... on RemoteFileSystemDataSource {
                    server
                    domain
                    username
                    shareName
                    path
                }
                ... on S3BucketDataSource {
                    nameOrUrl
                    accessKey
                    path
                    region
                }
            }
        }
    `;

    const data = await graphqlRequest(query);
    return data.dataSources;
}

/**
 * Get a single data source
 * @param {string} name - Data source name
 * @param {string} type - Data source type
 * @returns {Promise<object>} - Data source details
 */
export async function getDataSource(name, type) {
    const query = `
        query GetDataSource($token: String, $name: String!, $type: DataSourceType!) {
            dataSource(token: $token, name: $name, type: $type) {
                name
                cronPattern
                skipFiles
                dateCreated
                dateLastScanned
                status
                type
                ... on LocalFileSystemDataSource {
                    localPath
                }
                ... on RemoteFileSystemDataSource {
                    server
                    domain
                    username
                    shareName
                    path
                }
                ... on S3BucketDataSource {
                    nameOrUrl
                    accessKey
                    path
                    region
                }
            }
        }
    `;

    const data = await graphqlRequest(query, { name, type });
    return data.dataSource;
}

/**
 * Create a Local File System data source
 * @param {object} input - Data source input
 * @returns {Promise<object>} - Created data source
 */
export async function createLocalFileSystemDataSource(input) {
    const mutation = `
        mutation CreateLocalDataSource($token: String, $input: CreateLocalFileSystemDataSourceInput!) {
            createLocalFileSystemDataSource(token: $token, input: $input) {
                name
                cronPattern
                skipFiles
                dateCreated
                localPath
                type
            }
        }
    `;

    const data = await graphqlRequest(mutation, { input });
    return data.createLocalFileSystemDataSource;
}

/**
 * Create a Remote File System data source
 * @param {object} input - Data source input
 * @returns {Promise<object>} - Created data source
 */
export async function createRemoteFileSystemDataSource(input) {
    const mutation = `
        mutation CreateRemoteDataSource($token: String, $input: CreateRemoteFileSystemDataSourceInput!) {
            createRemoteFileSystemDataSource(token: $token, input: $input) {
                name
                cronPattern
                skipFiles
                dateCreated
                server
                domain
                username
                shareName
                path
                type
            }
        }
    `;

    const data = await graphqlRequest(mutation, { input });
    return data.createRemoteFileSystemDataSource;
}

/**
 * Create an S3 Bucket data source
 * @param {object} input - Data source input
 * @returns {Promise<object>} - Created data source
 */
export async function createS3BucketDataSource(input) {
    const mutation = `
        mutation CreateS3DataSource($token: String, $input: CreateS3BucketDataSourceInput!) {
            createS3BucketDataSource(token: $token, input: $input) {
                name
                cronPattern
                skipFiles
                dateCreated
                nameOrUrl
                accessKey
                path
                region
                type
            }
        }
    `;

    const data = await graphqlRequest(mutation, { input });
    return data.createS3BucketDataSource;
}

/**
 * Update a Local File System data source
 * @param {string} name - Data source name
 * @param {object} input - Update input
 * @returns {Promise<object>} - Updated data source
 */
export async function updateLocalFileSystemDataSource(name, input) {
    const mutation = `
        mutation UpdateLocalDataSource($token: String, $name: String!, $input: UpdateLocalFileSystemDataSourceInput!) {
            updateLocalFileSystemDataSource(token: $token, name: $name, input: $input) {
                name
                cronPattern
                skipFiles
                dateCreated
                localPath
                type
            }
        }
    `;

    const data = await graphqlRequest(mutation, { name, input });
    return data.updateLocalFileSystemDataSource;
}

/**
 * Update a Remote File System data source
 * @param {string} name - Data source name
 * @param {object} input - Update input
 * @returns {Promise<object>} - Updated data source
 */
export async function updateRemoteFileSystemDataSource(name, input) {
    const mutation = `
        mutation UpdateRemoteDataSource($token: String, $name: String!, $input: UpdateRemoteFileSystemDataSourceInput!) {
            updateRemoteFileSystemDataSource(token: $token, name: $name, input: $input) {
                name
                cronPattern
                skipFiles
                dateCreated
                server
                domain
                username
                shareName
                path
                type
            }
        }
    `;

    const data = await graphqlRequest(mutation, { name, input });
    return data.updateRemoteFileSystemDataSource;
}

/**
 * Update an S3 Bucket data source
 * @param {string} name - Data source name
 * @param {object} input - Update input
 * @returns {Promise<object>} - Updated data source
 */
export async function updateS3BucketDataSource(name, input) {
    const mutation = `
        mutation UpdateS3DataSource($token: String, $name: String!, $input: UpdateS3BucketDataSourceInput!) {
            updateS3BucketDataSource(token: $token, name: $name, input: $input) {
                name
                cronPattern
                skipFiles
                dateCreated
                nameOrUrl
                accessKey
                path
                region
                type
            }
        }
    `;

    const data = await graphqlRequest(mutation, { name, input });
    return data.updateS3BucketDataSource;
}

/**
 * Delete a data source
 * @param {string} name - Data source name
 * @param {string} type - Data source type
 * @returns {Promise<boolean>} - Success status
 */
export async function deleteDataSource(name, type) {
    const mutation = `
        mutation DeleteDataSource($token: String, $name: String!, $type: DataSourceType!) {
            deleteDataSource(token: $token, name: $name, type: $type)
        }
    `;

    const data = await graphqlRequest(mutation, { name, type });
    return data.deleteDataSource;
}

/**
 * Delete an item from a data source (removes from DB and underlying storage)
 * @param {string} dataSourceName - Data source name
 * @param {string} dataSourceType - Data source type
 * @param {string} name - Item name
 * @param {string|null} folder - Item folder path
 * @returns {Promise<boolean>} - True if deleted
 */
export async function deleteItem(dataSourceName, dataSourceType, name, folder) {
    const mutation = `
        mutation DeleteItem($token: String, $dataSourceName: String!, $dataSourceType: DataSourceType!, $name: String!, $folder: String) {
            deleteItem(token: $token, dataSourceName: $dataSourceName, dataSourceType: $dataSourceType, name: $name, folder: $folder)
        }
    `;

    const data = await graphqlRequest(mutation, { dataSourceName, dataSourceType: dataSourceType, name, folder: folder || null });
    return data.deleteItem;
}

/**
 * Clear all data for a data source (items, folders, treatment results) while keeping the definition
 * @param {string} name - Data source name
 * @param {string} type - Data source type
 * @returns {Promise<number>} - Number of items deleted
 */
export async function clearDataSourceData(name, type) {
    const mutation = `
        mutation ClearDataSourceData($token: String, $name: String!, $type: DataSourceType!) {
            clearDataSourceData(token: $token, name: $name, type: $type)
        }
    `;

    const data = await graphqlRequest(mutation, { name, type });
    return data.clearDataSourceData;
}

/**
 * Scan a data source
 * @param {string} name - Data source name
 * @param {string} type - Data source type
 * @returns {Promise<object>} - Data source with updated scan date
 */
export async function scanDataSource(name, type) {
    const mutation = `
        mutation ScanDataSource($token: String, $name: String!, $type: DataSourceType!) {
            scanDataSource(token: $token, name: $name, type: $type) {
                name
                dateLastScanned
                type
            }
        }
    `;

    const data = await graphqlRequest(mutation, { name, type });
    return data.scanDataSource;
}

/**
 * Get available treatment types
 * @returns {Promise<Array>} - List of treatment info (name, label, description)
 */
export async function getAvailableTreatments() {
    const query = `
        query GetAvailableTreatments {
            availableTreatments {
                name
                label
                description
            }
        }
    `;

    const data = await graphqlRequest(query);
    return data.availableTreatments;
}

/**
 * Get data source treatments
 * @param {string} dataSourceName - Data source name
 * @param {string} dataSourceType - Data source type
 * @returns {Promise<Array>} - List of treatments
 */
export async function getDataSourceTreatments(dataSourceName, dataSourceType) {
    const query = `
        query GetTreatments($token: String, $dataSourceName: String!, $dataSourceType: DataSourceType!) {
            dataSourceTreatments(token: $token, dataSourceName: $dataSourceName, dataSourceType: $dataSourceType) {
                treatment
                config
                dateCreated
            }
        }
    `;

    const data = await graphqlRequest(query, { dataSourceName, dataSourceType });
    return data.dataSourceTreatments;
}

/**
 * Add a treatment to a data source
 * @param {string} dataSourceName - Data source name
 * @param {string} dataSourceType - Data source type
 * @param {string} treatment - Treatment type
 * @param {string} config - Optional config
 * @returns {Promise<object>} - Created treatment
 */
export async function addDataSourceTreatment(dataSourceName, dataSourceType, treatment, config = null) {
    const query = `
        mutation AddTreatment($token: String, $input: AddDataSourceTreatmentInput!) {
            addDataSourceTreatment(token: $token, input: $input) {
                treatment
                config
                dateCreated
            }
        }
    `;

    const data = await graphqlRequest(query, {
        input: { dataSourceName, dataSourceType, treatment, config }
    });
    return data.addDataSourceTreatment;
}

/**
 * Remove a treatment from a data source
 * @param {string} dataSourceName - Data source name
 * @param {string} dataSourceType - Data source type
 * @param {string} treatment - Treatment type
 * @returns {Promise<boolean>} - Success status
 */
export async function removeDataSourceTreatment(dataSourceName, dataSourceType, treatment) {
    const query = `
        mutation RemoveTreatment($token: String, $dataSourceName: String!, $dataSourceType: DataSourceType!, $treatment: TreatmentType!) {
            removeDataSourceTreatment(token: $token, dataSourceName: $dataSourceName, dataSourceType: $dataSourceType, treatment: $treatment)
        }
    `;

    const data = await graphqlRequest(query, { dataSourceName, dataSourceType, treatment });
    return data.removeDataSourceTreatment;
}

/**
 * Get item attributes (treatment results) for an item
 * @param {string} dataSourceName - Data source name
 * @param {string} dataSourceType - Data source type
 * @param {string} name - Item name
 * @param {string} folder - Item folder (optional)
 * @returns {Promise<Array>} - List of key-value pairs
 */
export async function getItemAttributes(dataSourceName, dataSourceType, name, folder = null) {
    const query = `
        query GetItemAttributes($token: String, $dataSourceName: String!, $dataSourceType: DataSourceType!, $name: String!, $folder: String) {
            itemAttributes(token: $token, dataSourceName: $dataSourceName, dataSourceType: $dataSourceType, name: $name, folder: $folder) {
                key
                value
            }
        }
    `;

    const data = await graphqlRequest(query, { dataSourceName, dataSourceType, name, folder });
    return data.itemAttributes;
}

/**
 * Get data source statistics
 * @param {string} name - Data source name
 * @param {string} type - Data source type
 * @returns {Promise<object>} - Data source stats
 */
export async function getDataSourceStats(name, type) {
    const query = `
        query GetDataSourceStats($token: String, $name: String!, $type: DataSourceType!) {
            dataSourceStats(token: $token, name: $name, type: $type) {
                totalItems
                previewsToDo
                foldersWithProblems
                treatmentStatusCounts {
                    status
                    count
                }
            }
        }
    `;

    const data = await graphqlRequest(query, { name, type });
    return data.dataSourceStats;
}

/**
 * Subscribe to data source statistics updates via GraphQL WebSocket subscription
 * Uses the graphql-transport-ws protocol (Spring GraphQL default)
 * Token is passed as a subscription variable (not in connection_init).
 * @param {string} name - Data source name
 * @param {string} type - Data source type
 * @param {number} intervalSeconds - Update interval in seconds (default: 2)
 * @param {function} onUpdate - Callback function called with each stats update
 * @param {function} onError - Callback function called on error
 * @returns {function} - Unsubscribe function to stop subscription
 */
const WS_RECONNECT_DELAYS_MS = [1000, 5000, 10000, 30000, 60000];

/**
 * Creates a self-reconnecting graphql-transport-ws subscription.
 *
 * @param {string} logPrefix - prefix for all console messages, e.g. '[dataSourceStats]'
 * @param {function} buildSubscribeMsg - called each (re)connect to produce the subscribe message payload
 * @param {function} handleNext - called with the `data` object from each 'next' message
 * @param {function} [onError] - called on unrecoverable GraphQL protocol errors
 * @returns {function} unsubscribe - call to permanently stop the subscription
 */
function createWsSubscription(logPrefix, buildSubscribeMsg, handleNext, onError) {
    const wsUrl = buildWebSocketUrl();
    const remoteCtx = getRemoteContext();

    let ws = null;
    let stopped = false;
    let reconnectAttempt = 0;
    let reconnectTimer = null;

    function sendMessage(message) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(message));
        }
    }

    function connect() {
        if (stopped) return;
        try {
            console.log(`${logPrefix} Opening WS to ${wsUrl}`);
            // When remote, the raw WS connects to the tunnel gateway (not a graphql endpoint),
            // so don't negotiate graphql-transport-ws at this layer — it's handled server-side
            // by WsSessionManager which adds the subprotocol header to the local WS connection.
            let socket = remoteCtx
                ? new WebSocket(wsUrl)
                : new WebSocket(wsUrl, ['graphql-transport-ws']);
            if (remoteCtx) {
                socket = createRemoteWsAdapter(socket, remoteCtx.serverId, '/graphql');
            }
            ws = socket;

            ws.onopen = () => {
                console.log(`${logPrefix} WS opened, sending connection_init`);
                sendMessage({ type: 'connection_init', payload: {} });
            };

            ws.onmessage = (event) => {
                try {
                    const message = JSON.parse(event.data);
                    switch (message.type) {
                        case 'connection_ack':
                            console.log(`${logPrefix} connection_ack, sending subscription`);
                            reconnectAttempt = 0;
                            sendMessage(buildSubscribeMsg());
                            break;
                        case 'next':
                            if (message.payload && message.payload.data) {
                                handleNext(message.payload.data);
                            }
                            break;
                        case 'complete':
                            console.log(`${logPrefix} subscription completed by server`);
                            break;
                        case 'error':
                            console.error(`${logPrefix} GraphQL error:`, message.payload);
                            if (onError) onError(new Error(JSON.stringify(message.payload)));
                            break;
                        default:
                            break;
                    }
                } catch (e) {
                    console.error(`${logPrefix} failed to handle message:`, e);
                }
            };

            ws.onerror = (event) => {
                console.error(`${logPrefix} WS error`, event);
            };

            ws.onclose = (event) => {
                console.log(`${logPrefix} WS closed: ${event.code} ${event.reason}`);
                ws = null;
                if (!stopped) scheduleReconnect();
            };

        } catch (e) {
            console.error(`${logPrefix} failed to open WS:`, e);
            if (!stopped) scheduleReconnect();
        }
    }

    function scheduleReconnect() {
        const delayIndex = Math.min(reconnectAttempt, WS_RECONNECT_DELAYS_MS.length - 1);
        const delay = WS_RECONNECT_DELAYS_MS[delayIndex];
        reconnectAttempt++;
        console.log(`${logPrefix} reconnecting in ${delay}ms (attempt ${reconnectAttempt})`);
        reconnectTimer = setTimeout(connect, delay);
    }

    connect();

    return () => {
        console.log(`${logPrefix} unsubscribing`);
        stopped = true;
        if (reconnectTimer !== null) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        if (ws) {
            try {
                if (ws.readyState === WebSocket.OPEN) {
                    sendMessage({ id: '1', type: 'complete' });
                }
                ws.close();
            } catch (e) { /* ignore */ }
            ws = null;
        }
    };
}

export function subscribeToDataSourceStats(name, type, intervalSeconds = 2, onUpdate, onError) {
    const token = getAuthToken();
    const query = `
        subscription DataSourceStatsUpdates($token: String, $name: String!, $type: DataSourceType!, $intervalSeconds: Int) {
            dataSourceStatsUpdates(token: $token, name: $name, type: $type, intervalSeconds: $intervalSeconds) {
                totalItems
                previewsToDo
                foldersWithProblems
                treatmentStatusCounts {
                    status
                    count
                }
                dataSourceStatus
                dateLastScanned
            }
        }
    `;
    return createWsSubscription(
        '[dataSourceStats]',
        () => ({ id: '1', type: 'subscribe', payload: { query, variables: { token, name, type, intervalSeconds }, operationName: 'DataSourceStatsUpdates' } }),
        (data) => { if (onUpdate) onUpdate(data.dataSourceStatsUpdates); },
        onError
    );
}

/**
 * Get about information
 * @returns {Promise<object>} - About info
 */
export async function getAbout() {
    const query = `
        query About {
            about {
                name
                version
                description
                environment
                banner
            }
        }
    `;

    const data = await graphqlRequest(query);
    return data.about;
}

/**
 * Test a data source connection and list files at root
 * @param {string} name - Data source name
 * @param {string} type - Data source type
 * @returns {Promise<object>} - Test result with success, message, files and folders
 */
export async function testDataSource(name, type) {
    const query = `
        query TestDataSource($token: String, $name: String!, $type: DataSourceType!) {
            testDataSource(token: $token, name: $name, type: $type) {
                success
                message
                files {
                    name
                    folder
                    sizeBytes
                    dataSourceName
                    dataSourceType
                    dateCreated
                    dateModified
                }
                folders {
                    name
                    folder
                    dataSourceName
                    dataSourceType
                }
            }
        }
    `;

    const data = await graphqlRequest(query, { name, type });
    return data.testDataSource;
}

/**
 * Check if any AI provider is configured (Ollama, OpenAI, or Anthropic)
 * @returns {Promise<boolean>} - True if any AI provider is properly configured
 */
export async function isAiConfigured() {
    try {
        const provider = await getAiProvider();
        return provider && provider !== 'NONE';
    } catch (error) {
        console.error('Failed to check AI configuration:', error);
        return false;
    }
}

/**
 * Check if Ollama is configured (legacy, use isAiConfigured instead)
 * @returns {Promise<boolean>} - True if Ollama API URL and Model Name are configured
 * @deprecated Use isAiConfigured() instead
 */
export async function isOllamaConfigured() {
    try {
        const settings = await getUserSettings();
        const ollamaUrl = settings.find(s => s.settingName === 'OllamaApiUrl');
        const ollamaModel = settings.find(s => s.settingName === 'OllamaModelName');

        return !!(ollamaUrl?.value && ollamaModel?.value);
    } catch (error) {
        console.error('Failed to check Ollama configuration:', error);
        return false;
    }
}

/**
 * Test Ollama connection
 * @returns {Promise<boolean>} - True if connection is successful
 */
export async function testAiConnection() {
    const mutation = `
        mutation TestAiConnection($token: String) {
            testAiConnection(token: $token)
        }
    `;

    const data = await graphqlRequest(mutation);
    return data.testAiConnection;
}

/**
 * Interpret natural language into a cron expression
 * @param {string} naturalLanguageInput - Natural language description (e.g., "every 2 hours")
 * @returns {Promise<object>} - Object with cronExpression and naturalLanguageInput
 */
export async function interpretCronExpression(naturalLanguageInput) {
    const mutation = `
        mutation InterpretCronExpression($token: String, $naturalLanguageInput: String!) {
            interpretCronExpression(token: $token, naturalLanguageInput: $naturalLanguageInput) {
                cronExpression
                naturalLanguageInput
            }
        }
    `;

    const data = await graphqlRequest(mutation, { naturalLanguageInput });
    return data.interpretCronExpression;
}

/**
 * Explain a cron expression in natural language
 * @param {string} cronExpression - Cron expression (e.g., "0 *&#47;2 * * *")
 * @returns {Promise<object>} - Object with cronExpression and naturalLanguageExplanation
 */
export async function explainCronExpression(cronExpression) {
    const mutation = `
        mutation ExplainCronExpression($token: String, $cronExpression: String!) {
            explainCronExpression(token: $token, cronExpression: $cronExpression) {
                cronExpression
                naturalLanguageExplanation
            }
        }
    `;

    const data = await graphqlRequest(mutation, { cronExpression });
    return data.explainCronExpression;
}

/**
 * Interpret natural language into a skip pattern regex
 * @param {string} naturalLanguageInput - Natural language description (e.g., "all jpg and png files")
 * @returns {Promise<object>} - Object with skipPattern and naturalLanguageInput
 */
export async function interpretSkipPattern(naturalLanguageInput) {
    const mutation = `
        mutation InterpretSkipPattern($token: String, $naturalLanguageInput: String!) {
            interpretSkipPattern(token: $token, naturalLanguageInput: $naturalLanguageInput) {
                skipPattern
                naturalLanguageInput
            }
        }
    `;

    const data = await graphqlRequest(mutation, { naturalLanguageInput });
    return data.interpretSkipPattern;
}

/**
 * Explain a skip pattern regex in natural language
 * @param {string} skipPattern - Skip pattern regex (e.g., ".*\\.jpg|.*\\.png")
 * @returns {Promise<object>} - Object with skipPattern and naturalLanguageExplanation
 */
export async function explainSkipPattern(skipPattern) {
    const mutation = `
        mutation ExplainSkipPattern($token: String, $skipPattern: String!) {
            explainSkipPattern(token: $token, skipPattern: $skipPattern) {
                skipPattern
                naturalLanguageExplanation
            }
        }
    `;

    const data = await graphqlRequest(mutation, { skipPattern });
    return data.explainSkipPattern;
}

// ================= Saved Query Functions =================

/**
 * Get all saved queries for the current user
 * @returns {Promise<Array>} - List of saved queries
 */
export async function getSavedQueries() {
    const query = `
        query GetSavedQueries($token: String) {
            savedQueries(token: $token) {
                id
                userId
                name
                searchString
                queryConditions {
                    name
                    operation
                    value
                }
                sortDirection
                dateCreated
            }
        }
    `;
    const data = await graphqlRequest(query);
    return data.savedQueries;
}

/**
 * Create a new saved query
 * @param {string} searchString - Text search string (optional)
 * @param {Array} queryConditions - Array of {name, operation, value} search conditions
 * @param {string} sortDirection - Sort direction (ASC or DESC)
 * @returns {Promise<object>} - Created saved query
 */
export async function createSavedQuery(searchString, queryConditions, sortDirection) {
    const mutation = `
        mutation CreateSavedQuery($token: String, $input: CreateSavedQueryInput!) {
            createSavedQuery(token: $token, input: $input) {
                id
                userId
                name
                searchString
                queryConditions {
                    name
                    operation
                    value
                }
                sortDirection
                dateCreated
            }
        }
    `;
    const data = await graphqlRequest(mutation, { 
        input: { searchString, queryConditions: queryConditions || [], sortDirection } 
    });
    return data.createSavedQuery;
}

/**
 * Delete a saved query
 * @param {string} id - ID of the saved query to delete
 * @returns {Promise<boolean>} - True if deleted successfully
 */
export async function deleteSavedQuery(id) {
    const mutation = `
        mutation DeleteSavedQuery($token: String, $id: String!) {
            deleteSavedQuery(token: $token, id: $id)
        }
    `;
    const data = await graphqlRequest(mutation, { id });
    return data.deleteSavedQuery;
}

// ================= Remote Access =================

export function createRemoteWsAdapter(rawWs, serverId, wsPath) {
    let wsSessionId = null;
    let ready = false;
    const buffer = [];
    let onopenHandler = null;

    const adapter = {
        set onopen(fn) { onopenHandler = fn; },
        get onopen() { return onopenHandler; },
        onmessage: null,
        onerror: null,
        onclose: null,
        get readyState() { return rawWs.readyState; },
        send(data) {
            if (!ready) {
                buffer.push(data);
            } else {
                rawWs.send(JSON.stringify({ type: 'WS_MESSAGE', wsSessionId, data }));
            }
        },
        close() { rawWs.close(); }
    };

    rawWs.onopen = () => {
        rawWs.send(JSON.stringify({ type: 'WS_CONNECT_INIT', serverId, wsPath }));
    };

    rawWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'WS_SESSION_READY') {
            wsSessionId = msg.wsSessionId;
            ready = true;
            if (onopenHandler) onopenHandler();
            for (const data of buffer) {
                rawWs.send(JSON.stringify({ type: 'WS_MESSAGE', wsSessionId, data }));
            }
            buffer.length = 0;
        } else if (msg.type === 'WS_MESSAGE') {
            if (adapter.onmessage) adapter.onmessage({ data: msg.data });
        } else if (msg.type === 'WS_SERVER_CLOSE') {
            // Server-side WS session closed; close the tunnel WS so the reconnect logic fires
            rawWs.close();
        } else {
            console.warn('Unknown message type from remote WS:', msg.type);
        }
    };

    rawWs.onerror = (event) => { if (adapter.onerror) adapter.onerror(event); };
    rawWs.onclose = (event) => { if (adapter.onclose) adapter.onclose(event); };

    return adapter;
}

export async function getRemoteAccessConfig() {
    const query = `
        query GetRemoteAccessConfig($token: String) {
            remoteAccessConfig(token: $token) {
                serverId
                enabled
                lastConnected
                isConnected
                statusMessage
            }
        }
    `;
    const data = await graphqlRequest(query);
    return data.remoteAccessConfig;
}

export async function setRemoteAccessEnabled(enabled, inviteCode) {
    const mutation = `
        mutation SetRemoteAccessEnabled($token: String, $enabled: Boolean!, $inviteCode: String) {
            setRemoteAccessEnabled(token: $token, enabled: $enabled, inviteCode: $inviteCode) {
                serverId
                enabled
                lastConnected
                isConnected
                statusMessage
            }
        }
    `;
    const variables = { enabled };
    if (inviteCode) variables.inviteCode = inviteCode;
    const data = await graphqlRequest(mutation, variables);
    return data.setRemoteAccessEnabled;
}

export async function reenrollRemoteAccess() {
    const mutation = `
        mutation ReenrollRemoteAccess($token: String) {
            reenrollRemoteAccess(token: $token) {
                serverId
                enabled
                lastConnected
                isConnected
                statusMessage
            }
        }
    `;
    const data = await graphqlRequest(mutation);
    return data.reenrollRemoteAccess;
}

export function subscribeToRemoteAccessConfig(onUpdate, onError) {
    const token = getAuthToken();
    const query = `
        subscription RemoteAccessConfigUpdates($token: String) {
            remoteAccessConfigUpdates(token: $token) {
                serverId
                enabled
                lastConnected
                isConnected
                statusMessage
            }
        }
    `;
    return createWsSubscription(
        '[remoteAccess]',
        () => ({ id: '1', type: 'subscribe', payload: { query, variables: { token }, operationName: 'RemoteAccessConfigUpdates' } }),
        (data) => {
            const config = data.remoteAccessConfigUpdates;
            console.log('[remoteAccess] Config update received:', config);
            if (onUpdate) onUpdate(config);
        },
        onError
    );
}

export async function getRemoteUsage() {
    const query = `
        query GetRemoteUsage($token: String!) {
            remoteUsage(token: $token) {
                serverId
                granularity
                period
                ingressBytes
                egressBytes
            }
        }
    `;
    const data = await graphqlRequest(query);
    return data.remoteUsage;
}

export async function getRemoteQuota() {
    const query = `
        query GetRemoteQuota($token: String!) {
            remoteQuota(token: $token) {
                serverId
                userId
                monthlyQuotaPercentUsed
            }
        }
    `;
    const data = await graphqlRequest(query);
    return data.remoteQuota;
}

// ================= API Key / API User Functions =================

const API_USER_FIELDS = `
    id
    nickname
    keyValuePreview
    userLevel
    createdByUserId
    dateCreated
    dateExpires
    dateLastUsed
    isActive
    isExpired
`;

export async function getApiUsers() {
    const query = `
        query GetApiUsers($token: String) {
            apiUsers(token: $token) { ${API_USER_FIELDS} }
        }
    `;
    const data = await graphqlRequest(query);
    return data.apiUsers || [];
}

export async function getApiUser(id) {
    const query = `
        query GetApiUser($token: String, $id: String!) {
            apiUser(token: $token, id: $id) { ${API_USER_FIELDS} }
        }
    `;
    const data = await graphqlRequest(query, { id });
    return data.apiUser;
}

export async function createApiUser(nickname, userLevel, expirationDays) {
    const mutation = `
        mutation CreateApiUser($token: String, $input: CreateApiUserInput!) {
            createApiUser(token: $token, input: $input) {
                apiUser { ${API_USER_FIELDS} }
                keyValue
            }
        }
    `;
    const data = await graphqlRequest(mutation, { input: { nickname, userLevel, expirationDays } });
    return data.createApiUser;
}

export async function deleteApiUser(id) {
    const mutation = `
        mutation DeleteApiUser($token: String, $id: String!) {
            deleteApiUser(token: $token, id: $id)
        }
    `;
    const data = await graphqlRequest(mutation, { id });
    return data.deleteApiUser;
}

export async function loginWithApiKey(nickname, keyValue) {
    const mutation = `
        mutation LoginWithApiKey($input: LoginWithApiKeyInput!) {
            loginWithApiKey(input: $input) {
                token
                user { id nickname userLevel isActive }
                expiresAt
            }
        }
    `;
    const data = await graphqlRequest(mutation, { input: { nickname, keyValue } });
    const authResult = data.loginWithApiKey;
    setAuth(authResult.token, authResult.user, authResult.expiresAt);
    return authResult;
}
