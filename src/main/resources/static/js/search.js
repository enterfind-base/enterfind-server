/**
 * EnterFind Search Page Logic
 */

import { $, escapeHtml, formatDate, buildDownloadUrl, buildPreviewUrl, showAlert, showConfirm } from './util.js';
import { searchItems, getAvailableItemAttributeKeys, getFacets, getAiProvider, interpretSearchQuery, getSavedQueries, createSavedQuery, deleteSavedQuery, getStandardFields } from './api.js';
import { isLoggedIn } from './auth.js';

// Field name constants for functional logic (filtering, path clicking)
const FIELD_NAME = 'name';
const FIELD_PATH = 'path';

// State management
const state = {
    searchString: '',
    conditions: [],
    currentPage: 0,
    pageSize: 20,
    totalHits: 0,
    facetValues: [], // Values for the currently selected facet
    selectedFacet: FIELD_PATH, // Default to path facet
    availableKeys: [], // Available keys for condition field dropdown and facet dropdown
    standardFields: [], // Standard fields in priority order from server
    sortDirection: 'DESC', // Default to newest first
    showPreviews: false, // Show preview thumbnails
    tomSelectInstance: null, // Tom Select instance for facet dropdown
    conditionFieldTomSelect: null, // Tom Select instance for condition field dropdown
    pendingAiSearch: null, // Store AI interpretation result before applying
    currentFacetKeys: [] // Track current facet dropdown keys to detect changes
};

// Initialize the page
document.addEventListener('DOMContentLoaded', async () => {
    attachEventListeners();
    await loadStandardFields();
    loadFromUrl();
    initAiAssist();
});

/**
 * Load standard fields from the server
 */
async function loadStandardFields() {
    try {
        state.standardFields = await getStandardFields();
        // Normalize to lowercase for comparison
        state.standardFields = state.standardFields.map(f => f.toLowerCase());
    } catch (error) {
        console.error('Failed to load standard fields:', error);
        // Minimal fallback if server call fails
        state.standardFields = [];
    }
}

/**
 * Attach event listeners
 */
function attachEventListeners() {
    // Search
    $('searchBtn').addEventListener('click', performSearch);
    $('searchInput').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') performSearch();
    });
    
    // Preview toggle
    $('showPreviews').addEventListener('change', (e) => {
        state.showPreviews = e.target.checked;
        if (state.totalHits > 0) {
            performSearch(); // Rerun search to refresh display
        }
    });

    // Clear search button
    $('clearSearchBtn').addEventListener('click', clearSearch);

    // Show/hide clear button based on input content
    $('searchInput').addEventListener('input', updateClearButtonVisibility);

    // Saved queries button
    $('savedBtn').addEventListener('click', showSavedQueries);
    $('closeSavedModal').addEventListener('click', hideSavedModal);
    $('closeSavedBtn').addEventListener('click', hideSavedModal);
    $('saveCurrentBtn').addEventListener('click', confirmSaveQuery);

    // Add condition toggle
    $('toggleAddCondition').addEventListener('click', () => {
        $('addConditionPanel').classList.toggle('show');
    });

    // Add condition form
    $('addConditionForm').addEventListener('submit', (e) => {
        e.preventDefault();
        addCondition();
    });

    // Pagination
    $('prevPage').addEventListener('click', () => changePage(-1));
    $('nextPage').addEventListener('click', () => changePage(1));
    
    // Initialize Tom Select for facet dropdown
    state.tomSelectInstance = new TomSelect('#facetSelect', {
        maxItems: 1, // Only allow single selection
        placeholder: 'Select a facet...',
        maxOptions: null,
        sortField: null, // Keep our custom sort order
        hideSelected: false, // Show selected item in dropdown
        closeAfterSelect: true,
        openOnFocus: true, // Open dropdown when focused
        allowEmptyOption: true, // Allow clearing the selection
        loadThrottle: null, // No throttling
        score: function(search) {
            // Always show all options when search is empty
            if (!search) {
                return function() { return 1; };
            }
            // Use default scoring for non-empty searches
            return this.getScoreFunction(search);
        },
        render: {
            option: function(data, escape) {
                const isStandard = state.standardFields.includes(data.value.toLowerCase());
                const style = isStandard ? 'font-weight: bold;' : '';
                return `<div style="${style}">${escape(data.text)}</div>`;
            },
            item: function(data, escape) {
                const isStandard = state.standardFields.includes(data.value.toLowerCase());
                const style = isStandard ? 'font-weight: bold;' : '';
                return `<div style="${style}">${escape(data.text)}</div>`;
            }
        },
        onItemAdd: function() {
            // Blur the input after selection to prevent cursor issues
            this.blur();
        },
        onChange: async (value) => {
            state.selectedFacet = value;
            updateUrl();
            if (value) {
                await loadFacetValues();
            }
        }
    });
    
    // Initialize Tom Select for condition field dropdown
    state.conditionFieldTomSelect = new TomSelect('#conditionField', {
        maxItems: 1, // Only allow single selection
        placeholder: 'Select field...',
        maxOptions: null,
        sortField: null, // Keep our custom sort order
        hideSelected: false, // Show selected item in dropdown
        closeAfterSelect: true,
        openOnFocus: true, // Open dropdown when focused
        allowEmptyOption: true, // Allow clearing the selection
        loadThrottle: null, // No throttling
        score: function(search) {
            // Always show all options when search is empty
            if (!search) {
                return function() { return 1; };
            }
            // Use default scoring for non-empty searches
            return this.getScoreFunction(search);
        },
        render: {
            option: function(data, escape) {
                const isStandard = state.standardFields.includes(data.value.toLowerCase());
                const style = isStandard ? 'font-weight: bold;' : '';
                return `<div style="${style}">${escape(data.text)}</div>`;
            },
            item: function(data, escape) {
                const isStandard = state.standardFields.includes(data.value.toLowerCase());
                const style = isStandard ? 'font-weight: bold;' : '';
                return `<div style="${style}">${escape(data.text)}</div>`;
            }
        },
        onItemAdd: function() {
            // Blur the input after selection to prevent cursor issues
            this.blur();
        }
    });
    
    // Sort direction
    $('sortDirection').addEventListener('change', (e) => {
        state.sortDirection = e.target.value;
        state.currentPage = 0; // Reset to first page when changing sort
        executeSearch();
    });
}

