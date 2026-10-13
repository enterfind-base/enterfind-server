package enterfind.server.dao;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

import enterfind.server.model.UserSettingName;

@Repository
public class UserSettingsDao {
    // Observers for setting changes
    private final Map<UserSettingName, List<Consumer<String>>> observers = new ConcurrentHashMap<>();
    private final DBAccess dbAccess;

    @Autowired
    public UserSettingsDao(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }

    public Optional<String> getSetting(UserSettingName name) {
        String sql = "SELECT value FROM USER_SETTINGS WHERE setting_name = ?";
        try {
            String value = dbAccess.queryForObject(sql, String.class, name.name());
            return Optional.ofNullable(value);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<UserSetting> getAllSettings() {
        String sql = "SELECT setting_name, value FROM USER_SETTINGS";
        return dbAccess.query(sql, (rs, rowNum) ->
            new UserSetting(UserSettingName.valueOf(rs.getString("setting_name")), rs.getString("value")));
    }

    public void setSetting(UserSettingName name, String value) {
        String sql = """
            INSERT INTO USER_SETTINGS (setting_name, value)
            VALUES (?, ?)
            ON CONFLICT (setting_name) DO UPDATE SET value = EXCLUDED.value
            """;
        dbAccess.update(sql, name.name(), value);
        notifyObservers(name, value);
    }

    /**
     * Register an observer to be notified when a particular setting is updated.
     * The observer will be called with the new value.
     */
    public void addSettingObserver(UserSettingName name, Consumer<String> observer) {
        observers.computeIfAbsent(name, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(observer);
    }

    private void notifyObservers(UserSettingName name, String value) {
        List<Consumer<String>> list = observers.get(name);
        if (list != null) {
            for (Consumer<String> obs : list) {
                obs.accept(value);
            }
        }
    }

    public record UserSetting(UserSettingName settingName, String value) {}
}
