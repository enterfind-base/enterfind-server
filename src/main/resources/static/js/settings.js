import {
    getAbout,
    getAiSettings,
    getFaceEmbeddingModel,
    getRemoteAccessConfig,
    getUserSettings,
    setAiSetting,
    setFaceEmbeddingModel,
    setFaceGroupingThreshold,
    setRemoteAccessEnabled,
    setUserSetting,
    subscribeToRemoteAccessConfig,
    testAiConnection
} from './api.js';
import { checkAuthAndRedirect } from './auth.js';
import { initFooter } from './footer.js';
import { $, escapeHtml, showAlert, showConfirm } from './util.js';

let serverEnvironment = null;
let unsubscribeRemoteAccess = null;
// The Server ID as last rendered, for the confirmation before an invite code replaces it.
let currentServerId = null;

document.addEventListener('DOMContentLoaded', async () => {
    // Auth check
    await checkAuthAndRedirect();
    initFooter();

    // About info
    try {
        const about = await getAbout();
        renderAbout(about);
    } catch (error) {
        console.error('Failed to load about info:', error);
        $('aboutLoading').innerHTML =
            '<p class="alert alert-error">Failed to load application info</p>';
    }

    // Settings
    try {
        const [settings, aiSettings] = await Promise.all([getUserSettings(), getAiSettings()]);
        renderSettingsTable(settings);
        renderAiSettings(aiSettings);
    } catch (error) {
        console.error('Failed to load settings:', error);
        // Check if this is a permission error
        if (error.message && (error.message.includes('Insufficient permissions') || error.message.includes('permission'))) {
            showAccessDenied('You do not have permission to view settings. Admin access is required.');
        } else {
            $('settingsLoading').style.display = 'none';
            $('settingsError').style.display = 'block';
            $('settingsError').innerHTML = `<p class="alert alert-error">Failed to load settings: ${escapeHtml(error.message || 'Unknown error')}</p>`;
        }
    }

    // Face recognition model card
    try {
        await loadFaceModel();
    } catch (error) {
        console.error('Failed to load face model settings:', error);
    }
    wireFaceModelCard();

    // Test AI connection button
    $('testAiBtn').addEventListener('click', async () => {
        const btn = $('testAiBtn');
        const resultSpan = $('testAiResult');

        btn.disabled = true;
        btn.textContent = 'Testing...';
        resultSpan.textContent = '';

        try {
            const success = await testAiConnection();
            if (success) {
                resultSpan.innerHTML = '<span style="color: green;">✓ Connection successful</span>';
            } else {
                resultSpan.innerHTML = '<span style="color: red;">✗ Connection failed</span>';
            }
        } catch (error) {
            resultSpan.innerHTML = `<span style="color: red;">✗ Error: ${escapeHtml(error.message || 'Unknown error')}</span>`;
        } finally {
            btn.disabled = false;
            btn.textContent = 'Test Connection';
        }
    });

    // Auto-save provider when selection changes
    $('aiProviderSelect').addEventListener('change', async (event) => {
        const select = event.target;
        const resultSpan = $('saveProviderResult');

        select.disabled = true;
        resultSpan.innerHTML = '<span style="color: #999;">Saving...</span>';

        try {
            await setAiSetting('AiProvider', select.value);
            resultSpan.innerHTML = '<span style="color: green;">✓ Saved</span>';
            // Update the visible provider settings
            updateVisibleProviderSettings(select.value);
            setTimeout(() => { resultSpan.textContent = ''; }, 2000);
        } catch (error) {
            resultSpan.innerHTML = `<span style="color: red;">✗ Error: ${escapeHtml(error.message || 'Unknown error')}</span>`;
        } finally {
            select.disabled = false;
        }
    });

    // Remote Access config — load initial state immediately, then subscribe for live updates
    try {
        const config = await getRemoteAccessConfig();
        renderRemoteAccessConfig(config);
    } catch (error) {
        console.error('[remoteAccess] Failed to load initial config:', error);
        $('remoteAccessLoading').innerHTML = '<p class="alert alert-error">Failed to load remote access configuration</p>';
    }

    unsubscribeRemoteAccess = subscribeToRemoteAccessConfig(
        (config) => {
            console.log('[remoteAccess] Rendering config from subscription:', config);
            renderRemoteAccessConfig(config);
        },
        (err) => console.warn('[remoteAccess] Subscription error:', err)
    );

    $('remoteAccessToggle').addEventListener('change', async (event) => {
        const checkbox = event.target;
        const prevChecked = !checkbox.checked;
        checkbox.disabled = true;
        // Never sends the invite code field: off/on must keep the Server ID, and text left in the
        // field must not be able to re-enroll the instance by accident.
        try {
            const config = await setRemoteAccessEnabled(checkbox.checked);
            renderRemoteAccessConfig(config);
        } catch (error) {
            checkbox.checked = prevChecked;
            await showAlert('Failed to update remote access: ' + (error?.message || error));
        } finally {
            checkbox.disabled = false;
        }
    });

    window.addEventListener('beforeunload', () => {
        if (unsubscribeRemoteAccess) unsubscribeRemoteAccess();
    });

    $('useInviteCodeBtn').addEventListener('click', async () => {
        const inviteCode = readInviteCode();
        if (!inviteCode) return;
        if (currentServerId) {
            const confirmed = await showConfirm(
                `If this code is accepted, it replaces Server ID ${currentServerId} and its URL, and AI `
                + 'connectors using the old URL will stop working. If it is not accepted, this instance '
                + 'keeps its current Server ID.');
            if (!confirmed) return;
        }
        const btn = $('useInviteCodeBtn');
        btn.disabled = true;
        try {
            const config = await setRemoteAccessEnabled(true, inviteCode);
            // The code is one-shot: success or failure arrives over the subscription, and a failed
            // attempt is retried by entering it again, never from here.
            $('remoteInviteCode').value = '';
            renderRemoteAccessConfig(config);
        } catch (error) {
            await showAlert('Failed to use the invite code: ' + (error?.message || error));
        } finally {
            btn.disabled = false;
        }
    });

    $('copyServerIdBtn').addEventListener('click', () => {
        const serverId = $('remoteServerId').textContent;
        if (serverId) {
            navigator.clipboard.writeText(serverId).catch(err => console.error('Copy failed:', err));
        }
    });

    $('copyMcpUrlBtn').addEventListener('click', () => {
        const url = $('remoteMcpUrl').textContent;
        if (url) {
            navigator.clipboard.writeText(url).catch(err => console.error('Copy failed:', err));
        }
    });

    $('copyChatGptUrlBtn').addEventListener('click', () => {
        const url = $('remoteChatGptUrl').textContent;
        if (url) {
            navigator.clipboard.writeText(url).catch(err => console.error('Copy failed:', err));
        }
    });
});

