/**
 * Authentication module for EnterFind
 * Handles token storage, auth state management, and API integration
 *
 * Token is stored in localStorage and passed as a GraphQL variable (not in headers).
 * This enables AI introspection of auth requirements and simplifies MCP integration.
 *
 * localStorage is readable by any script on the origin, so the token is only as safe as the
 * pages are from XSS: every value rendered via innerHTML must go through escapeHtml, and never
 * into inline JS. An HttpOnly cookie would remove that dependency, but would also break the
 * token-as-variable design above and the ?t= token on content/preview URLs. CodeQL's
 * js/clear-text-storage-of-sensitive-data alerts on setAuth are dismissed as by-design for this
 * reason.
 *
 * Behind the remote access tunnel every instance shares one origin (remote-<stage>.enterfind.com),
 * and so one localStorage, so the keys are suffixed with the server ID there. Without it, one
 * instance's pages would show another's session and send its token to the wrong server.
 */

import { getApiUrl, getLoginUrl, getRemoteContext } from './remote.js';

const LOCAL_KEYS = ['enterfind_auth_token', 'enterfind_auth_user', 'enterfind_auth_expiry'];
const [AUTH_TOKEN_KEY, AUTH_USER_KEY, AUTH_EXPIRY_KEY] = storageKeys();

/**
 * Resolve the localStorage keys for this instance: the plain keys locally, server-scoped remotely.
 * On a remote origin the plain keys hold a session of unknown ownership left by older pages, so
 * they are dropped rather than migrated.
 * @returns {string[]} - token, user and expiry keys
 */
function storageKeys() {
    const ctx = getRemoteContext();
    if (!ctx) {
        return LOCAL_KEYS;
    }
    LOCAL_KEYS.forEach(key => localStorage.removeItem(key));
    return LOCAL_KEYS.map(key => `${key}:${ctx.serverId}`);
}

/**
 * Get the stored auth token
 * @returns {string|null} - The auth token or null if not logged in
 */
export function getAuthToken() {
    const token = localStorage.getItem(AUTH_TOKEN_KEY);
    const expiry = localStorage.getItem(AUTH_EXPIRY_KEY);

    // Check if token has expired
    if (token && expiry) {
        const expiryDate = new Date(expiry);
        if (expiryDate <= new Date()) {
            // Token expired, clear auth
            clearAuth();
            return null;
        }
    }

    return token;
}

/**
 * Get the stored user info
 * @returns {object|null} - The user object or null if not logged in
 */
export function getAuthUser() {
    const userJson = localStorage.getItem(AUTH_USER_KEY);
    if (userJson) {
        try {
            return JSON.parse(userJson);
        } catch (e) {
            return null;
        }
    }
    return null;
}

/**
 * Check if the user is logged in
 * @returns {boolean} - True if logged in with valid token
 */
export function isLoggedIn() {
    return getAuthToken() !== null;
}

/**
 * Check if the user is an admin
 * @returns {boolean} - True if logged in as admin
 */
export function isAdmin() {
    const user = getAuthUser();
    return user && user.userLevel === 'ADMIN';
}

/**
 * Store auth credentials after login
 * @param {string} token - The auth token
 * @param {object} user - The user object
 * @param {string} expiresAt - Token expiry timestamp
 */
export function setAuth(token, user, expiresAt) {
    localStorage.setItem(AUTH_TOKEN_KEY, token);
    localStorage.setItem(AUTH_USER_KEY, JSON.stringify(user));
    localStorage.setItem(AUTH_EXPIRY_KEY, expiresAt);
}

/**
 * Clear auth credentials on logout
 */
export function clearAuth() {
    localStorage.removeItem(AUTH_TOKEN_KEY);
    localStorage.removeItem(AUTH_USER_KEY);
    localStorage.removeItem(AUTH_EXPIRY_KEY);
}

/**
 * Check if an error is an authentication error that requires redirect to login.
 * This should only return true for actual authentication failures (invalid/expired token),
 * NOT for authorization failures (valid token but insufficient permissions like "Admin access required").
 * @param {object} result - GraphQL response
 * @returns {boolean} - True if auth error detected
 */
export function isAuthError(result) {
    if (!result.errors || result.errors.length === 0) {
        return false;
    }

    // Only authentication errors - NOT authorization errors like "Admin access required"
    const authErrorMessages = [
        'Authentication required',
        'Invalid token',
        'Session expired',
        'Token expired'
    ];

    return result.errors.some(error => {
        const message = error.message || '';
        const classification = error.extensions?.classification || '';

        // Check if error message matches authentication errors
        if (authErrorMessages.some(authMsg => message.toLowerCase().includes(authMsg.toLowerCase()))) {
            return true;
        }

        // Only UNAUTHORIZED means not authenticated, FORBIDDEN means authenticated but not authorized
        if (classification === 'UNAUTHORIZED') {
            return true;
        }

        return false;
    });
}

