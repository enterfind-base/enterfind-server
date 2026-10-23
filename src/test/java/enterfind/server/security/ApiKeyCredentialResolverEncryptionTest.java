package enterfind.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.UserLevel;
import enterfind.server.service.AuthenticationService;

/**
 * The connector header path with ENTERFIND_ENCRYPTION_KEY set, as every deployment template sets it.
 * Stored keys are then AES-GCM ciphertext under a random IV, so a key cannot be found by encrypting
 * it again and comparing.
 */
@SpringBootTest
@ActiveProfiles("alpha")
@TestPropertySource(properties = {
    "ENTERFIND_ENCRYPTION_KEY=PykQmzYw2SbTShBb8g5gukpLjtFQRvMJ07UTmPTr2m8=" // 32-byte test key
})
class ApiKeyCredentialResolverEncryptionTest extends BaseDatabaseTest {

    @Autowired
    private ApiKeyCredentialResolver resolver;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ConfigEncryptor configEncryptor;

    private String adminId;
    private String key;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        adminId = authenticationService.createUser("admin", UserLevel.ADMIN, null).user().id();
        key = authenticationService.createApiUser("connector", UserLevel.ADMIN, 365, adminId).keyValue();
    }

    @Test
    void storedKeyIsEncrypted() {
        assertTrue(configEncryptor.isEncryptionEnabled());
        String stored = dbAccess.queryForObject(
            "SELECT key_value FROM API_USERS WHERE nickname = 'connector'", String.class);
        assertTrue(configEncryptor.isEncrypted(stored));
    }

    @Test
    void loginWithApiKeyAcceptsTheKey() {
        assertNotNull(authenticationService.loginWithApiKey("connector", key).token());
    }

    @Test
    void findApiUserByKeyFindsTheKey() {
        var found = authenticationService.findApiUserByKey(key);
        assertTrue(found.isPresent(), "findApiUserByKey found no API user for a key it just created");
        assertEquals("connector", found.get().nickname());
    }

    @Test
    void bearerHeaderResolves() {
        ApiKeyExchange result = resolver.resolve("Bearer " + key);
        assertInstanceOf(ApiKeyExchange.Resolved.class, result, result::toString);
    }

    /** A key encrypted under a different encryption key, e.g. after ENTERFIND_ENCRYPTION_KEY changed. */
    @Test
    void undecryptableRowDoesNotBreakTheLookup() {
        dbAccess.update("""
            INSERT INTO API_USERS (id, nickname, key_value, user_level, created_by_user_id,
                                   date_created, date_expires, is_active)
            VALUES ('undecryptable', 'stale', 'ENC:bm90LXJlYWwtY2lwaGVydGV4dC1hdC1hbGw=', 'ADMIN', ?,
                    NOW(), NOW() + INTERVAL '1 day', true)
            """, adminId);

        assertEquals("connector", authenticationService.findApiUserByKey(key).orElseThrow().nickname());
        // An unknown key matches nothing, so the lookup visits every row, the undecryptable one included
        assertTrue(authenticationService.findApiUserByKey("efk_unknown").isEmpty());
        ApiKeyExchange result = resolver.resolve("Bearer " + key);
        assertInstanceOf(ApiKeyExchange.Resolved.class, result, result::toString);
    }

    @Test
    void plainHeaderResolves() {
        ApiKeyExchange result = resolver.resolve(key);
        assertInstanceOf(ApiKeyExchange.Resolved.class, result, result::toString);
    }
}
