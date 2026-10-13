package enterfind.server.util;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Helper for resolving the active Spring profile.
 *
 * When no profile is explicitly activated (e.g. running under the default
 * "alpha" profile set via spring.profiles.default), getActiveProfiles() returns
 * an empty array. This helper falls back to getDefaultProfiles() in that case
 * so callers always get the effective profile name.
 */
@Component
public class ProfileHelper {

    private final Environment environment;

    public ProfileHelper(Environment environment) {
        this.environment = environment;
    }

    /**
     * Returns the first effective profile name, falling back to the first default
     * profile when no profiles are explicitly active.
     */
    public String getActiveProfile() {
        String[] active = environment.getActiveProfiles();
        if (active.length > 0) {
            return active[0];
        }
        String[] defaults = environment.getDefaultProfiles();
        return defaults.length > 0 ? defaults[0] : "default";
    }

    /**
     * Returns true if the effective active profile matches the given name.
     */
    public boolean isProfile(String profileName) {
        String[] active = environment.getActiveProfiles();
        String[] profiles = active.length > 0 ? active : environment.getDefaultProfiles();
        for (String p : profiles) {
            if (profileName.equals(p)) {
                return true;
            }
        }
        return false;
    }
}