/** Reads the invite code field, or null when it is empty. */
function readInviteCode() {
    return $('remoteInviteCode').value.trim() || null;
}

function renderRemoteAccessConfig(config) {
    $('remoteAccessLoading').style.display = 'none';
    $('remoteAccessContent').style.display = 'block';

    $('remoteAccessToggle').checked = config.enabled;

    const status = $('remoteAccessStatus');
    if (!config.enabled) {
        status.textContent = 'Disabled';
        status.style.color = 'var(--text-muted)';
    } else if (config.isConnected) {
        status.textContent = 'Connected';
        status.style.color = 'green';
    } else {
        status.textContent = 'Disconnected';
        status.style.color = 'orange';
    }

    const msgRow = $('remoteAccessStatusMessageRow');
    if (config.statusMessage) {
        $('remoteAccessStatusMessageText').textContent = config.statusMessage;
        msgRow.style.display = '';
    } else {
        msgRow.style.display = 'none';
    }

    currentServerId = config.serverId ?? null;
    $('remoteServerId').textContent = config.serverId ?? 'Not enrolled';

    const urlRow = $('remoteAccessUrlRow');
    const mcpUrlRow = $('remoteMcpUrlRow');
    const chatGptUrlRow = $('remoteChatGptUrlRow');
    const graphiqlUrlRow = $('remoteGraphiqlUrlRow');
    if (config.enabled && serverEnvironment && config.serverId) {
        const base = `https://remote-${serverEnvironment}.enterfind.com/${config.serverId}`;
        const url = `${base}/`;
        $('remoteAccessUrl').href = url;
        $('remoteAccessUrl').textContent = url;
        urlRow.style.display = '';

        $('remoteMcpUrl').textContent = `${base}/mcp`;
        mcpUrlRow.style.display = '';

        $('remoteChatGptUrl').textContent = `${base}/openapi.json`;
        chatGptUrlRow.style.display = '';

        const graphiqlUrl = `${base}/graphiql?path=/${config.serverId}/graphql&wsPath=/${config.serverId}/graphql`;
        $('remoteGraphiqlUrl').href = graphiqlUrl;
        $('remoteGraphiqlUrl').textContent = graphiqlUrl;
        graphiqlUrlRow.style.display = '';
    } else {
        urlRow.style.display = 'none';
        mcpUrlRow.style.display = 'none';
        chatGptUrlRow.style.display = 'none';
        graphiqlUrlRow.style.display = 'none'; // remote graphiql hidden; local graphiql always visible
    }
}

