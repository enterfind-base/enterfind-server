package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.AiSettingName;

@SpringBootTest
@ActiveProfiles("alpha")
public class SystemSettingsDaoTest extends BaseDatabaseTest {

    @Autowired
    private SystemSettingsDao systemSettingsDao;

    @BeforeEach
    public void setup() {
        wipeDatabase();
    }

    @Test
    public void getSetting_missing_returnsEmpty() {
        Optional<String> result = systemSettingsDao.getSetting("NonExistentKey");
        assertFalse(result.isPresent());
    }

    @Test
    public void setSetting_insert_storesValue() {
        systemSettingsDao.setSetting("TestKey", "TestValue");
        Optional<String> result = systemSettingsDao.getSetting("TestKey");
        assertTrue(result.isPresent());
        assertEquals("TestValue", result.get());
    }

    @Test
    public void setSetting_upsert_updatesExistingRow() {
        systemSettingsDao.setSetting("TestKey", "FirstValue");
        systemSettingsDao.setSetting("TestKey", "SecondValue");

        Optional<String> result = systemSettingsDao.getSetting("TestKey");
        assertTrue(result.isPresent());
        assertEquals("SecondValue", result.get());

        Integer count = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM SYSTEM_SETTINGS WHERE setting_name = ?", Integer.class, "TestKey");
        assertEquals(1, count);
    }

    @Test
    public void setSetting_upsert_setsDateModified() {
        systemSettingsDao.setSetting("TestKey", "FirstValue");

        Object dateModifiedAfterInsert = dbAccess.queryForObject(
            "SELECT date_modified FROM SYSTEM_SETTINGS WHERE setting_name = ?", Object.class, "TestKey");
        assertNull(dateModifiedAfterInsert);

        systemSettingsDao.setSetting("TestKey", "SecondValue");

        Object dateModifiedAfterUpdate = dbAccess.queryForObject(
            "SELECT date_modified FROM SYSTEM_SETTINGS WHERE setting_name = ?", Object.class, "TestKey");
        assertNotNull(dateModifiedAfterUpdate);
    }

    // ── AI settings (typed, with encryption) ────────────────────────────────

    @Test
    public void getAiSetting_missing_returnsEmpty() {
        Optional<String> value = systemSettingsDao.getSetting(AiSettingName.OPENAI_API_KEY);
        assertFalse(value.isPresent());
    }

    @Test
    public void setAiSetting_insertAndRead_roundTrips() {
        systemSettingsDao.setSetting(AiSettingName.OPENAI_MODEL_NAME, "gpt-4.1-mini");

        Optional<String> value = systemSettingsDao.getSetting(AiSettingName.OPENAI_MODEL_NAME);
        assertTrue(value.isPresent());
        assertEquals("gpt-4.1-mini", value.get());
    }

    @Test
    public void setAiSetting_sensitiveSetting_storedEncryptedOrPlaintextCompatibly() {
        systemSettingsDao.setSetting(AiSettingName.OPENAI_API_KEY, "sk-test");

        String stored = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?",
            String.class,
            AiSettingName.OPENAI_API_KEY.name());
        Optional<String> decrypted = systemSettingsDao.getSetting(AiSettingName.OPENAI_API_KEY);

        assertTrue(stored.equals("sk-test") || stored.startsWith("ENC:"));
        assertTrue(decrypted.isPresent());
        assertEquals("sk-test", decrypted.get());
    }

    @Test
    public void getAllAiSettings_returnsOnlyAiSettings() {
        systemSettingsDao.setSetting(AiSettingName.AI_PROVIDER, "OLLAMA");
        dbAccess.update(
            "INSERT INTO SYSTEM_SETTINGS (setting_name, value) VALUES (?, ?)",
            "RemoteAccessEnabled", "false");

        var settings = systemSettingsDao.getAllAiSettings();

        assertEquals(1, settings.size());
        assertEquals(AiSettingName.AI_PROVIDER, settings.getFirst().settingName());
    }

    @Test
    public void getAllAiSettings_returnsRealSensitiveValues() {
        // The DAO stays honest — it returns the real decrypted secret. Redaction for the browser
        // is the aiSettings resolver's job (see GraphQLController), not the DAO's.
        systemSettingsDao.setSetting(AiSettingName.OPENAI_API_KEY, "sk-secret");

        var settings = systemSettingsDao.getAllAiSettings();

        String apiKey = settings.stream()
            .filter(s -> s.settingName() == AiSettingName.OPENAI_API_KEY)
            .findFirst().orElseThrow().value();
        assertEquals("sk-secret", apiKey);
    }
}
