package enterfind.server.config;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.java_websocket.client.WebSocketClient;

/**
 * Outbound HTTP proxy, decided once at startup and then read by every client from the JVM's default
 * {@link ProxySelector}.
 *
 * <p>The JVM does not read {@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code NO_PROXY}, so
 * {@link #apply()} maps them onto {@code https.proxyHost}, {@code http.proxyHost} and
 * {@code http.nonProxyHosts} before any client exists. JDK clients, OkHttp and Spring's
 * {@code RestClient} then pick the proxy up on their own. Two clients do not and are handed it
 * explicitly: Java-WebSocket ({@link #configure(WebSocketClient, URI)}), and the AWS SDK, which reads
 * only the {@code http.*} properties (see {@code S3BucketDataProvider}).
 *
 * <p>Explicit JVM properties ({@code -Dhttps.proxyHost} in {@code JAVA_OPTS}) win: when either proxy
 * host is already set the environment is ignored. Loopback is always in {@code http.nonProxyHosts},
 * because the tunnel forwards every remote request to {@code http://127.0.0.1}. With no proxy
 * configured anywhere nothing is written and every connection is direct.
 *
 * <p>Only plain HTTP CONNECT proxies without authentication are supported. Anything that cannot be
 * used is reported as a warning and ignored rather than stopping the server.
 */
public final class EgressProxy {

    static final String HTTPS_HOST = "https.proxyHost";
    static final String HTTPS_PORT = "https.proxyPort";
    static final String HTTP_HOST = "http.proxyHost";
    static final String HTTP_PORT = "http.proxyPort";
    static final String NON_PROXY_HOSTS = "http.nonProxyHosts";
    static final String LOOPBACK = "localhost|127.*|[::1]";

    /** The properties to write, a one-line description for the log, and anything that was ignored. */
    public record Resolution(Map<String, String> properties, String summary, List<String> warnings) {}

    private static final Resolution NONE =
            new Resolution(Map.of(), "Egress proxy: none (direct connections)", List.of());

    private static volatile Resolution current = NONE;

    private EgressProxy() {
    }

    /**
     * Resolves the proxy from the environment and system properties and writes the result. Called
     * first thing in {@code main}, before Spring has configured logging, so the outcome is only kept
     * here and logged later through {@link #current()}.
     */
    public static void apply() {
        Resolution resolution = resolve(System.getenv(), System.getProperties());
        resolution.properties().forEach(System::setProperty);
        current = resolution;
    }

    public static Resolution current() {
        return current;
    }

