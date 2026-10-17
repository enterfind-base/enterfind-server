package enterfind.server.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves the JVM-global proxy system properties and puts them back. Tests that set them must restore
 * them: the whole suite runs in one JVM, and a leftover proxy would reroute every later test.
 */
public final class ProxyPropertiesSnapshot {

    private static final List<String> KEYS = List.of(
            EgressProxy.HTTPS_HOST, EgressProxy.HTTPS_PORT,
            EgressProxy.HTTP_HOST, EgressProxy.HTTP_PORT,
            EgressProxy.NON_PROXY_HOSTS);

    private final Map<String, String> saved = new HashMap<>();

    public static ProxyPropertiesSnapshot take() {
        ProxyPropertiesSnapshot snapshot = new ProxyPropertiesSnapshot();
        KEYS.forEach(k -> snapshot.saved.put(k, System.getProperty(k)));
        return snapshot;
    }

    /** Clears every proxy property, so a test starts from "no proxy configured". */
    public static void clear() {
        KEYS.forEach(System::clearProperty);
    }

    public void restore() {
        saved.forEach((k, v) -> {
            if (v == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        });
    }
}
