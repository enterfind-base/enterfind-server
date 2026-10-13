package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.UserSettingsDao.UserSetting;
import enterfind.server.model.UserSettingName;

@SpringBootTest
@ActiveProfiles("alpha")
public class UserSettingsDaoTest extends BaseDatabaseTest {

    @Autowired
    private UserSettingsDao userSettingsDao;

    @BeforeEach
    public void setup() {
        wipeDatabase();
        // Insert default settings as in production
        dbAccess.update("INSERT INTO USER_SETTINGS (setting_name, value) VALUES (?, ?)", UserSettingName.CONCURRENT_TREATMENT_PROCESSORS.name(), "3");
           }

    @Test
    public void testGetSetting_existing() {
        Optional<String> value = userSettingsDao.getSetting(UserSettingName.CONCURRENT_TREATMENT_PROCESSORS);
        assertTrue(value.isPresent());
        assertEquals("3", value.get());
    }

    @Test
    public void testGetSetting_nonexistent() {
        // Use a non-existent enum value (simulate by catching exception)
        boolean thrown = false;
        try {
            userSettingsDao.getSetting(UserSettingName.valueOf("DOES_NOT_EXIST"));
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }

    @Test
    public void testSetSetting_update() {
        userSettingsDao.setSetting(UserSettingName.CONCURRENT_TREATMENT_PROCESSORS, "5");
        Optional<String> value = userSettingsDao.getSetting(UserSettingName.CONCURRENT_TREATMENT_PROCESSORS);
        assertTrue(value.isPresent());
        assertEquals("5", value.get());
    }

    @Test
    public void testGetAllSettings() {
        List<UserSetting> settings = userSettingsDao.getAllSettings();
        assertEquals(1, settings.size());
        assertTrue(settings.stream().anyMatch(s -> s.settingName() == UserSettingName.CONCURRENT_TREATMENT_PROCESSORS && s.value().equals("3")));
          }
}
