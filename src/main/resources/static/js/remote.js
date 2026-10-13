/**
 * Remote access URL utilities for EnterFind
 * Resolves correct URLs when running behind the remote access tunnel.
 * On remote hostnames (remote-<stage>.enterfind.com), all paths are prefixed with /<serverId>/.
 */

/**
 * Returns remote context if the page is being served via the remote access tunnel,
 * or null when running locally.
 * @returns {{ stage: string, serverId: string } | null}
 */
export function getRemoteContext() {
    const match = window.location.hostname.match(/^remote-(.+)\.enterfind\.com$/);
    if (!match) return null;
    const stage = match[1];
    const serverId = window.location.pathname.split('/').filter(Boolean)[0];
    return serverId ? { stage, serverId } : null;
}

/**
 * Resolves an absolute path, prepending the server ID prefix when in remote mode.
 * @param {string} path - Absolute path starting with '/'
 * @returns {string}
 */
export function getUrl(path) {
    const ctx = getRemoteContext();
    return ctx ? `/${ctx.serverId}${path}` : path;
}

/**
 * Returns the GraphQL endpoint URL, prefixed with the server ID when remote.
 * @returns {string}
 */
export function getApiUrl() {
    return getUrl('/graphql');
}

/**
 * Returns the login page URL, prefixed with the server ID when remote.
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
