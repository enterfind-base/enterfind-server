package enterfind.server.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import enterfind.server.config.ApplianceMode;
import enterfind.server.dao.ApiUserDao;
import enterfind.server.dao.HumanUserDao;
import enterfind.server.dao.Schema;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.ApiSession;
import enterfind.server.model.ApiUser;
import enterfind.server.model.HumanUser;
import enterfind.server.model.Session;
import enterfind.server.model.UserLevel;
import enterfind.server.model.input.CreateApiUserInput;
import enterfind.server.model.input.CreateHumanUserInput;
import enterfind.server.model.input.LoginInput;
import enterfind.server.model.input.LoginWithApiKeyInput;
import enterfind.server.model.input.RecoveryLoginInput;
import enterfind.server.model.input.UpdateHumanUserInput;
import enterfind.server.security.AuthContext;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.AuthenticationService.ApiUserCreationResult;
import enterfind.server.service.AuthenticationService.TotpRegenerationResult;
import enterfind.server.service.TotpService;

/**
 * GraphQL controller for authentication operations.
 */
@Controller
public class AuthController {

    private final AuthenticationService authenticationService;
    private final HumanUserDao humanUserDao;
    private final ApiUserDao apiUserDao;
    private final TotpService totpService;
    private final AuthContext authContext;
    private final Schema schema;
    private final ApplianceMode applianceMode;

    // Setup tokens for one-time setup links (in-memory, expires after 24 hours)
    private static final int SETUP_TOKEN_EXPIRY_HOURS = 24;
    private final Map<String, SetupTokenData> setupTokens = new ConcurrentHashMap<>();

    public AuthController(AuthenticationService authenticationService, HumanUserDao humanUserDao,
            ApiUserDao apiUserDao, TotpService totpService, AuthContext authContext, Schema schema,
            ApplianceMode applianceMode) {
        this.authenticationService = authenticationService;
        this.humanUserDao = humanUserDao;
        this.apiUserDao = apiUserDao;
        this.totpService = totpService;
        this.authContext = authContext;
        this.schema = schema;
        this.applianceMode = applianceMode;
    }

    private void checkSchemaReady() {
        if (schema.isSchemaUpdateInProgress()) {
            throw new IllegalStateException("Schema update in progress. Please try again shortly.");
        }
    }

    // ================= Queries =================

    @QueryMapping
    public boolean isAuthenticationRequired() {
        checkSchemaReady();
        return authenticationService.isAuthenticationRequired();
    }

    @QueryMapping
    public HumanUser currentUser(@Argument String token) {
        checkSchemaReady();
        return authContext.validateAndGetUser(token).orElse(null);
    }

    @QueryMapping
    public List<HumanUser> humanUsers(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return humanUserDao.findAll();
    }

    @QueryMapping
    public HumanUser humanUser(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return humanUserDao.findById(id).orElse(null);
    }