/**
 * Load state from URL parameters
 */
async function loadFromUrl() {
    const params = new URLSearchParams(window.location.search);
    
    if (params.has('q')) {
        state.searchString = params.get('q');
        $('searchInput').value = state.searchString;
        updateClearButtonVisibility();
    }
    
    if (params.has('conditions')) {
        const raw = params.get('conditions');
        try {
            // URLSearchParams already decodes once; conditions is now stored as
            // plain JSON. Fall back to the legacy double-encoded format so older
            // bookmarked/shared URLs keep working.
            let parsed;
            try {
                parsed = JSON.parse(raw);
            } catch (inner) {
                parsed = JSON.parse(decodeURIComponent(raw));
            }
            state.conditions = parsed;
            renderConditions();
        } catch (e) {
            console.error('Failed to parse conditions from URL:', e);
        }
    }
    
    if (params.has('page')) {
        state.currentPage = parseInt(params.get('page')) || 0;
    }



    if (params.has('sort')) {
        state.sortDirection = params.get('sort');
        $('sortDirection').value = state.sortDirection;
    }

    if (params.has('facet')) {
        // Selected facet is applied to the dropdown by renderFacetDropdown()
        // once the available keys load (it preserves state.selectedFacet).
        state.selectedFacet = params.get('facet');
    }

    // Always perform initial search to show available items and facets
    // (performSearch will load available keys)
    performSearch();
}

/**
 * Update URL with current state
 */
function updateUrl() {
    const params = new URLSearchParams();
    
    if (state.searchString) {
        params.set('q', state.searchString);
    }
    
    if (state.conditions.length > 0) {
        // URLSearchParams encodes the value on toString(); JSON.stringify only.
        params.set('conditions', JSON.stringify(state.conditions));
    }
    
    if (state.currentPage > 0) {
        params.set('page', state.currentPage.toString());
    }

    if (state.sortDirection && state.sortDirection !== 'DESC') {
        params.set('sort', state.sortDirection);
    }

    if (state.selectedFacet && state.selectedFacet !== FIELD_PATH) {
        params.set('facet', state.selectedFacet);
    }

    const newUrl = params.toString() ? `?${params.toString()}` : window.location.pathname;
    window.history.replaceState({}, '', newUrl);
}

/**
 * Perform search
 */
async function performSearch() {
    state.searchString = $('searchInput').value.trim();
    state.currentPage = 0;

    // executeSearch will clear results and load all data in parallel
    await executeSearch();
}

/**
 * Clear search input and perform search
 */
function clearSearch() {
    $('searchInput').value = '';
    updateClearButtonVisibility();
    performSearch();
}

/**
 * Update visibility of clear button based on input content
 */
function updateClearButtonVisibility() {
    const hasContent = $('searchInput').value.length > 0;
    $('clearSearchBtn').style.display = hasContent ? 'inline-flex' : 'none';
}

/**
 * Execute the search items API call and update UI
 * @private
 */