function showAccessDenied(message) {
    $('errorState').style.display = 'block';
    $('errorState').textContent = message;
    $('contentContainer').style.display = 'none';
}

function renderAbout(about) {
    serverEnvironment = about.environment;
    $('aboutLoading').style.display = 'none';
    $('aboutContent').style.display = 'grid';
    const fields = [
        { label: 'Application', value: about.name },
        { label: 'Version', value: about.version },
        { label: 'Description', value: about.description },
        { label: 'Environment', value: about.environment }
    ];
    $('aboutContent').innerHTML = fields.map(f => `
        <div class="detail-label">${f.label}</div>
        <div class="detail-value">${escapeHtml(f.value || 'N/A')}</div>
    `).join('');
}

function renderSettingsTable(settings) {
    $('settingsLoading').style.display = 'none';
    $('settingsTableContainer').style.display = 'block';
    const tbody = $('settingsTable').querySelector('tbody');
    tbody.innerHTML = '';

    if (!settings || settings.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" style="text-align:center;">No settings found</td></tr>';
        return;
    }

    for (const setting of settings) {
        renderSettingRow(tbody, setting);
    }
}

function renderAiSettings(settings) {
    $('aiSettingsLoading').style.display = 'none';
    $('aiSettingsContainer').style.display = 'block';

    // Get current provider
    const currentProvider = settings.find(s => s.settingName === 'AiProvider');
    const providerValue = currentProvider ? currentProvider.value : 'OLLAMA';

    // Set provider dropdown
    $('aiProviderSelect').value = providerValue;

    // Render Ollama settings
    const ollamaSettings = ['OllamaApiUrl', 'OllamaModelName', 'OllamaApiCode'].map(settingName => {
        const existing = settings.find(s => s.settingName === settingName);
        return existing || { settingName, value: '' };
    });
    const ollamaBody = $('ollamaSettingsTable');
    ollamaBody.innerHTML = '';
    for (const setting of ollamaSettings) {
        renderSettingRow(ollamaBody, setting, setting.settingName === 'OllamaApiCode', setAiSetting);
    }

    // Render OpenAI settings
    const openaiSettings = ['OpenAiApiKey', 'OpenAiModelName', 'OpenAiBaseUrl'].map(settingName => {
        const existing = settings.find(s => s.settingName === settingName);
        return existing || { settingName, value: '' };
    });
    const openaiBody = $('openaiSettingsTable');
    openaiBody.innerHTML = '';
    for (const setting of openaiSettings) {
        renderSettingRow(openaiBody, setting, setting.settingName === 'OpenAiApiKey', setAiSetting);
    }

    // Render Anthropic settings
    const anthropicSettings = ['AnthropicApiKey', 'AnthropicModelName', 'AnthropicBaseUrl'].map(settingName => {
        const existing = settings.find(s => s.settingName === settingName);
        return existing || { settingName, value: '' };
    });
    const anthropicBody = $('anthropicSettingsTable');
    anthropicBody.innerHTML = '';
    for (const setting of anthropicSettings) {
        renderSettingRow(anthropicBody, setting, setting.settingName === 'AnthropicApiKey', setAiSetting);
    }

    // Show appropriate provider settings
    updateVisibleProviderSettings(providerValue);
}