    /** Pure: what {@link #apply()} would write for this environment and these system properties. */
    static Resolution resolve(Map<String, String> env, Properties sys) {
        List<String> warnings = new ArrayList<>();

        if (isSet(sys.getProperty(HTTPS_HOST)) || isSet(sys.getProperty(HTTP_HOST))) {
            String bypass = withLoopback(sys.getProperty(NON_PROXY_HOSTS));
            return new Resolution(Map.of(NON_PROXY_HOSTS, bypass),
                    summary(sys.getProperty(HTTPS_HOST), sys.getProperty(HTTPS_PORT),
                            sys.getProperty(HTTP_HOST), sys.getProperty(HTTP_PORT), bypass, "JVM properties"),
                    warnings);
        }

        HostPort https = parseProxy("HTTPS_PROXY", variable(env, "https_proxy"), warnings);
        HostPort http = parseProxy("HTTP_PROXY", variable(env, "http_proxy"), warnings);
        if (https == null && http == null) {
            return new Resolution(Map.of(), NONE.summary(), warnings);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        if (https != null) {
            properties.put(HTTPS_HOST, https.host());
            properties.put(HTTPS_PORT, String.valueOf(https.port()));
        }
        if (http != null) {
            properties.put(HTTP_HOST, http.host());
            properties.put(HTTP_PORT, String.valueOf(http.port()));
        }
        String bypass = withLoopback(noProxy(variable(env, "no_proxy"), warnings));
        properties.put(NON_PROXY_HOSTS, bypass);

        return new Resolution(properties,
                summary(https != null ? https.host() : null, https != null ? String.valueOf(https.port()) : null,
                        http != null ? http.host() : null, http != null ? String.valueOf(http.port()) : null,
                        bypass, "environment"),
                warnings);
    }

    /**
     * The proxy the JVM's selector chooses for {@code target}, or {@link Proxy#NO_PROXY}. The default
     * selector only knows {@code http} and {@code https} (given {@code wss://} it would consult the
     * SOCKS properties), so a {@code ws}/{@code wss} target is asked about as {@code http}/{@code https}.
     */
    public static Proxy proxyFor(URI target) {
        ProxySelector selector = ProxySelector.getDefault();
        if (selector == null || target.getHost() == null) {
            return Proxy.NO_PROXY;
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("https") || scheme.equals("wss");
        URI probe;
        try {
            probe = new URI(secure ? "https" : "http", null, target.getHost(), target.getPort(), null, null, null);
        } catch (URISyntaxException e) {
            return Proxy.NO_PROXY;
        }
        return selector.select(probe).stream()
                .filter(p -> p.type() == Proxy.Type.HTTP)
                .findFirst()
                .orElse(Proxy.NO_PROXY);
    }

    /**
     * Points a Java-WebSocket client at the proxy for {@code target}, if there is one. The library
     * ignores JVM proxy settings, and by default resolves the host locally before connecting — which
     * fails on networks whose only way out is the proxy. Clearing the resolver makes the CONNECT
     * request carry the hostname, so the proxy resolves it.
     */
    public static Proxy configure(WebSocketClient client, URI target) {
        Proxy proxy = proxyFor(target);
        if (proxy.type() == Proxy.Type.HTTP) {
            client.setProxy(proxy);
            client.setDnsResolver(null);
        }
        return proxy;
    }

    private record HostPort(String host, int port) {}

    /** Lowercase wins over uppercase, as curl reads them; blank counts as unset. */
    private static String variable(Map<String, String> env, String lowercaseName) {
        String value = env.get(lowercaseName);
        if (!isSet(value)) {
            value = env.get(lowercaseName.toUpperCase(Locale.ROOT));
        }
        return isSet(value) ? value.trim() : null;
    }

    /** Accepts {@code http://host:port[/]} or {@code host:port}. Messages never echo the value: it may carry credentials. */
    private static HostPort parseProxy(String name, String value, List<String> warnings) {
        if (value == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value.contains("://") ? value : "http://" + value);
        } catch (URISyntaxException e) {
            warnings.add(name + " ignored: not a valid proxy URL");
            return null;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            warnings.add(name + " ignored: only http:// proxies are supported, not " + uri.getScheme() + "://");
            return null;
        }
        if (uri.getHost() == null || uri.getPort() == -1) {
            warnings.add(name + " ignored: a proxy URL needs a host and a port, e.g. http://proxy.example:3128");
            return null;
        }
        if (uri.getRawUserInfo() != null) {
            warnings.add(name + ": proxy credentials are not supported and are ignored");
        }
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return new HostPort(host, uri.getPort());
    }

    /** {@code NO_PROXY} in curl's terms, translated into {@code http.nonProxyHosts} patterns. */
    private static String noProxy(String value, List<String> warnings) {
        if (value == null) {
            return null;
        }
        Set<String> patterns = new LinkedHashSet<>();
        for (String raw : value.split(",")) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("/")) {
                warnings.add("NO_PROXY entry " + entry + " ignored: IP ranges are not supported");
                continue;
            }
            entry = stripPort(entry, warnings);
            if (entry.equals("*") || entry.startsWith("*.") || entry.startsWith("[") || entry.matches("[0-9.]+")) {
                patterns.add(entry);
            } else if (entry.contains(":")) {
                patterns.add("[" + entry + "]");
            } else {
                String domain = entry.startsWith(".") ? entry.substring(1) : entry;
                patterns.add(domain);
                patterns.add("*." + domain);
            }
        }
        return patterns.isEmpty() ? null : String.join("|", patterns);
    }

    private static String stripPort(String entry, List<String> warnings) {
        int portAt = -1;
        if (entry.startsWith("[")) {
            int close = entry.indexOf(']');
            if (close > 0 && close + 1 < entry.length() && entry.charAt(close + 1) == ':') {
                portAt = close + 1;
            }
        } else if (entry.indexOf(':') > 0 && entry.indexOf(':') == entry.lastIndexOf(':')) {
            portAt = entry.indexOf(':');
        }
        if (portAt < 0) {
            return entry;
        }
        String host = entry.substring(0, portAt);
        warnings.add("NO_PROXY entry " + entry + " applies to every port of " + host);
        return host;
    }

    private static String withLoopback(String bypass) {
        Set<String> patterns = new LinkedHashSet<>();
        if (isSet(bypass)) {
            for (String p : bypass.split("\\|")) {
                if (!p.isBlank()) {
                    patterns.add(p.trim());
                }
            }
        }
        patterns.addAll(List.of(LOOPBACK.split("\\|")));
        return String.join("|", patterns);
    }

    private static String summary(String httpsHost, String httpsPort, String httpHost, String httpPort,
            String bypass, String source) {
        return "Egress proxy: https=" + endpoint(httpsHost, httpsPort)
                + " http=" + endpoint(httpHost, httpPort)
                + " bypass=" + bypass + " (from " + source + ")";
    }

    private static String endpoint(String host, String port) {
        if (!isSet(host)) {
            return "none";
        }
        return host + ":" + (isSet(port) ? port : "default");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