async function executeSearchApiCall(allConditions) {
    const result = await searchItems({
        searchString: state.searchString || null,
        searchConditions: allConditions,
        skip: state.currentPage * state.pageSize,
        howmany: state.pageSize,
        sortDirection: state.sortDirection
    });

    state.totalHits = result.totalHits;

    renderResults(result.items);
    renderDataSourceFilters();
    updatePagination();
    updateUrl();
}

/**
 * Execute the search API call
 */
async function executeSearch() {
    // Clear existing results immediately
    clearResults();

    // Setup loading indicator with 500ms delay
    let loadingTimeout;
    let loadingShown = false;

    loadingTimeout = setTimeout(() => {
        loadingShown = true;
        showLoading(true);
    }, 500);

    try {
        // Execute search first (user's primary action) - don't wait for secondary data
        const searchPromise = executeSearchApiCall(state.conditions);
        
        // Start loading facet values and attribute keys in background (don't block search results)
        // These update dropdowns but shouldn't delay showing search results
        loadFacetValues().catch(err => console.error('Failed to load facet values:', err));
        loadItemAttributeKeys().catch(err => console.error('Failed to load attribute keys:', err));
        
        // Wait only for the search results
        await searchPromise;

    } catch (error) {
        showError('Search failed: ' + error.message);
    } finally {
        // Clear the timeout if it hasn't fired yet
        clearTimeout(loadingTimeout);
        // Only hide loading if it was shown
        if (loadingShown) {
            showLoading(false);
        }
    }
}

/**
 * Add a new condition
 */
async function addCondition() {
    const field = state.conditionFieldTomSelect.getValue();
    const operation = $('conditionOperation').value;
    let value = $('conditionValue').value.trim();

    if (!value) {
        await showAlert('Please enter a value');
        return;
    }

    // No wildcard wrapping: LIKE is already a "contains" match server-side, and the server strips
    // every '%' from a condition value anyway. Conditions saved before this change still carry the
    // '%value%' wrapping, which is why renderConditions() keeps stripping it for display.

    state.conditions.push({
        name: field,
        operation: operation,
        value: value
    });

    $('conditionValue').value = '';
    renderConditions();

    // performSearch will reload available keys
    performSearch();
}

/**
 * Remove a condition by index
 */
async function removeCondition(index) {
    state.conditions.splice(index, 1);
    renderConditions();

    // performSearch will reload available keys
    performSearch();
}

/**
 * Flip a condition to its opposite operation
 */
async function flipCondition(index) {
    const condition = state.conditions[index];
    if (!condition) return;

    // Define opposite operations
    const opposites = {
        'LIKE': 'NOT_LIKE',
        'NOT_LIKE': 'LIKE',
        'EQUAL': 'NOT_EQUAL',
        'NOT_EQUAL': 'EQUAL',
        'LESS': 'GREATER',
        'GREATER': 'LESS'
    };

    const newOperation = opposites[condition.operation];
    if (newOperation) {
        condition.operation = newOperation;
        renderConditions();
        performSearch();
    }
}

/**
 * Add condition from facet click
 */
async function addFacetCondition(key, value) {
    // Check if condition already exists
    const exists = state.conditions.some(c => c.name === key && c.value === value);
    if (exists) return;

    state.conditions.push({
        name: key,
        operation: 'EQUAL',
        value: value
    });

    renderConditions();

    // performSearch will reload available keys
    performSearch();
}

/**
 * Render conditions pills
 */
function renderConditions() {
    const operationLabels = {
        'LIKE': 'contains words',
        'NOT_LIKE': 'does not contain words',
        'EQUAL': '=',
        'NOT_EQUAL': '≠',
        'LESS': '<',
        'GREATER': '>'
    };
    
    // Render all conditions
    const html = state.conditions.map((condition, index) => {
        // For display, strip leading/trailing % from LIKE values to show cleaner text
        let displayValue = condition.value;
        if (condition.operation === 'LIKE' && displayValue.startsWith('%') && displayValue.endsWith('%')) {
            displayValue = displayValue.slice(1, -1);
        }
        return `
            <div class="condition-pill">
                <span>${escapeHtml(condition.name)} ${escapeHtml(operationLabels[condition.operation] || condition.operation)} "${escapeHtml(displayValue)}"</span>
                <button class="flip-btn" onclick="flipCondition(${index})" title="Flip to opposite operation">⇄</button>
                <button class="remove-btn" onclick="removeCondition(${index})" title="Remove condition">×</button>
            </div>
        `;
    }).join('');

    $('conditionsList').innerHTML = html;
}

/**
 * Render datasource filters section (called after search results)
 */
function renderDataSourceFilters() {
    // Re-render conditions to show current datasource filters
    renderConditions();
}

/**
 * Render search results
 */