function updateVisibleProviderSettings(provider) {
    // Hide all provider settings
    document.querySelectorAll('.provider-settings').forEach(el => el.style.display = 'none');

    // Show selected provider settings
    if (provider === 'OLLAMA') {
        $('ollamaSettings').style.display = 'block';
    } else if (provider === 'OPENAI') {
        $('openaiSettings').style.display = 'block';
    } else if (provider === 'ANTHROPIC') {
        $('anthropicSettings').style.display = 'block';
    }
}

// ================= Face recognition model card =================

// Reveal the affirmation (built-in) or URL field (custom) for the chosen mode. Purely presentational.
function updateVisibleFaceModelSettings(mode) {
    $('faceModelBuiltin').style.display = mode === 'BUILTIN' ? 'block' : 'none';
    $('faceModelCustom').style.display = mode === 'CUSTOM' ? 'block' : 'none';
}

// Reflect a status the server returned. Everything the card shows comes from here rather than from
// the form, so a rejected or adjusted value can never be left displayed as though it took.
function renderFaceModelStatus(status) {
    $('faceModelSelect').value = status.mode;
    $('faceModelUrl').value = status.url || '';
    $('faceModelAck').checked = false; // affirmation is per-selection, never remembered
    const count = status.enabledDatasourceCount;
    $('faceModelEnabledCount').textContent = count > 0
        ? `${count} datasource${count === 1 ? '' : 's'} use face recognition — changing the model re-groups all of them`
        : '';

    // The threshold only means something once a model defines the cosine scale it is measured on.
    $('faceThresholdRow').style.display = status.mode === 'NONE' ? 'none' : 'block';
    $('faceThresholdInput').value = status.threshold;
    const derived = status.derivedThreshold;
    $('faceThresholdDerived').innerHTML = Math.abs(derived - status.threshold) < 1e-9
        ? `This model's own measurements imply <strong>${derived}</strong>, which is what is in force.`
        : `This model's own measurements imply <strong>${derived}</strong>. `
          + `<a href="#" id="faceThresholdReset">Reset to it</a>.`;
    const resetLink = $('faceThresholdReset');
    if (resetLink) {
        resetLink.addEventListener('click', (e) => {
            e.preventDefault();
            $('faceThresholdInput').value = derived;
        });
    }

    updateVisibleFaceModelSettings(status.mode);
}

// Read current state from the server and reflect it in the card. Also the reload-after-error path:
// on a rejected set we re-read so the card never shows a mode the instance did not accept.
async function loadFaceModel() {
    const status = await getFaceEmbeddingModel();
    $('faceModelLoading').style.display = 'none';
    $('faceModelContainer').style.display = 'block';
    renderFaceModelStatus(status);
}