    @QueryMapping
    public List<ApiUser> apiUsers(@Argument String token) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return apiUserDao.findAll();
    }

    @QueryMapping
    public ApiUser apiUser(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireAdmin(token);
        return apiUserDao.findById(id).orElse(null);
    }

    @QueryMapping
    public UserSetupInfoDto userSetup(@Argument String setupToken) {
        checkSchemaReady();
        // This is a public query - no auth required

        // Clean up expired tokens first
        setupTokens.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(LocalDateTime.now()));

        SetupTokenData tokenData = setupTokens.get(setupToken);

        if (tokenData == null) {
            // Token not found - either invalid, expired, or already used
            return new UserSetupInfoDto("Unknown", "", "", List.of(), true);
        }

        // Get user nickname
        Optional<HumanUser> user = humanUserDao.findById(tokenData.userId());
        if (user.isEmpty()) {
            // User was deleted - remove token and return error
            setupTokens.remove(setupToken);
            return new UserSetupInfoDto("Unknown", "", "", List.of(), true);
        }

        // Remove token immediately (one-time use)
        setupTokens.remove(setupToken);

        return new UserSetupInfoDto(
            user.get().nickname(),
            tokenData.qrCodeDataUrl(),
            tokenData.totpSecret(),
            tokenData.recoveryCodes(),
            false
        );
    }

    // ================= Mutations =================

    @MutationMapping
    public AuthResult login(@Argument LoginInput input) {
        checkSchemaReady();
        Session session = authenticationService.login(
            input.nickname(),
            input.totpCode(),
            input.isRememberMe()
        );

        HumanUser user = humanUserDao.findById(session.userId())
            .orElseThrow(() -> new InvalidArgumentException("User not found"));

        return new AuthResult(session.token(), user, session.dateExpires().toString());
    }

    @MutationMapping
    public AuthResult loginWithRecoveryCode(@Argument RecoveryLoginInput input) {
        checkSchemaReady();
        Session session = authenticationService.loginWithRecoveryCode(
            input.nickname(),
            input.recoveryCode()
        );

        HumanUser user = humanUserDao.findById(session.userId())
            .orElseThrow(() -> new InvalidArgumentException("User not found"));

        return new AuthResult(session.token(), user, session.dateExpires().toString());
    }

    @MutationMapping
    public boolean logout(@Argument String token) {
        checkSchemaReady();
        if (token == null || token.isBlank()) {
            return false;
        }

        return authenticationService.logout(token);
    }

    @MutationMapping
    public AuthResult loginWithApiKey(@Argument LoginWithApiKeyInput input) {
        checkSchemaReady();
        ApiSession session = authenticationService.loginWithApiKey(
            input.nickname(),
            input.keyValue()
        );

        ApiUser apiUser = apiUserDao.findById(session.apiUserId())
            .orElseThrow(() -> new InvalidArgumentException("API user not found"));

        // Create a "virtual" HumanUser for the response
        HumanUser virtualUser = new HumanUser(
            apiUser.id(),
            apiUser.nickname(),
            null, // no TOTP secret
            null, // no recovery codes
            apiUser.userLevel(),
            apiUser.dateCreated(),
            apiUser.dateLastUsed(),
            apiUser.isActive()
        );

        return new AuthResult(session.token(), virtualUser, session.dateExpires().toString());
    }

    @MutationMapping
    public UserCreationResultDto createHumanUser(@Argument String token, @Argument CreateHumanUserInput input) {
        checkSchemaReady();
        applianceMode.requireMutationsAllowed();

        // First user creation doesn't require auth, subsequent ones require admin
        boolean isFirstUser = !authenticationService.isAuthenticationRequired();
        if (!isFirstUser) {
            authContext.requireAdmin(token);
        }

        // Validate nickname
        if (input.nickname() == null || input.nickname().isBlank()) {
            throw new InvalidArgumentException("Nickname cannot be empty");
        }

        if (humanUserDao.existsByNickname(input.nickname())) {
            throw new InvalidArgumentException("Nickname already exists");
        }

        // First user must be admin
        UserLevel effectiveUserLevel = input.userLevel();
        if (isFirstUser && effectiveUserLevel != UserLevel.ADMIN) {
            effectiveUserLevel = UserLevel.ADMIN;
        }

        // Generate TOTP setup
        String totpSecret = totpService.generateSecret();
        String qrCodeDataUrl = totpService.generateQrCodeDataUrl(totpSecret, input.nickname());
        List<String> recoveryCodes = totpService.generateRecoveryCodes();

        // Create the user immediately in database
        String serializedRecoveryCodes = totpService.serializeRecoveryCodes(recoveryCodes);
        HumanUser user = humanUserDao.create(
            input.nickname(),
            totpSecret,
            serializedRecoveryCodes,
            effectiveUserLevel
        );

        // Generate setup token
        String setupToken = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.now().plusHours(SETUP_TOKEN_EXPIRY_HOURS);

        // Remove any existing setup token for this user
        setupTokens.entrySet().removeIf(entry -> entry.getValue().userId().equals(user.id()));

        // Store setup token
        setupTokens.put(setupToken, new SetupTokenData(
            user.id(),
            totpSecret,
            recoveryCodes,
            qrCodeDataUrl,
            expires
        ));

        String setupUrl = "/user-onetime-setup.html?token=" + setupToken;
        return new UserCreationResultDto(user, setupToken, setupUrl);
    }

    @MutationMapping
    public UserCreationResultDto regenerateSetupToken(@Argument String token, @Argument String userId) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        HumanUser user = humanUserDao.findById(userId)
            .orElseThrow(() -> new InvalidArgumentException("User not found"));

        // Remove any existing setup token for this user
        setupTokens.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));

        // Generate new TOTP setup
        String totpSecret = totpService.generateSecret();
        String qrCodeDataUrl = totpService.generateQrCodeDataUrl(totpSecret, user.nickname());
        List<String> recoveryCodes = totpService.generateRecoveryCodes();

        // Update user with new TOTP secret and recovery codes
        String serializedRecoveryCodes = totpService.serializeRecoveryCodes(recoveryCodes);
        humanUserDao.updateTotpSecret(userId, totpSecret, serializedRecoveryCodes);

        // Generate new setup token
        String setupToken = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.now().plusHours(SETUP_TOKEN_EXPIRY_HOURS);

        // Store setup token
        setupTokens.put(setupToken, new SetupTokenData(
            userId,
            totpSecret,
            recoveryCodes,
            qrCodeDataUrl,
            expires
        ));

        String setupUrl = "/user-onetime-setup.html?token=" + setupToken;
        return new UserCreationResultDto(user, setupToken, setupUrl);
    }

    @MutationMapping
    public HumanUser updateHumanUser(@Argument String token, @Argument UpdateHumanUserInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        Optional<HumanUser> optUser = humanUserDao.findById(input.id());
        if (optUser.isEmpty()) {
            throw new InvalidArgumentException("User not found");
        }
        HumanUser user = optUser.get();

        // Prevent deactivating the only user
        if (input.isActive() != null && !input.isActive()) {
            int userCount = humanUserDao.countUsers();
            if (userCount <= 1) {
                throw new InvalidArgumentException("Cannot deactivate the only user");
            }
        }

        // Prevent removing admin rights from the last active admin, which would lock everyone out
        boolean losingAdminRights = user.userLevel() == UserLevel.ADMIN && user.isActive()
            && ((input.userLevel() != null && input.userLevel() != UserLevel.ADMIN)
                || (input.isActive() != null && !input.isActive()));
        if (losingAdminRights && humanUserDao.countActiveAdmins() <= 1) {
            throw new InvalidArgumentException("Cannot remove the last remaining administrator");
        }

        humanUserDao.update(input.id(), input.nickname(), input.userLevel(), input.isActive());

        return humanUserDao.findById(input.id()).orElse(null);
    }

    @MutationMapping
    public boolean deleteHumanUser(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        Optional<HumanUser> optUser = humanUserDao.findById(id);
        if (optUser.isEmpty()) {
            throw new InvalidArgumentException("User not found");
        }
        HumanUser user = optUser.get();
        int userCount = humanUserDao.countUsers();

        // Prevent deleting self unless it's the only user
        Optional<HumanUser> currentUser = authContext.validateAndGetUser(token);
        if (currentUser.isPresent() && currentUser.get().id().equals(id)) {
            if (userCount > 1) {
                throw new InvalidArgumentException("Cannot delete your own account while other users exist");
            }
            // If this is the only user, allow self-deletion
        }

        // Prevent deleting the last remaining administrator while other users still exist,
        // which would lock them out. Deleting the only user overall is still allowed.
        if (userCount > 1 && user.userLevel() == UserLevel.ADMIN && user.isActive()
                && humanUserDao.countActiveAdmins() <= 1) {
            throw new InvalidArgumentException("Cannot delete the last remaining administrator");
        }

        String deletedByNickname = currentUser.map(HumanUser::nickname).orElse(null);
        return authenticationService.deleteUser(id, deletedByNickname);
    }

    @MutationMapping
    public ApiUserCreationResultDto createApiUser(@Argument String token, @Argument CreateApiUserInput input) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        // Get creator user ID
        String createdByUserId = authContext.validateAndGetUser(token)
            .map(HumanUser::id)
            .orElse(null);

        ApiUserCreationResult result = authenticationService.createApiUser(
            input.nickname(),
            input.userLevel(),
            input.expirationDays(),
            createdByUserId
        );

        return new ApiUserCreationResultDto(result.apiUser(), result.keyValue());
    }

    @MutationMapping
    public boolean deleteApiUser(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        String deletedByNickname = authContext.validateAndGetUser(token)
            .map(HumanUser::nickname)
            .orElse(null);

        return authenticationService.deleteApiUser(id, deletedByNickname);
    }

    @MutationMapping
    public TotpSetup regenerateTotpSecret(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        String regeneratedByNickname = authContext.validateAndGetUser(token)
            .map(HumanUser::nickname)
            .orElse(null);

        HumanUser user = humanUserDao.findById(id)
            .orElseThrow(() -> new InvalidArgumentException("User not found"));

        TotpRegenerationResult result = authenticationService.regenerateTotpSecret(id, regeneratedByNickname);

        String qrCodeDataUrl = totpService.generateQrCodeDataUrl(result.totpSecret(), user.nickname());

        return new TotpSetup(result.totpSecret(), qrCodeDataUrl, result.recoveryCodes());
    }

    @MutationMapping
    public RecoveryCodesResult regenerateRecoveryCodes(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireMutationAdmin(token);

        String regeneratedByNickname = authContext.validateAndGetUser(token)
            .map(HumanUser::nickname)
            .orElse(null);

        List<String> newCodes = authenticationService.regenerateRecoveryCodes(id, regeneratedByNickname);

        return new RecoveryCodesResult(newCodes);
    }

    // ================= Schema Mappings =================

    @SchemaMapping(typeName = "HumanUser", field = "recoveryCodesRemaining")
    public int recoveryCodesRemaining(HumanUser user) {
        return totpService.countRecoveryCodes(user.recoveryCodes());
    }

    @SchemaMapping(typeName = "ApiUser", field = "keyValuePreview")
    public String keyValuePreview(ApiUser apiUser) {
        return apiUser.getKeyValuePreview();
    }

    @SchemaMapping(typeName = "ApiUser", field = "isExpired")
    public boolean isExpired(ApiUser apiUser) {
        return apiUser.isExpired();
    }

    // ================= Result Types =================

    public record AuthResult(String token, HumanUser user, String expiresAt) {}
    public record TotpSetup(String secret, String qrCodeDataUrl, List<String> recoveryCodes) {}
    public record RecoveryCodesResult(List<String> recoveryCodes) {}
    public record UserCreationResultDto(HumanUser user, String setupToken, String setupUrl) {}
    public record UserSetupInfoDto(String nickname, String qrCodeDataUrl, String totpSecret, List<String> recoveryCodes, boolean alreadyUsed) {}
    public record ApiUserCreationResultDto(ApiUser apiUser, String keyValue) {}

    // ================= Internal Types =================

    private record SetupTokenData(
        String userId,
        String totpSecret,
        List<String> recoveryCodes,
        String qrCodeDataUrl,
        LocalDateTime expires
    ) {}
}
