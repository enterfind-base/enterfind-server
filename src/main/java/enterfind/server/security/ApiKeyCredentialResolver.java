package enterfind.server.security;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import enterfind.server.dao.ApiSessionDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.ApiSession;
import enterfind.server.model.ApiUser;
import enterfind.server.service.AuthenticationService;

/**
 * Exchanges the API key a connector sends in its Authorization header for an API session, so the
 * key stays in the connection and never reaches the model.
 *
 * <p>Holds no state: the API user and its sessions are in the database already. A fresh session of
 * the key's API user is reused — one created by a script using the same key included, since it is
 * the same API user — and otherwise {@link AuthenticationService#loginWithApiKey} creates one, which
 * keeps its active and expiry checks, its audit events and its lifetime rule in one place.
 */
@Component
public class ApiKeyCredentialResolver {

    private static final String BEARER_PREFIX = "bearer ";

    private final AuthenticationService authenticationService;
    private final ApiSessionDao apiSessionDao;

    public ApiKeyCredentialResolver(AuthenticationService authenticationService, ApiSessionDao apiSessionDao) {
        this.authenticationService = authenticationService;
        this.apiSessionDao = apiSessionDao;
    }

    public ApiKeyExchange resolve(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return new ApiKeyExchange.Absent();
        }
        if (!authenticationService.isAuthenticationRequired()) {
            return new ApiKeyExchange.Ignored();
        }

        String key = stripBearer(authorizationHeader.trim());
        if (!key.startsWith(AuthenticationService.API_KEY_PREFIX)) {
            return new ApiKeyExchange.Failed(
                "The connector's Authorization header must carry an Enterfind API key (efk_...).");
        }

        Optional<ApiUser> optApiUser = authenticationService.findApiUserByKey(key);
        if (optApiUser.isEmpty()) {
            return new ApiKeyExchange.Failed(
                "The API key in the connector's Authorization header is not valid for this Enterfind instance.");
        }
        ApiUser apiUser = optApiUser.get();

        // An inactive or expired user goes straight to the login below, which refuses it with the
        // reason and the audit event. Reusing a leftover session would instead fail every field
        // separately in AuthContext.
        if (apiUser.isCurrentlyValid()) {
            Optional<ApiSession> fresh = findFreshSession(apiUser);
            if (fresh.isPresent()) {
                return new ApiKeyExchange.Resolved(fresh.get().token());
            }
        }

        try {
            return new ApiKeyExchange.Resolved(
                authenticationService.loginWithApiKey(apiUser.nickname(), key).token());
        } catch (InvalidArgumentException e) {
            return new ApiKeyExchange.Failed(
                "Enterfind refused the API key in the connector's Authorization header: " + e.getMessage() + ".");
        }
    }

    /**
     * A session is fresh when it has at least half a lifetime left, or lasts until the key itself
     * expires. Links built from a session carry it, so one about to expire would hand out links
     * that die before they are fetched; the key-expiry cap stops a key in its last half-lifetime
     * from logging in on every call.
     */
    private Optional<ApiSession> findFreshSession(ApiUser apiUser) {
        LocalDateTime halfLifetime = LocalDateTime.now()
            .plusSeconds(authenticationService.getApiSessionDurationMinutes() * 30L);
        LocalDateTime freshUntil = halfLifetime.isBefore(apiUser.dateExpires()) ? halfLifetime : apiUser.dateExpires();
        return apiSessionDao.findValidByApiUserId(apiUser.id()).stream()
            .filter(session -> !session.dateExpires().isBefore(freshUntil))
            .max(Comparator.comparing(ApiSession::dateExpires));
    }

    /** RFC 6750 schemes are case-insensitive; Claude sends the header value exactly as entered. */
    private static String stripBearer(String value) {
        if (value.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            return value.substring(BEARER_PREFIX.length()).trim();
        }
        return value;
    }
}
