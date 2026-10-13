package enterfind.server.dao;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

import enterfind.server.model.AiSettingName;
import enterfind.server.security.ConfigEncryptor;

@Repository
public class SystemSettingsDao {

    private final DBAccess dbAccess;
    private final ConfigEncryptor configEncryptor;

    public SystemSettingsDao(DBAccess dbAccess, ConfigEncryptor configEncryptor) {
        this.dbAccess = dbAccess;
        this.configEncryptor = configEncryptor;
    }

    public Optional<String> getSetting(String name) {
        String sql = "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?";
        try {
            String value = dbAccess.queryForObject(sql, String.class, name);
            return Optional.ofNullable(value);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<String> getSetting(AiSettingName name) {
        String sql = "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?";
        try {
            String value = dbAccess.queryForObject(sql, String.class, name.name());
            if (value != null && AiSettingName.SENSITIVE.contains(name)) {
                value = configEncryptor.decrypt(value);
            }
            return Optional.ofNullable(value);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<AiSetting> getAllAiSettings() {
        List<String> names = Arrays.stream(AiSettingName.values()).map(Enum::name).toList();
        String placeholders = String.join(",", names.stream().map(n -> "?").toList());
        String sql = "SELECT setting_name, value FROM SYSTEM_SETTINGS WHERE setting_name IN (" + placeholders + ")";

        return dbAccess.query(sql, (rs, rowNum) -> {
            AiSettingName settingName = AiSettingName.valueOf(rs.getString("setting_name"));
            String raw = rs.getString("value");
            String value = (raw != null && AiSettingName.SENSITIVE.contains(settingName))
                ? configEncryptor.decrypt(raw)
                : raw;
            return new AiSetting(settingName, value);
        }, names.toArray());
    }

    public void setSetting(String name, String value) {
        String sql = """
            INSERT INTO SYSTEM_SETTINGS (setting_name, value)
            VALUES (?, ?)
            ON CONFLICT (setting_name) DO UPDATE SET value = EXCLUDED.value, date_modified = CURRENT_TIMESTAMP
            """;
        dbAccess.update(sql, name, value);
    }

    public void setSetting(AiSettingName name, String value) {
        String valueToStore = value;
        if (value != null && !value.isBlank() && AiSettingName.SENSITIVE.contains(name)) {
            valueToStore = configEncryptor.encrypt(value);
        }
        setSetting(name.name(), valueToStore);
    }

    public record AiSetting(AiSettingName settingName, String value) {}
}