function renderResults(items) {
    if (!items || items.length === 0) {
        if (state.searchString || state.conditions.length > 0) {
            $('resultsList').innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">📭</div>
                    <div class="empty-state-title">No Results Found</div>
                    <p>Try different search terms or adjust your conditions.</p>
                </div>
            `;
        } else {
            $('resultsList').innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">🔍</div>
                    <div class="empty-state-title">Start Searching</div>
                    <p>Enter a search term or add conditions to find your files.</p>
                </div>
            `;
        }
        $('resultsCount').textContent = 'No results';
        return;
    }
    
    $('resultsCount').textContent = `${state.totalHits.toLocaleString()} result${state.totalHits !== 1 ? 's' : ''} found`;
    
    if (state.showPreviews) {
        // Preview mode: show cards in grid layout
        $('resultsList').innerHTML = `
            <div class="results-grid">
                ${items.map(item => {
                    const safeName = encodeForInlineOnclick(item.name);
                    const safeFolder = encodeForInlineOnclick(item.folder || '');
                    const safeDataSourceName = encodeForInlineOnclick(item.dataSourceName);
                    const previewUrl = item.dataSourceName ? buildPreviewUrl(item.dataSourceType, item.dataSourceName, item.folder || '', item.name) : '';
                    return `
                        <div class="result-card" onclick="openItem('${safeName}', '${safeFolder}', '${safeDataSourceName}', '${encodeForInlineOnclick(item.dataSourceType)}')">
                            ${previewUrl ? `<img src="${previewUrl}" alt="${escapeHtml(item.name)}" class="result-card-thumb" onerror="this.style.display='none'">` : '<div class="result-card-thumb-placeholder"></div>'}
                            <div class="result-card-content">
                                <div class="result-card-title">${item.dataSourceName ? `<a class="result-card-download" href="${buildDownloadUrl(item.dataSourceType, item.dataSourceName, item.folder || '', item.name)}" onclick="event.stopPropagation()" title="Download">↓</a>` : ''}${escapeHtml(item.name)}</div>
                                <div class="result-card-folder">${renderClickablePath(item.folder)}</div>
                            </div>
                        </div>
                    `;
                }).join('')}
            </div>
        `;
    } else {
        // Normal mode: list view
        $('resultsList').innerHTML = items.map(item => `
            <div class="result-item" onclick="openItem('${encodeForInlineOnclick(item.name)}', '${encodeForInlineOnclick(item.folder || '')}', '${encodeForInlineOnclick(item.dataSourceName)}', '${encodeForInlineOnclick(item.dataSourceType)}')">
                <div class="result-title">${escapeHtml(item.name)} ${item.dataSourceName ? `<a class="download-arrow" href="${buildDownloadUrl(item.dataSourceType, item.dataSourceName, item.folder || '', item.name)}" onclick="event.stopPropagation()" title="Download">↓</a>` : ''}</div>
                <div class="result-folder">${renderClickablePath(item.folder)}</div>
                <div class="result-meta">
                    <span>Created: ${formatDate(item.dateCreated)}</span>
                    <span>Modified: ${formatDate(item.dateModified)}</span>
                </div>
            </div>
        `).join('');
    }
}

