/**
 * Remote access URL utilities for EnterFind
 * Resolves correct URLs when running behind the remote access tunnel.
 * Each remotely reached instance has its own origin, <serverId>.remote-<stage>.enterfind.com,
 * with the app at the root exactly as on a local install.
 */

// A legacy-shaped URL (remote-<stage>.enterfind.com/<serverId>/...) shares one origin with every
// other instance. Move to this instance's own origin, keeping the rest of the URL byte for byte,
// and stop: the throw fails every module that imports this one, so no page code runs here.
const legacy = window.location.hostname.match(/^remote-(.+)\.enterfind\.com$/);
if (legacy) {
    const id = window.location.pathname.split('/')[1];
    if (/^[0-9a-f]{12}$/.test(id)) {
        const rest = window.location.pathname.slice(id.length + 1) || '/';
        window.location.replace(`https://${id}.remote-${legacy[1]}.enterfind.com${rest}`
            + window.location.search + window.location.hash);
        throw new Error('Redirecting to the per-server host');
    }
}

/**
 * Returns remote context if the page is being served via the remote access tunnel,
 * or null when running locally.
 * @returns {{ stage: string, serverId: string } | null}
 */
export function getRemoteContext() {
    const match = window.location.hostname.match(/^([0-9a-f]{12})\.remote-(.+)\.enterfind\.com$/);
    return match ? { stage: match[2], serverId: match[1] } : null;
}

/**
 * Resolves an absolute path. The app runs at the root of its origin both locally and remotely,
 * so the path is returned unchanged.
 * @param {string} path - Absolute path starting with '/'
 * @returns {string}
 */
export function getUrl(path) {
    return path;
}

/**
 * Returns the GraphQL endpoint URL.
 * @returns {string}
 */
export function getApiUrl() {
    return getUrl('/graphql');
}

/**
 * Returns the login page URL.
 * @returns {string}
 */
export function getLoginUrl() {
    return getUrl('/login.html');
}

/**
 * Returns the WebSocket URL for GraphQL subscriptions.
 * When remote, points to the remote WebSocket gateway; otherwise uses the current host.
 * @returns {string}
 */
export function buildWebSocketUrl() {
    const ctx = getRemoteContext();
    if (ctx) return `wss://ws-remote-${ctx.stage}.enterfind.com`;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}/graphql`;
}
