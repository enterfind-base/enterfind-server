/**
 * EnterFind Sources Page Logic
 */

import { $, escapeHtml, formatDate, showAlert, showConfirm } from './util.js';
import { getDataSources, deleteDataSource, scanDataSource, subscribeToDataSourceStats, fetchServerTimezone } from './api.js';

// State
let dataSources = [];
let sourceToDelete = null;
let activeSubscriptions = new Map(); // Map of "name:type" -> unsubscribe function

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    // Fetch server timezone for proper date formatting
    fetchServerTimezone();
    attachEventListeners();
    loadDataSources();
});

// Clean up subscriptions on page unload
window.addEventListener('beforeunload', () => {
    stopSubscriptions();
});

/**
 * Attach event listeners
 */
function attachEventListeners() {
    // Close modal on overlay click
    $('deleteModal').addEventListener('click', (e) => {
        if (e.target === $('deleteModal')) closeDeleteModal();
    });
    
    // Delete confirmation
    $('confirmDeleteBtn').addEventListener('click', confirmDelete);
}

/**
 * Load all data sources
 */
async function loadDataSources() {
    showLoading(true);
    
    try {
        dataSources = await getDataSources();
        renderSources();
        startSubscriptions();
    } catch (error) {
        showError('Failed to load data sources: ' + error.message);
    } finally {
        showLoading(false);
    }
}

/**
 * Start subscriptions for all data sources
 */
function startSubscriptions() {
    // Clean up existing subscriptions
    stopSubscriptions();
    
    // Start new subscriptions
    dataSources.forEach(source => {
        const key = `${source.name}:${source.type}`;
        const unsubscribe = subscribeToDataSourceStats(
            source.name,
            source.type,
            5, // Update interval in seconds (longer for sources list)
            (stats) => {
                updateSourceStatus(source.name, source.type, stats.dataSourceStatus, stats.dateLastScanned);
            },
            (error) => {
                console.error(`Subscription error for ${key}:`, error);
            }
        );
        activeSubscriptions.set(key, unsubscribe);
    });
}

/**
 * Stop all active subscriptions
 */
function stopSubscriptions() {
    activeSubscriptions.forEach((unsubscribe, key) => {
        try {
            unsubscribe();
        } catch (error) {
            console.error(`Error stopping subscription for ${key}:`, error);
        }
    });
    activeSubscriptions.clear();
}

/**
 * Update the status of a specific data source in the UI
 */
function updateSourceStatus(name, type, newStatus, newDateLastScanned) {
    const key = `${name}:${type}`;
    // Compare via dataset rather than an attribute selector, which a quote in the name would break
    const sourceCard = [...document.querySelectorAll('.source-card')].find(card => card.dataset.sourceKey === key);
    if (!sourceCard) return;
    
    // Update the status badge
    const statusElement = sourceCard.querySelector('.status-badge');
    if (statusElement) {
        statusElement.outerHTML = formatStatus(newStatus);
    }
    
    // Update the scan button state
    const scanButton = sourceCard.querySelector('button[onclick*="triggerScan"]');
    if (scanButton) {
        scanButton.disabled = newStatus === 'SCANNING';
    }
    
    // Update the source object in our dataSources array
    const sourceIndex = dataSources.findIndex(s => s.name === name && s.type === type);
    if (sourceIndex !== -1) {
        dataSources[sourceIndex].status = newStatus;
        dataSources[sourceIndex].dateLastScanned = newDateLastScanned;
        
        // Update the scanned date display if it exists
        const detailsElement = sourceCard.querySelector('.source-details');
        if (detailsElement) {
            const scannedParagraph = detailsElement.querySelector('p strong');
            if (scannedParagraph && scannedParagraph.textContent.includes('Scanned:')) {
                const scannedP = scannedParagraph.parentElement;
                scannedP.innerHTML = `<strong>Scanned:</strong> ${formatDate(newDateLastScanned)}`;
            }
        }
    }
}

/**
 * Render data sources grid
 */
