package enterfind.server.security;

import java.util.Optional;

import org.springframework.stereotype.Component;

import enterfind.server.config.ApplianceMode;
import enterfind.server.model.HumanUser;
import enterfind.server.model.UserLevel;
import enterfind.server.service.AuthenticationService;

/**
 * Utility class for accessing authentication context.
 *
 * Authentication is handled via explicit token parameter passed to GraphQL operations.
 * This enables AI introspection of auth requirements and simplifies MCP integration.
 */
@Component
public class AuthContext {

    private final AuthenticationService authenticationService;
    private final ApplianceMode applianceMode;

    public AuthContext(AuthenticationService authenticationService, ApplianceMode applianceMode) {
        this.authenticationService = authenticationService;
        this.applianceMode = applianceMode;
    }

    /**
     * Check if authentication is required (any users exist).
     */
    public boolean isAuthenticationRequired() {
        return authenticationService.isAuthenticationRequired();
    }

    /**
     * Validate a token and return the associated user.
     *
     * @param token The authentication token
     * @return The authenticated user, if token is valid
     */
    public Optional<HumanUser> validateAndGetUser(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return authenticationService.validateToken(token);
    }

    /**
     * Check if the token has the required permission level.
     * Returns true if:
     * - No authentication is required (no users exist), or
     * - Token is valid and user has the required level
     *
     * @param token The authentication token
     * @param requiredLevel The required permission level
     * @return true if permission is granted
     */
    public boolean hasPermission(String token, UserLevel requiredLevel) {
        if (!isAuthenticationRequired()) {
            return true;
        }

        Optional<HumanUser> user = validateAndGetUser(token);
        if (user.isEmpty()) {
            return false;
        }

        HumanUser currentUser = user.get();
        if (!currentUser.isActive()) {
            return false;
        }

        // ADMIN has all permissions
        if (currentUser.userLevel() == UserLevel.ADMIN) {
            return true;
        }

        // READER can only access READER-level endpoints
        return requiredLevel == UserLevel.READER;
    }

    /**
     * Require the token to have at least READER permission.
     * Throws SecurityException if not authorized.
     *
     * @param token The authentication token
     */
    public void requireReader(String token) {
        requirePermission(token, UserLevel.READER);
    }

    /**
     * Require the token to have ADMIN permission.
     * Throws SecurityException if not authorized.
     *
     * @param token The authentication token
     */
    public void requireAdmin(String token) {
        requirePermission(token, UserLevel.ADMIN);
    }

    /**
     * Require that mutations are allowed (appliance-mode is off) AND that the token
     * has ADMIN permission. Appliance-mode is checked first so it always fires even
     * if a future change weakens the role check.
     *
     * @param token The authentication token
     */
    public void requireMutationAdmin(String token) {
        applianceMode.requireMutationsAllowed();
        requireAdmin(token);
    }

    /**
     * Require the token to have the specified permission level.
     * Throws SecurityException if not authorized.
     *
     * @param token The authentication token
     * @param requiredLevel The required permission level
     */
    public void requirePermission(String token, UserLevel requiredLevel) {
        if (!isAuthenticationRequired()) {
            return; // No auth required, allow access
        }

        Optional<HumanUser> user = validateAndGetUser(token);
        if (user.isEmpty()) {
            throw new SecurityException("Authentication required");
        }

        HumanUser currentUser = user.get();
        if (!currentUser.isActive()) {
            throw new SecurityException("Account is disabled");
        }

        // ADMIN has all permissions
        if (currentUser.userLevel() == UserLevel.ADMIN) {
            return;
        }

        // READER can only access READER-level endpoints
        if (requiredLevel == UserLevel.READER) {
            return;
        }

        throw new SecurityException("Insufficient permissions. Required: " + requiredLevel);
    }
}
