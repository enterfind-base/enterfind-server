/**
 * EnterFind Utility Functions
 */

import { getServerTimezone } from './api.js';
import { getAuthToken } from './auth.js';
import { getUrl } from './remote.js';

// Helper function to get elements by ID
export const $ = id => document.getElementById(id);

/**
 * MIME types that browsers can render natively in an <img> tag.
 * Used to decide whether to serve the original file or fall back to a JPEG preview.
 */
export const BROWSER_IMAGE_TYPES = new Set([
    'image/jpeg', 'image/jpg', 'image/png', 'image/gif',
    'image/webp', 'image/svg+xml', 'image/bmp', 'image/avif',
    'image/x-icon', 'image/vnd.microsoft.icon'
]);

// Escape HTML to prevent XSS. Quotes are escaped too, so the result is safe inside quoted
// attributes as well as text. It is NOT safe inside inline JS (onclick="..."): the browser decodes
// entities before running the handler, so pass values via data-* attributes instead.
const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export function escapeHtml(text) {
    if (text === null || text === undefined || text === '') return '';
    return String(text).replace(/[&<>"']/g, ch => HTML_ESCAPES[ch]);
}

// Format date string for display in client's local timezone
// Server sends dates as LocalDateTime strings without timezone (e.g., "2026-01-21T14:30:45")
// We interpret them using the server's timezone, then convert to client's local timezone
export function formatDate(dateString) {
    if (!dateString) return 'N/A';
    try {
        // Parse the date string as if it's in the server's timezone
        // Since we can't use async here, we use the cached value from getServerTimezone()
        // (fetchServerTimezone should be called on page load)
        const tz = getServerTimezone() || 'UTC';
        
        // For UTC, append 'Z'; for other timezones, we need to convert
        let date;
        if (tz === 'UTC' || tz === 'Etc/UTC') {
            // Append 'Z' to indicate UTC
            const utcString = dateString.endsWith('Z') ? dateString : dateString + 'Z';
            date = new Date(utcString);
        } else {
            // Parse as ISO string and interpret as server timezone
            // JavaScript doesn't natively support parsing in arbitrary timezones,
            // so we use a workaround: format the string with timezone info
            date = new Date(dateString + 'Z'); // For now, still treat as UTC
            // TODO: Proper timezone conversion if server is not UTC
        }
        
        // Use Intl.DateTimeFormat for explicit timezone-aware formatting
        // This ensures dates are displayed in the client's local timezone.
        // Numeric date in the browser locale's own order and clock, seconds kept
        const options = {
            dateStyle: 'short',
            timeStyle: 'medium'
        };
        return new Intl.DateTimeFormat(undefined, options).format(date);
    } catch {
        return dateString;
    }
}

// Format file size in bytes to human readable format
export function formatFileSize(bytes) {
    if (bytes === null || bytes === undefined) return 'N/A';
    if (bytes === 0) return '0 B';
    
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let size = bytes;
    let unitIndex = 0;
    
    while (size >= 1024 && unitIndex < units.length - 1) {
        size /= 1024;
        unitIndex++;
    }
    
    return `${size.toFixed(1)} ${units[unitIndex]}`;
}

/**
 * Build a preview URL for an item. Encodes datasource name and each path segment.
 * @param {string} dsType
 * @param {string} dsName
 * @param {string} folder - forward-slash separated folder path
 * @param {string} name - item name
 * @returns {string} - URL path to preview endpoint
 */
export function buildPreviewUrl(dsType, dsName, folder, name) {
    const segments = folder ? folder.split('/').concat([name]) : [name];
    const encodedPath = segments.map(s => encodeURIComponent(s)).join('/');
    const token = getAuthToken();
    const query = token ? `?t=${encodeURIComponent(token)}` : '';
    return getUrl(`/preview/${encodeURIComponent(dsType || '')}/${encodeURIComponent(dsName || '')}/${encodedPath}${query}`);
}

/**
 * Build a download URL for an item. Encodes datasource name and each path segment.
 * @param {string} dsType
 * @param {string} dsName
 * @param {string} folder - forward-slash separated folder path
 * @param {string} name - item name
 * @returns {string} - URL path to download endpoint
 */
export function buildDownloadUrl(dsType, dsName, folder, name) {
    const segments = folder ? folder.split('/').concat([name]) : [name];
    const encodedPath = segments.map(s => encodeURIComponent(s)).join('/');
    const token = getAuthToken();
    const query = token ? `?t=${encodeURIComponent(token)}` : '';
    return getUrl(`/content/${encodeURIComponent(dsType || '')}/${encodeURIComponent(dsName || '')}/${encodedPath}${query}`);
}

/**
 * Build a detected face crop URL for an item. Encodes datasource name, each path segment, and the
 * face's index within the item.
 * @param {string} dsType - Datasource type (e.g., "LOCAL_FILESYSTEM")
 * @param {string} dsName - Datasource name
 * @param {string} folder - forward-slash separated folder path
 * @param {string} name - item name
 * @param {number} faceIndex - 0-based index of the face within the item
 * @returns {string} - URL path to detected-face endpoint
 */
export function buildFaceCropUrl(dsType, dsName, folder, name, faceIndex) {
    const segments = folder ? folder.split('/').concat([name, String(faceIndex)]) : [name, String(faceIndex)];
    const encodedPath = segments.map(s => encodeURIComponent(s)).join('/');
    // Normalize dsType to lowercase with dashes (e.g., LOCAL_FILESYSTEM -> local-filesystem)
    const normalizedType = (dsType || '').toLowerCase().replace(/_/g, '-');
    const token = getAuthToken();
    const query = token ? `?t=${encodeURIComponent(token)}` : '';
    return getUrl(`/detected-face/${encodeURIComponent(normalizedType)}/${encodeURIComponent(dsName || '')}/${encodedPath}${query}`);
}

/**
 * Custom Modal Dialog System
 * Replaces browser's alert(), confirm(), and prompt() with centered custom modals
 */

let modalContainer = null;

function ensureModalContainer() {
    if (!modalContainer) {
        modalContainer = document.createElement('div');
        modalContainer.id = 'custom-modal-container';
        document.body.appendChild(modalContainer);
    }
    return modalContainer;
}

function createModal(content, buttons) {
    const container = ensureModalContainer();
    
    const overlay = document.createElement('div');
    overlay.className = 'custom-modal-overlay';
    
    const modal = document.createElement('div');
    modal.className = 'custom-modal-dialog';
    
    const modalContent = document.createElement('div');
    modalContent.className = 'custom-modal-content';
    modalContent.innerHTML = content;
    
    const modalButtons = document.createElement('div');
    modalButtons.className = 'custom-modal-buttons';
    
    buttons.forEach(btn => {
        const button = document.createElement('button');
        button.className = `btn ${btn.className || 'btn-secondary'}`;
        button.textContent = btn.label;
        button.onclick = () => {
            if (btn.callback) btn.callback();
            closeModal(overlay);
        };
        modalButtons.appendChild(button);
    });
    
    modal.appendChild(modalContent);
    modal.appendChild(modalButtons);
    overlay.appendChild(modal);
    container.appendChild(overlay);
    
    // Focus first button
    setTimeout(() => {
        const firstButton = modalButtons.querySelector('button');
        if (firstButton) firstButton.focus();
    }, 100);
    
    return overlay;
}

function closeModal(overlay) {
    if (overlay && overlay.parentNode) {
        overlay.parentNode.removeChild(overlay);
    }
}

/**
 * Show an alert message
 * @param {string} message - The message to display
 * @returns {Promise<void>}
 */
export function showAlert(message) {
    return new Promise(resolve => {
        const content = `<p>${escapeHtml(message).replace(/\n/g, '<br>')}</p>`;
        createModal(content, [
            { label: 'OK', className: 'btn-primary', callback: resolve }
        ]);
    });
}

/**
 * Show a confirmation dialog
 * @param {string} message - The message to display
 * @returns {Promise<boolean>} - true if confirmed, false if cancelled
 */
export function showConfirm(message) {
    return new Promise(resolve => {
        const content = `<p>${escapeHtml(message).replace(/\n/g, '<br>')}</p>`;
        createModal(content, [
            { label: 'Cancel', className: 'btn-secondary', callback: () => resolve(false) },
            { label: 'OK', className: 'btn-primary', callback: () => resolve(true) }
        ]);
    });
}

/**
 * Show a prompt dialog
 * @param {string} message - The message to display
 * @param {string} defaultValue - The default input value
 * @returns {Promise<string|null>} - the entered value or null if cancelled
 */
export function showPrompt(message, defaultValue = '') {
    return new Promise(resolve => {
        let resolved = false;
        const inputId = 'modal-prompt-input-' + Date.now();
        const content = `
            <p>${escapeHtml(message).replace(/\n/g, '<br>')}</p>
            <input type="text" id="${inputId}" class="form-input" value="${escapeHtml(defaultValue)}" style="margin-top: 1rem; width: 100%;">
        `;

        function doResolve(val, overlay) {
            if (!resolved) {
                resolved = true;
                closeModal(overlay);
                resolve(val);
            }
        }

        const overlay = createModal(content, [
            { label: 'Cancel', className: 'btn-secondary', callback: () => doResolve(null, overlay) },
            {
                label: 'OK',
                className: 'btn-primary',
                callback: () => {
                    const input = document.getElementById(inputId);
                    doResolve(input ? input.value : null, overlay);
                }
            }
        ]);

        // Focus the input and select text
        setTimeout(() => {
            const input = document.getElementById(inputId);
            if (input) {
                input.focus();
                input.select();
                // Submit on Enter key
                input.addEventListener('keypress', (e) => {
                    if (e.key === 'Enter') {
                        doResolve(input.value, overlay);
                    }
                });
            }
        }, 100);
    });
}

/**
 * Format expiration days for display
 * @param {number} days - Number of days
 * @returns {string} - Formatted string
 */
export function formatExpirationDays(days) {
    if (days === 1) return '1 day';
    if (days === 7) return '1 week';
    if (days === 30) return '1 month';
    if (days === 120) return '4 months';
    if (days === 365) return '1 year';
    if (days === 730) return '2 years';
    return `${days} days`;
}

/**
 * Get available API key expiration options
 * @returns {Array} - Array of {value, label} objects
 */
export function getExpirationOptions() {
    return [
        { value: 1, label: '1 day' },
        { value: 7, label: '1 week' },
        { value: 30, label: '1 month' },
        { value: 120, label: '4 months' },
        { value: 365, label: '1 year' },
        { value: 730, label: '2 years' }
    ];
}