/**
 * Handle auth errors by clearing credentials and redirecting to login
 * @param {object} result - GraphQL response
 * @returns {boolean} - True if handled (redirecting)
 */
export function handleAuthError(result) {
    if (isAuthError(result)) {
        // Don't redirect if already on login page
        if (!window.location.pathname.includes('login.html')) {
            clearAuth();
            window.location.href = getLoginUrl();
            return true;
        }
    }
    return false;
}

/**
 * Redirect to login page if not authenticated
 * @param {boolean} authRequired - Whether auth is required for the current page
 */
export function requireAuth(authRequired) {
    if (authRequired && !isLoggedIn()) {
        window.location.href = getLoginUrl();
    }
}

/**
 * Check auth status and redirect accordingly
 * Call this on page load to handle auth redirection
 */
export async function checkAuthAndRedirect() {
    try {
        const response = await fetch(getApiUrl(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                query: `query { isAuthenticationRequired }`
            })
        });

        const result = await response.json();
        const authRequired = result.data?.isAuthenticationRequired;

        // If auth is required and we're not logged in, redirect to login
        if (authRequired && !isLoggedIn()) {
            // Don't redirect if already on login page
            if (!window.location.pathname.includes('login.html')) {
                window.location.href = getLoginUrl();
            }
        }

        return authRequired;
    } catch (error) {
        console.error('Failed to check auth status:', error);
        return false;
    }
}

/**
 * Setup auth check for pages that require authentication
 * This should be called in DOMContentLoaded for protected pages
 */
export function setupAuthCheck() {
    checkAuthAndRedirect();
}

/**
 * Perform login
 * @param {string} nickname - User nickname
 * @param {string} totpCode - TOTP code
 * @param {boolean} rememberMe - Whether to remember the session
 * @returns {Promise<object>} - Auth result with token and user
 */
export async function login(nickname, totpCode, rememberMe = false) {
    const mutation = `
        mutation Login($input: LoginInput!) {
            login(input: $input) {
                token
                user {
                    id
                    nickname
                    userLevel
                    isActive
                    recoveryCodesRemaining
                }
                expiresAt
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { input: { nickname, totpCode, rememberMe } }
        })
    });

    const result = await response.json();

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Login failed');
    }

    const authResult = result.data.login;
    setAuth(authResult.token, authResult.user, authResult.expiresAt);

    return authResult;
}

/**
 * Perform login with recovery code
 * @param {string} nickname - User nickname
 * @param {string} recoveryCode - Recovery code
 * @returns {Promise<object>} - Auth result with token and user
 */
export async function loginWithRecoveryCode(nickname, recoveryCode) {
    const mutation = `
        mutation LoginWithRecoveryCode($input: RecoveryLoginInput!) {
            loginWithRecoveryCode(input: $input) {
                token
                user {
                    id
                    nickname
                    userLevel
                    isActive
                    recoveryCodesRemaining
                }
                expiresAt
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { input: { nickname, recoveryCode } }
        })
    });

    const result = await response.json();

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Login failed');
    }

    const authResult = result.data.loginWithRecoveryCode;
    setAuth(authResult.token, authResult.user, authResult.expiresAt);

    return authResult;
}

/**
 * Perform logout
 * @returns {Promise<boolean>} - True if logout was successful
 */
export async function logout() {
    const token = getAuthToken();

    const mutation = `
        mutation Logout($token: String) {
            logout(token: $token)
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { token }
        })
    });

    const result = await response.json();
    clearAuth();

    return result.data?.logout || false;
}

/**
 * Check if authentication is required
 * @returns {Promise<boolean>} - True if auth is required
 */
export async function isAuthenticationRequired() {
    const query = `
        query {
            isAuthenticationRequired
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query })
    });

    const result = await response.json();
    return result.data?.isAuthenticationRequired || false;
}

/**
 * Get current user info
 * @returns {Promise<object|null>} - Current user or null
 */
export async function getCurrentUser() {
    const token = getAuthToken();

    const query = `
        query CurrentUser($token: String) {
            currentUser(token: $token) {
                id
                nickname
                userLevel
                isActive
                recoveryCodesRemaining
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query,
            variables: { token }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        return null;
    }

    return result.data?.currentUser || null;
}

/**
 * Get all human users (admin only)
 * @returns {Promise<Array>} - List of users
 */
export async function getHumanUsers() {
    const token = getAuthToken();

    const query = `
        query GetHumanUsers($token: String) {
            humanUsers(token: $token) {
                id
                nickname
                userLevel
                dateCreated
                dateLastLogin
                isActive
                recoveryCodesRemaining
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query,
            variables: { token }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        throw new Error('Authentication required');
    }

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to get users');
    }

    return result.data?.humanUsers || [];
}