function renderSources() {
    if (dataSources.length === 0) {
        $('sourcesGrid').style.display = 'none';
        $('emptyState').style.display = 'block';
        return;
    }
    
    $('sourcesGrid').style.display = 'flex';
    $('emptyState').style.display = 'none';
    
    $('sourcesGrid').innerHTML = dataSources.map(source => `
        <div class="source-card" data-source-key="${escapeHtml(`${source.name}:${source.type}`)}" data-name="${escapeHtml(source.name)}" data-type="${escapeHtml(source.type)}">
            <div class="source-header">
                <div style="display: flex; align-items: center; gap: 0.5rem; flex: 1;">
                    <span class="source-name">${escapeHtml(source.name)}</span>
                    ${formatStatus(source.status)}
                </div>
                <span class="source-type">${formatType(source.type)}</span>
            </div>
            <div class="source-details">
                ${getSourceDetailsRow(source)}
            </div>
            <div class="source-actions">
                <a href="source.html?name=${encodeURIComponent(source.name)}&type=${encodeURIComponent(source.type)}" class="btn btn-secondary btn-sm">Edit</a>
                <button class="btn btn-secondary btn-sm" onclick="triggerScan(this.closest('.source-card').dataset.name, this.closest('.source-card').dataset.type)" ${source.status === 'SCANNING' ? 'disabled' : ''}>Scan Now</button>
                <button class="btn btn-danger btn-sm" onclick="promptDelete(this.closest('.source-card').dataset.name, this.closest('.source-card').dataset.type)">Delete</button>
            </div>
        </div>
    `).join('');
}

/**
 * Get source-specific details HTML for row layout
 */
function getSourceDetailsRow(source) {
    const details = [];
    
    switch (source.type) {
        case 'LOCAL_FILESYSTEM':
            details.push(`<p><strong>Path:</strong> ${escapeHtml(source.localPath)}</p>`);
            break;
        case 'REMOTE_FILESYSTEM':
            details.push(`<p><strong>Server:</strong> ${escapeHtml(source.server)}/${escapeHtml(source.shareName)}${source.path ? '/' + escapeHtml(source.path) : ''}</p>`);
            break;
        case 'S3_BUCKET':
            details.push(`<p><strong>Bucket:</strong> ${escapeHtml(source.nameOrUrl)}${source.path ? '/' + escapeHtml(source.path) : ''}</p>`);
            if (source.region) {
                details.push(`<p><strong>Region:</strong> ${escapeHtml(source.region)}</p>`);
            }
            break;
    }
    
    if (source.skipFiles) {
        details.push(`<p><strong>Skip Files:</strong> ${escapeHtml(source.skipFiles)}</p>`);
    }
    
    if (source.dateLastScanned) {
        details.push(`<p><strong>Scanned:</strong> ${formatDate(source.dateLastScanned)}</p>`);
    }
    
    return details.join('');
}

/**
 * Trigger a scan for a data source
 */
async function triggerScan(name, type) {
    if (!await showConfirm(`Start scanning "${name}"? This may take a while.`)) {
        return;
    }
    
    try {
        await scanDataSource(name, type);
        await showAlert('Scan started successfully');
        await loadDataSources(); // This will restart subscriptions
    } catch (error) {
        await showAlert('Failed to start scan: ' + error.message);
    }
}

/**
 * Prompt for deletion
 */
function promptDelete(name, type) {
    sourceToDelete = { name, type };
    $('deleteSourceName').textContent = name;
    $('deleteModal').classList.add('show');
}

/**
 * Close delete modal
 */
function closeDeleteModal() {
    $('deleteModal').classList.remove('show');
    sourceToDelete = null;
}

/**
 * Confirm deletion
 */
async function confirmDelete() {
    if (!sourceToDelete) return;
    
    try {
        $('confirmDeleteBtn').disabled = true;
        $('confirmDeleteBtn').textContent = 'Deleting...';
        
        await deleteDataSource(sourceToDelete.name, sourceToDelete.type);
        closeDeleteModal();
        await loadDataSources(); // This will restart subscriptions
        
    } catch (error) {
        await showAlert('Failed to delete data source: ' + error.message);
    } finally {
        $('confirmDeleteBtn').disabled = false;
        $('confirmDeleteBtn').textContent = 'Delete';
    }
}

/**
 * Show/hide loading state
 */
function showLoading(loading) {
    $('loadingState').style.display = loading ? 'flex' : 'none';
}

/**
 * Show error message
 */
function showError(message) {
    $('errorState').style.display = 'block';
    $('errorState').textContent = message;
}

// Format data source type for display
function formatType(type) {
    const types = {
        'LOCAL_FILESYSTEM': 'Local',
        'REMOTE_FILESYSTEM': 'SMB/Remote',
        'S3_BUCKET': 'S3 Bucket'
    };
    return types[type] || type;
}

// Format status badge for display
function formatStatus(status) {
    const statusConfig = {
        'IDLE': { label: 'Idle', class: 'status-idle' },
        'SCANNING': { label: 'Scanning', class: 'status-scanning' }
    };
    const config = statusConfig[status] || { label: status, class: 'status-unknown' };
    return `<span class="status-badge ${config.class}">${escapeHtml(config.label)}</span>`;
}

// Expose functions to global scope for inline onclick handlers
window.triggerScan = triggerScan;
window.promptDelete = promptDelete;
window.closeDeleteModal = closeDeleteModal;
