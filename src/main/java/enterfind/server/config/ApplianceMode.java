package enterfind.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import enterfind.server.exception.InvalidArgumentException;

/**
 * Appliance-mode flag. When enabled, all state-mutating operations are refused and
 * background schedulers are suppressed. Read at startup from:
 *
 *   enterfind.appliance-mode=true
 *
 * Changing the flag requires a restart — appliance-mode is a deployment-time decision.
 */
@Component
public class ApplianceMode {

    private static final Logger logger = LoggerFactory.getLogger(ApplianceMode.class);

    private final boolean enabled;

    public ApplianceMode(@Value("${enterfind.appliance-mode:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            logger.warn("Appliance mode is ENABLED: mutations and background processing are disabled");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Throws {@link InvalidArgumentException} if appliance-mode is on.
     * Call this at the start of any resolver or endpoint that mutates appliance state.
     */
    public void requireMutationsAllowed() {
        if (enabled) {
            throw new InvalidArgumentException(
                "This instance is in appliance mode; mutations are disabled");
        }
    }
}
