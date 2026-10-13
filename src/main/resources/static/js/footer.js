/**
 * Footer module for EnterFind
 * Displays authentication state and logout link across all pages
 */

import { getAbout } from './api.js';
import { clearAuth, getAuthUser, isLoggedIn, logout } from './auth.js';
import { getLoginUrl } from './remote.js';

const REPO_URL = 'https://github.com/enterfind-base/enterfind-server';

/**
 * Center column: contact, license, and source link
 */
const FOOTER_CENTER = `
                <span class="auth-footer-center" style="justify-self: center; display: flex; align-items: center; gap: 0 0.5rem; flex-wrap: wrap; justify-content: center; max-width: 50vw; color: var(--text-secondary);">
                    <a href="mailto:info@enterfind.com" class="auth-footer-contact">info@enterfind.com</a>
                    <span aria-hidden="true">·</span>
                    <a href="${REPO_URL}/blob/master/LICENSE" class="auth-footer-contact" target="_blank" rel="noopener">AGPLv3</a>
                    <span aria-hidden="true">·</span>
                    <a href="${REPO_URL}" class="auth-footer-contact" target="_blank" rel="noopener">Source</a>
                </span>`;

/**
 * Initialize the auth footer on the page
 * Call this on DOMContentLoaded for all pages except login
 */
export function initFooter() {
    // Don't show footer on login page
    if (window.location.pathname.includes('login.html')) {
        return;
    }

    // Populate server version in navbar
    initNavbarVersion();

    // Create footer element
    const footer = document.createElement('footer');
    footer.className = 'auth-footer';
    footer.id = 'authFooter';

    updateFooterContent(footer);
    document.body.appendChild(footer);
}

/**
 * Update the footer content based on auth state
 */
function updateFooterContent(footer) {
    const user = getAuthUser();
    const loggedIn = isLoggedIn();

    if (loggedIn && user) {
        footer.innerHTML = `
            <div class="auth-footer-content">
                <span class="auth-footer-status">
                    <span class="auth-status-indicator logged-in"></span>
                    Logged in as <strong>${escapeHtml(user.nickname)}</strong>
                    ${user.userLevel === 'ADMIN' ? '<span class="auth-badge admin">Admin</span>' : '<span class="auth-badge reader">Reader</span>'}
                </span>${FOOTER_CENTER}
                <a href="#" class="auth-footer-logout" id="footerLogoutBtn">Logout</a>
            </div>
        `;

        // Attach logout handler
        const logoutBtn = footer.querySelector('#footerLogoutBtn');
        if (logoutBtn) {
            logoutBtn.addEventListener('click', handleLogout);
        }
    } else {
        footer.innerHTML = `
            <div class="auth-footer-content">
                <span class="auth-footer-status">
                    <span class="auth-status-indicator logged-out"></span>
                    Not logged in
                </span>${FOOTER_CENTER}
                <a href="#" class="auth-footer-login" id="footerLoginBtn">Login</a>
            </div>
        `;

        // Attach login handler
        const loginBtn = footer.querySelector('#footerLoginBtn');
        if (loginBtn) {
            loginBtn.addEventListener('click', (e) => { e.preventDefault(); window.location.href = getLoginUrl(); });
        }
    }
}

/**
 * Handle logout click
 */
async function handleLogout(e) {
    e.preventDefault();
    try {
        await logout();
    } catch (err) {
        console.error('Logout failed:', err);
    }
    clearAuth();
    window.location.href = getLoginUrl();
}

/**
 * Populate the server version in the navbar and display the banner if set
 */
async function initNavbarVersion() {
    const versionEl = document.getElementById('server-version');
    try {
        const about = await getAbout();
        if (about?.version && versionEl) {
            versionEl.textContent = about.version;
        }
        if (about?.banner) {
            initNavbarBanner(about.banner);
        }
    } catch {
        // Silently ignore version fetch errors
    }
}

/**
 * Inject the banner element between navbar-left (version) and navbar-nav
 */
function initNavbarBanner(bannerText) {
    if (!bannerText) return;
    const navbar = document.querySelector('nav.navbar .container');
    if (!navbar) return;
    const existing = document.getElementById('navbar-banner');
    if (existing) {
        existing.textContent = bannerText;
        return;
    }
    const bannerEl = document.createElement('div');
    bannerEl.id = 'navbar-banner';
    bannerEl.className = 'navbar-banner';
    bannerEl.textContent = bannerText;
    const navLinks = navbar.querySelector('.navbar-nav');
    if (navLinks) {
        navbar.insertBefore(bannerEl, navLinks);
    } else {
        navbar.appendChild(bannerEl);
    }
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

/**
 * Refresh the footer (call after auth state changes)
 */
export function refreshFooter() {
    const footer = document.getElementById('authFooter');
    if (footer) {
        updateFooterContent(footer);
    }
}