function wireFaceModelCard() {
    $('faceModelSelect').addEventListener('change', (e) => {
        updateVisibleFaceModelSettings(e.target.value);
        $('faceModelResult').textContent = '';
    });

    $('faceThresholdSaveBtn').addEventListener('click', async () => {
        const btn = $('faceThresholdSaveBtn');
        const resultSpan = $('faceThresholdResult');
        const threshold = parseFloat($('faceThresholdInput').value);
        if (!Number.isFinite(threshold)) {
            resultSpan.innerHTML = '<span style="color:red;">✗ Enter a number</span>';
            return;
        }

        const count = parseInt($('faceModelEnabledCount').textContent, 10) || 0;
        const confirmed = await showConfirm(
            `Re-group every face at threshold ${threshold}?`
            + (count > 0 ? ` This rebuilds the groups across ${count} datasource${count === 1 ? '' : 's'}.` : '')
            + ' Names you have assigned are carried over where the new grouping still supports them.');
        if (!confirmed) {
            return;
        }

        btn.disabled = true;
        resultSpan.innerHTML = '<span style="color:#999;">Re-grouping…</span>';
        try {
            renderFaceModelStatus(await setFaceGroupingThreshold(threshold));
            resultSpan.innerHTML = '<span style="color:green;">✓ Re-grouped</span>';
        } catch (error) {
            // Rejected (out of range, or a drain in flight): nothing changed, so show why and put
            // the value in force back in the field.
            resultSpan.innerHTML = `<span style="color:red;">✗ ${escapeHtml(error.message || 'Failed')}</span>`;
            try { await loadFaceModel(); } catch (e) { /* leave the error visible */ }
        } finally {
            btn.disabled = false;
        }
    });

    $('faceModelSaveBtn').addEventListener('click', async () => {
        const btn = $('faceModelSaveBtn');
        const resultSpan = $('faceModelResult');
        const mode = $('faceModelSelect').value;
        const url = $('faceModelUrl').value.trim();
        const ack = $('faceModelAck').checked;

        btn.disabled = true;
        resultSpan.innerHTML = '<span style="color:#999;">Saving…</span>';
        try {
            const status = await setFaceEmbeddingModel(mode, mode === 'CUSTOM' ? url : null, ack);
            resultSpan.innerHTML = '<span style="color:green;">✓ Saved</span>';
            // Authoritative state, not the form: a new model brings its own derived threshold.
            renderFaceModelStatus(status);
        } catch (error) {
            // Nothing was applied; the previous selection stands. Re-read so the card cannot show a
            // mode the instance did not accept, and surface the probe's numbers from the message.
            resultSpan.innerHTML = `<span style="color:red;">✗ ${escapeHtml(error.message || 'Failed')}</span>`;
            try { await loadFaceModel(); } catch (e) { /* leave the error visible */ }
        } finally {
            btn.disabled = false;
        }
    });
}

function renderSettingRow(tbody, setting, isPassword = false, saveSetting = setUserSetting) {
    const tr = document.createElement('tr');
    // Detect if value is numeric (integer or float)
    const isNumeric = /^-?\d+(\.\d+)?$/.test(setting.value);
    const inputType = isPassword ? 'password' : (isNumeric ? 'number' : 'text');
    const displayValue = setting.value || '';
    // Sensitive keys come back redacted (empty) from the server, so the box is always blank.
    // Signal that a value may already be stored and that submitting leaves it untouched.
    const placeholder = isPassword ? '**** enter to replace' : '';

    tr.innerHTML = `
        <td style="padding-right: 2rem; vertical-align: middle; width: 30%;">${escapeHtml(setting.settingName)}</td>
        <td style="padding-right: 2rem; vertical-align: middle;">
            <input type="${inputType}" value="${escapeHtml(displayValue)}" placeholder="${placeholder}" data-setting="${escapeHtml(setting.settingName)}" style="width: 100%; padding: 0.4rem 0.6rem; margin-right: 0.5rem;" />
        </td>
        <td style="vertical-align: middle; width: 15%;">
            <button class="btn btn-primary btn-save" data-setting="${escapeHtml(setting.settingName)}" style="padding: 0.4rem 1.2rem;">Save</button>
        </td>
    `;
    tbody.appendChild(tr);

    // Add event listener for save button
    const btn = tr.querySelector('.btn-save');
    btn.addEventListener('click', async (e) => {
        const settingName = btn.dataset.setting;
        const input = tr.querySelector('input');
        const newValue = input.value;
        // A blank sensitive field means "leave the stored key as-is" — never overwrite it with "".
        if (isPassword && newValue === '') {
            btn.textContent = 'Unchanged';
            setTimeout(() => { btn.textContent = 'Save'; }, 1000);
            return;
        }
        btn.disabled = true;
        btn.textContent = 'Saving...';
        try {
            await saveSetting(settingName, newValue);
            btn.textContent = 'Saved!';
            setTimeout(() => { btn.textContent = 'Save'; btn.disabled = false; }, 1000);
        } catch (err) {
            btn.textContent = 'Error';
            btn.disabled = false;
            await showAlert('Failed to update setting: ' + (err?.message || err));
        }
    });
}