function encodeForInlineOnclick(value) {
    return encodeURIComponent(value || '').replace(/'/g, '%27');
}

// use shared buildDownloadUrl from util.js

/**
 * Render the facet dropdown with available keys (uses itemAttributeKeys)
 */
function renderFacetDropdown() {
    if (!state.tomSelectInstance) {
        console.warn('Tom Select not initialized yet');
        return;
    }

    if (!state.availableKeys || state.availableKeys.length === 0) {
        state.tomSelectInstance.clear();
        state.tomSelectInstance.clearOptions();
        state.tomSelectInstance.addOption({value: '', text: 'No facets available'});
        state.tomSelectInstance.setValue('', true); // silent=true to avoid triggering change
        state.currentFacetKeys = [];
        return;
    }

    // Filter out keys not suitable for faceting:
    // - "name" (too many unique values)
    // - "content", "ocr-content" (exact matches - text content fields)
    // - "content-0", "content-1", etc. (numbered content chunks)
    // - "ocr-content-0", "ocr-content-1", etc. (numbered OCR content chunks)
    // Allow: "path", "content-type", "content-encoding" and other non-numbered content fields
    const facetKeys = state.availableKeys.filter(key => {
        const lowerKey = key.toLowerCase();
        return key !== FIELD_NAME &&
               lowerKey !== 'content' &&
               lowerKey !== 'ocr-content' &&
               !/^content-\d+$/.test(lowerKey) &&
               !/^ocr-content-\d+$/.test(lowerKey);
    });

    if (facetKeys.length === 0) {
        state.tomSelectInstance.clear();
        state.tomSelectInstance.clearOptions();
        state.tomSelectInstance.addOption({value: '', text: 'No facets available'});
        state.tomSelectInstance.setValue('', true);
        state.currentFacetKeys = [];
        return;
    }

    // Check if the facet keys have actually changed
    const facetKeysChanged = !arraysEqual(facetKeys, state.currentFacetKeys);
    
    // Only rebuild dropdown if keys have changed
    if (facetKeysChanged) {
        // Save current selection before clearing
        const currentSelection = state.selectedFacet;
        
        // Build dropdown options for Tom Select
        state.tomSelectInstance.clear();
        state.tomSelectInstance.clearOptions();
        
        facetKeys.forEach(key => {
            state.tomSelectInstance.addOption({value: key, text: key});
        });

        // If previous selection is still in the new list, keep it; otherwise select first
        if (facetKeys.includes(currentSelection)) {
            state.selectedFacet = currentSelection;
        } else {
            state.selectedFacet = facetKeys[0];
        }
        
        // Set the value silently (don't trigger loadFacetValues since executeSearch already does that)
        state.tomSelectInstance.setValue(state.selectedFacet, true);
        
        // Update tracking array
        state.currentFacetKeys = [...facetKeys];
    } else {
        // Keys haven't changed, but ensure the current selection is properly set
        // (in case it got lost somehow)
        if (state.selectedFacet && facetKeys.includes(state.selectedFacet)) {
            const currentValue = state.tomSelectInstance.getValue();
            if (currentValue !== state.selectedFacet) {
                state.tomSelectInstance.setValue(state.selectedFacet, true);
            }
        }
    }
}

/**
 * Helper function to compare two arrays for equality
 */
function arraysEqual(arr1, arr2) {
    if (arr1.length !== arr2.length) return false;
    for (let i = 0; i < arr1.length; i++) {
        if (arr1[i] !== arr2[i]) return false;
    }
    return true;
}

/**
 * Load facet values for the currently selected facet from the API
 */
// Maximum length for facet values to display (longer values are excluded)
const MAX_FACET_VALUE_LENGTH = 100;

async function loadFacetValues() {
    const facetValuesDiv = $('facetValues');
    
    if (!state.selectedFacet) {
        facetValuesDiv.innerHTML = '';
        return;
    }
    
    // Show loading indicator
    facetValuesDiv.innerHTML = '<p class="empty-state">Loading...</p>';
    
    try {
        // Call the new getFacets API for this specific key
        const facetValues = await getFacets(state.selectedFacet, {
            searchConditions: state.conditions,
            searchString: state.searchString || null
        });
        
        state.facetValues = facetValues || [];
        renderFacetValues();
        
    } catch (error) {
        console.error('Failed to load facet values:', error);
        facetValuesDiv.innerHTML = '<p class="empty-state">Failed to load values</p>';
    }
}

/**
 * Render values for the currently selected facet
 */
function renderFacetValues() {
    const facetValuesDiv = $('facetValues');
    
    if (!state.selectedFacet || !state.facetValues || state.facetValues.length === 0) {
        facetValuesDiv.innerHTML = '<p class="empty-state">No values for this facet</p>';
        return;
    }
    
    // Filter out values that are too long
    const filteredValues = state.facetValues.filter(fv => 
        !fv.value || fv.value.length <= MAX_FACET_VALUE_LENGTH
    );
    
    if (filteredValues.length === 0) {
        facetValuesDiv.innerHTML = '<p class="empty-state">No values for this facet</p>';
        return;
    }
    
    facetValuesDiv.innerHTML = `
        <div class="facet-filter-container">
            <input 
                type="text" 
                id="facetValueFilterInput" 
                class="form-input" 
                placeholder="Filter values..."
            />
        </div>
        <ul class="facet-list">
            ${filteredValues.map(fv => `
                <li class="facet-item" data-value="${escapeHtml((fv.value || '').toLowerCase())}" data-facet-key="${escapeHtml(state.selectedFacet)}" data-facet-value="${escapeHtml(fv.value)}" onclick="addFacetCondition(this.dataset.facetKey, this.dataset.facetValue)">
                    <span class="facet-value">${escapeHtml(fv.value) || '(empty)'}</span>
                    <span class="facet-count">${fv.count}</span>
                </li>
            `).join('')}
        </ul>
    `;
    
    // Attach filter event listener
    const filterInput = $('facetValueFilterInput');
    if (filterInput) {
        filterInput.addEventListener('input', (e) => {
            const filterText = e.target.value.toLowerCase();
            const facetItems = facetValuesDiv.querySelectorAll('.facet-item');
            
            facetItems.forEach(item => {
                const itemValue = item.getAttribute('data-value');
                if (itemValue.includes(filterText)) {
                    item.style.display = '';
                } else {
                    item.style.display = 'none';
                }
            });
        });
        // Focus on the filter input
        filterInput.focus();
    }
}

/**
 * Load available item attribute keys and populate the condition field dropdown and facet dropdown
 */
async function loadItemAttributeKeys() {
    try {
        // Use the new API that filters keys based on current search conditions
        const keys = await getAvailableItemAttributeKeys({
            searchConditions: state.conditions,
            searchString: state.searchString || null
        });
        state.availableKeys = keys;
        renderConditionFieldDropdown();
        renderFacetDropdown();
    } catch (error) {
        console.error('Failed to load treatment result keys:', error);
        state.availableKeys = [FIELD_NAME, FIELD_PATH];
        renderConditionFieldDropdown();
        renderFacetDropdown();
        const errEl = $('facetKeysError');
        if (errEl) { errEl.textContent = 'Failed to load attribute filters'; errEl.style.display = 'block'; }
    }
}

/**
 * Render the condition field dropdown with available keys
 */
function renderConditionFieldDropdown() {
    if (!state.conditionFieldTomSelect) {
        console.warn('Condition field Tom Select not initialized yet');
        return;
    }

    if (!state.availableKeys || state.availableKeys.length === 0) {
        // Fallback to defaults if no keys available
        state.conditionFieldTomSelect.clear();
        state.conditionFieldTomSelect.clearOptions();
        state.conditionFieldTomSelect.addOption({value: 'name', text: 'Name'});
        state.conditionFieldTomSelect.addOption({value: 'path', text: 'Path'});
        state.conditionFieldTomSelect.setValue('name', true);
        return;
    }

    // Keys are already sorted by the backend with priority order
    // Use all available keys (including name, content*, etc.) for condition filtering

    state.conditionFieldTomSelect.clear();
    state.conditionFieldTomSelect.clearOptions();
    
    state.availableKeys.forEach(key => {
        state.conditionFieldTomSelect.addOption({value: key, text: key});
    });
    
    // Set first value as default
    if (state.availableKeys.length > 0) {
        state.conditionFieldTomSelect.setValue(state.availableKeys[0], true);
    }
}

/**
 * Update pagination controls
 */
function updatePagination() {
    const totalPages = Math.ceil(state.totalHits / state.pageSize);
    
    if (totalPages <= 1) {
        $('pagination').style.display = 'none';
        return;
    }
    
    $('pagination').style.display = 'flex';
    $('prevPage').disabled = state.currentPage === 0;
    $('nextPage').disabled = state.currentPage >= totalPages - 1;
    $('pageInfo').textContent = `Page ${state.currentPage + 1} of ${totalPages}`;
}

/**
 * Change page
 */
function changePage(delta) {
    const totalPages = Math.ceil(state.totalHits / state.pageSize);
    const newPage = state.currentPage + delta;
    
    if (newPage >= 0 && newPage < totalPages) {
        state.currentPage = newPage;
        executeSearch();
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }
}

/**
 * Open item detail page
 */
function openItem(name, folder, dsName, dsType) {
    const params = new URLSearchParams({
        name,
        folder,
        ds: dsName,
        dsType
    });

    // Pass search context for highlighting
    if (state.searchString) {
        params.set('q', state.searchString);
    }
    if (state.conditions && state.conditions.length > 0) {
        params.set('conditions', JSON.stringify(state.conditions));
    }
    if (state.selectedFacet && state.selectedFacet !== FIELD_PATH) {
        params.set('facet', state.selectedFacet);
    }

    window.location.href = `item.html?${params}`;
}

/**
 * Clear results data
 */
function clearResults() {
    $('resultsList').innerHTML = '';
    $('resultsCount').textContent = '';
}

/**
 * Show/hide loading state
 */
function showLoading(loading) {
    $('resultsLoading').style.display = loading ? 'flex' : 'none';
    $('resultsList').style.display = loading ? 'none' : 'flex';
}

/**
 * Show error message
 */
function showError(message) {
    $('resultsList').innerHTML = `
        <div class="alert alert-error">
            ${escapeHtml(message)}
        </div>
    `;
}

/**
 * Render a clickable path where each segment can be clicked to add it as a search condition.
 * Path comes in as "folder1/folder2/folder3" format from the server.
 */
function renderClickablePath(folder) {
    if (!folder) {
        return '';
    }
    
    const segments = folder.split('/');
    let html = '';
    
    for (let i = 0; i < segments.length; i++) {
        if (i > 0) {
            html += '<span class="path-separator">/</span>';
        }
        
        // Each segment is clickable and adds just that segment as a path condition
        html += `<span class="path-segment" data-segment="${escapeHtml(segments[i])}" onclick="event.stopPropagation(); addFacetCondition('${FIELD_PATH}', this.dataset.segment)">${escapeHtml(segments[i])}</span>`;
    }
    
    return html;
}

// Expose functions to global scope for inline onclick handlers
window.removeCondition = removeCondition;
window.flipCondition = flipCondition;
window.openItem = openItem;
window.addFacetCondition = addFacetCondition;

// ================= AI Assist Functions =================

/**
 * Initialize AI assist feature - show button if AI is configured
 */
async function initAiAssist() {
    try {
        const provider = await getAiProvider();
        if (provider && provider !== 'NONE') {
            $('aiAssistBtn').style.display = 'inline-block';
            attachAiEventListeners();
        }
    } catch (e) {
        console.log('AI assist not available:', e);
    }
}

/**
 * Attach AI-related event listeners
 */
function attachAiEventListeners() {
    $('aiAssistBtn').addEventListener('click', handleAiAssist);
    $('closeAiModal').addEventListener('click', hideAiModal);
    $('aiCancelBtn').addEventListener('click', hideAiModal);
}

/**
 * Handle AI assist button click - use search box content directly
 */
async function handleAiAssist() {
    const query = $('searchInput').value.trim();
    if (!query) {
        // Nothing to interpret - focus search box
        $('searchInput').focus();
        return;
    }
    
    // Show loading indicator on button
    const btn = $('aiAssistBtn');
    const originalText = btn.textContent;
    btn.textContent = 'interpreting..';
    btn.disabled = true;
    
    try {
        const result = await interpretSearchQuery(query);
        console.log('AI interpretation result:', result);
        
        if (result.success) {
            // Auto-apply successful interpretation
            applyAiResult(result);
        } else {
            // Show error in modal
            showAiModalWithError(query, result.explanation);
        }
    } catch (e) {
        // Network error - show in modal
        showAiModalWithError(query, 'Failed to contact AI service: ' + e.message);
    } finally {
        btn.textContent = originalText;
        btn.disabled = false;
    }
}

/**
 * Show the AI modal with an error message
 */
function showAiModalWithError(query, errorMessage) {
    $('aiAssistModal').style.display = 'flex';
    $('aiQueryInput').value = query;
    $('aiQueryInput').disabled = true;
    $('aiInterpretation').style.display = 'block';
    $('aiExplanation').textContent = errorMessage;
    $('aiPreview').innerHTML = '<div class="ai-preview-item ai-error"><em>Please rephrase your query in the search box and try again</em></div>';
    $('aiApplyBtn').style.display = 'none';
    $('aiInterpretBtn').style.display = 'none';
    $('aiLoading').style.display = 'none';
    state.pendingAiSearch = null;
}

/**
 * Hide the AI assist modal
 */
function hideAiModal() {
    $('aiAssistModal').style.display = 'none';
    $('aiQueryInput').disabled = false;
    $('aiInterpretBtn').style.display = 'inline-block';
    state.pendingAiSearch = null;
}

/**
 * Apply AI interpretation result to search
 */
function applyAiResult(result) {
    if (!result || !result.success) return;
    
    // Clear existing conditions
    state.conditions = [];
    
    // Apply search string
    if (result.searchString) {
        $('searchInput').value = result.searchString;
        state.searchString = result.searchString;
    } else {
        $('searchInput').value = '';
        state.searchString = '';
    }
    updateClearButtonVisibility();
    
    // Apply conditions
    for (const condition of result.searchConditions) {
        state.conditions.push({
            name: condition.name,
            operation: condition.operation,
            value: condition.value
        });
    }
    
    renderConditions();
    state.currentPage = 0;
    executeSearch();
}

// ================= Saved Queries Functions =================

/**
 * Show the saved queries modal
 */
async function showSavedQueries() {
    // Check if user is logged in
    if (!isLoggedIn()) {
        showAlert('Please log in to use saved queries');
        return;
    }
    
    $('savedQueriesModal').style.display = 'flex';
    $('savedQueriesList').innerHTML = '<div class="loading" id="savedQueriesLoading"><div class="spinner"></div></div>';
    
    try {
        const queries = await getSavedQueries();
        renderSavedQueries(queries);
    } catch (e) {
        console.error('Failed to load saved queries:', e);
        $('savedQueriesList').innerHTML = '<div class="empty-state"><p>Failed to load saved queries</p></div>';
    }
}

/**
 * Render the list of saved queries
 */
function renderSavedQueries(queries) {
    const list = $('savedQueriesList');
    
    if (!queries || queries.length === 0) {
        list.innerHTML = '<div class="empty-state"><p>No saved queries yet. Save your current search conditions to get started.</p></div>';
        return;
    }
    
    let html = '<div class="saved-queries-items">';
    
    for (const query of queries) {
        const conditionsText = query.queryConditions.map(c => 
            `${c.name} ${formatOperation(c.operation)} "${c.value}"`
        ).join(', ');
        
        const dateStr = formatDate(query.dateCreated);
        
        html += `
            <div class="saved-query-item">
                <div class="saved-query-header">
                    <h4 class="saved-query-name">${escapeHtml(query.name)}</h4>
                    <div class="saved-query-actions">
                        <button class="btn btn-sm btn-primary" data-query-id="${escapeHtml(query.id)}" onclick="applySavedQuery(this.dataset.queryId)">Apply</button>
                        <button class="btn btn-sm btn-danger" data-query-id="${escapeHtml(query.id)}" data-query-name="${escapeHtml(query.name)}" onclick="deleteSavedQueryConfirm(this.dataset.queryId, this.dataset.queryName)">Delete</button>
                    </div>
                </div>
                <div class="saved-query-details">
                    <small>${escapeHtml(conditionsText)}</small>
                    <small class="saved-query-date">Saved on ${dateStr}</small>
                </div>
            </div>
        `;
    }
    
    html += '</div>';
    list.innerHTML = html;
}

/**
 * Format operation for display
 */
function formatOperation(operation) {
    const map = {
        'LIKE': 'contains words',
        'NOT_LIKE': 'does not contain words',
        'EQUAL': 'equals',
        'NOT_EQUAL': 'not equal to',
        'LESS': 'less than',
        'GREATER': 'greater than'
    };
    return map[operation] || operation;
}

/**
 * Apply a saved query
 */
window.applySavedQuery = async function(queryId) {
    try {
        const queries = await getSavedQueries();
        const query = queries.find(q => q.id === queryId);
        
        if (!query) {
            showAlert('Saved query not found');
            return;
        }
        
        // Clear current state
        state.conditions = [];
        state.searchString = '';
        $('searchInput').value = '';
        
        // Apply search string from saved query
        if (query.searchString) {
            state.searchString = query.searchString;
            $('searchInput').value = query.searchString;
        }
        
        // Apply conditions from saved query
        if (query.queryConditions) {
            for (const condition of query.queryConditions) {
                state.conditions.push({
                    name: condition.name,
                    operation: condition.operation,
                    value: condition.value
                });
            }
        }
        
        // Apply sort direction from saved query
        if (query.sortDirection) {
            state.sortDirection = query.sortDirection;
            $('sortDirection').value = query.sortDirection;
        }
        
        updateClearButtonVisibility();
        renderConditions();
        state.currentPage = 0;
        executeSearch();
        
        // Close modal
        hideSavedModal();
    } catch (e) {
        console.error('Failed to apply saved query:', e);
        showAlert('Failed to apply saved query');
    }
};

/**
 * Delete a saved query with confirmation
 */
window.deleteSavedQueryConfirm = async function(queryId, queryName) {
    const confirmed = await showConfirm(`Are you sure you want to delete "${queryName}"?`);
    if (!confirmed) {
        return;
    }
    
    deleteSavedQueryById(queryId);
};

/**
 * Delete a saved query by ID
 */
async function deleteSavedQueryById(queryId) {
    try {
        await deleteSavedQuery(queryId);
        
        // Refresh the list
        const queries = await getSavedQueries();
        renderSavedQueries(queries);
    } catch (e) {
        console.error('Failed to delete saved query:', e);
        showAlert('Failed to delete saved query');
    }
}

/**
 * Hide the saved queries modal
 */
function hideSavedModal() {
    $('savedQueriesModal').style.display = 'none';
}

/**
 * Save the current query without asking for a name (name is auto-generated)
 */
async function confirmSaveQuery() {
    // Check if user is logged in
    if (!isLoggedIn()) {
        showAlert('Please log in to save queries');
        return;
    }
    
    try {
        // Validate that there's something to save
        const hasSearchString = state.searchString && state.searchString.trim() !== '';
        const hasConditions = state.conditions && state.conditions.length > 0;
        
        if (!hasSearchString && !hasConditions) {
            showAlert('No search query to save. Enter a search term or add at least one filter.');
            return;
        }
        
        await createSavedQuery(
            state.searchString || null,
            state.conditions || [],
            state.sortDirection || 'DESC'
        );
        
        // Refresh the saved queries list if modal is open
        if ($('savedQueriesModal').style.display === 'flex') {
            const queries = await getSavedQueries();
            renderSavedQueries(queries);
        }
    } catch (e) {
        console.error('Failed to save query:', e);
        showAlert('Failed to save query: ' + e.message);
    }
}