/**
 * Create a new human user (immediately saved to database)
 * @param {string} nickname - User nickname
 * @param {string} userLevel - READER or ADMIN
 * @returns {Promise<object>} - UserCreationResult with user, setupToken, and setupUrl
 */
export async function createHumanUser(nickname, userLevel) {
    const token = getAuthToken();

    const mutation = `
        mutation CreateHumanUser($token: String, $input: CreateHumanUserInput!) {
            createHumanUser(token: $token, input: $input) {
                user {
                    id
                    nickname
                    userLevel
                    isActive
                }
                setupToken
                setupUrl
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { token, input: { nickname, userLevel } }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        throw new Error('Authentication required');
    }

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to create user');
    }

    return result.data.createHumanUser;
}

/**
 * Get user setup info using a one-time setup token
 * @param {string} setupToken - Setup token from URL
 * @returns {Promise<object>} - UserSetupInfo with nickname, qrCodeDataUrl, recoveryCodes, alreadyUsed
 */
export async function getUserSetup(setupToken) {
    const query = `
        query UserSetup($token: String!) {
            userSetup(token: $token) {
                nickname
                qrCodeDataUrl
                totpSecret
                recoveryCodes
                alreadyUsed
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query,
            variables: { token: setupToken }
        })
    });

    const result = await response.json();

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to get setup info');
    }

    return result.data.userSetup;
}

/**
 * Regenerate setup token for an existing user
 * @param {string} userId - User ID
 * @returns {Promise<object>} - UserCreationResult with user, setupToken, and setupUrl
 */
export async function regenerateSetupToken(userId) {
    const token = getAuthToken();

    const mutation = `
        mutation RegenerateSetupToken($token: String, $userId: String!) {
            regenerateSetupToken(token: $token, userId: $userId) {
                user {
                    id
                    nickname
                    userLevel
                    isActive
                }
                setupToken
                setupUrl
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { token, userId }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        throw new Error('Authentication required');
    }

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to regenerate setup token');
    }

    return result.data.regenerateSetupToken;
}

/**
 * Update a human user
 * @param {string} id - User ID
 * @param {object} updates - Updates to apply
 * @returns {Promise<object>} - Updated user
 */
export async function updateHumanUser(id, updates) {
    const token = getAuthToken();

    const mutation = `
        mutation UpdateHumanUser($token: String, $input: UpdateHumanUserInput!) {
            updateHumanUser(token: $token, input: $input) {
                id
                nickname
                userLevel
                isActive
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { token, input: { id, ...updates } }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        throw new Error('Authentication required');
    }

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to update user');
    }

    return result.data.updateHumanUser;
}

/**
 * Delete a human user
 * @param {string} id - User ID
 * @returns {Promise<boolean>} - True if deleted
 */
export async function deleteHumanUser(id) {
    const token = getAuthToken();

    const mutation = `
        mutation DeleteHumanUser($token: String, $id: String!) {
            deleteHumanUser(token: $token, id: $id)
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { token, id }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        throw new Error('Authentication required');
    }

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to delete user');
    }

    return result.data.deleteHumanUser;
}

/**
 * Regenerate TOTP secret for a user
 * @param {string} id - User ID
 * @returns {Promise<object>} - New TOTP setup info
 */
export async function regenerateTotpSecret(id) {
    const token = getAuthToken();

    const mutation = `
        mutation RegenerateTotpSecret($token: String, $id: String!) {
            regenerateTotpSecret(token: $token, id: $id) {
                secret
                qrCodeDataUrl
                recoveryCodes
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { token, id }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        throw new Error('Authentication required');
    }

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to regenerate TOTP');
    }

    return result.data.regenerateTotpSecret;
}

/**
 * Regenerate recovery codes for a user
 * @param {string} id - User ID
 * @returns {Promise<object>} - New recovery codes
 */
export async function regenerateRecoveryCodes(id) {
    const token = getAuthToken();

    const mutation = `
        mutation RegenerateRecoveryCodes($token: String, $id: String!) {
            regenerateRecoveryCodes(token: $token, id: $id) {
                recoveryCodes
            }
        }
    `;

    const response = await fetch(getApiUrl(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            query: mutation,
            variables: { token, id }
        })
    });

    const result = await response.json();

    // Check for auth errors and redirect if needed
    if (handleAuthError(result)) {
        throw new Error('Authentication required');
    }

    if (result.errors) {
        throw new Error(result.errors[0]?.message || 'Failed to regenerate recovery codes');
    }

    return result.data.regenerateRecoveryCodes;
}
