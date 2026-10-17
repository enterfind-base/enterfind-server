package enterfind.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import enterfind.server.config.EgressProxy.Resolution;

class EgressProxyTest {

    private static final String LOOPBACK = "localhost|127.*|[::1]";

    private static Resolution resolve(Map<String, String> env) {
        return EgressProxy.resolve(env, new Properties());
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    // --- source -----------------------------------------------------------------------------

    @Test
    void nothingSet_writesNothing() {
        Resolution r = resolve(Map.of());
        assertEquals(Map.of(), r.properties());
        assertEquals("Egress proxy: none (direct connections)", r.summary());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void httpsProxyOnly_setsHttpsPropertiesAndLoopbackBypass() {
        Resolution r = resolve(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"));
        assertEquals(Map.of(
                "https.proxyHost", "proxy.corp",
                "https.proxyPort", "3128",
                "http.nonProxyHosts", LOOPBACK), r.properties());
        assertEquals("Egress proxy: https=proxy.corp:3128 http=none bypass=" + LOOPBACK + " (from environment)",
                r.summary());
    }

    @Test
    void httpProxyOnly_setsHttpProperties() {
        Resolution r = resolve(Map.of("HTTP_PROXY", "http://proxy.corp:8080"));
        assertEquals("proxy.corp", r.properties().get("http.proxyHost"));
        assertEquals("8080", r.properties().get("http.proxyPort"));
        assertFalse(r.properties().containsKey("https.proxyHost"));
    }

    @Test
    void lowercaseBeatsUppercase() {
        Resolution r = resolve(Map.of("https_proxy", "http://lower:1", "HTTPS_PROXY", "http://upper:2"));
        assertEquals("lower", r.properties().get("https.proxyHost"));
    }

    @Test
    void blankLowercaseFallsThroughToUppercase() {
        Resolution r = resolve(Map.of("https_proxy", "", "HTTPS_PROXY", "http://upper:2"));
        assertEquals("upper", r.properties().get("https.proxyHost"));
    }

    @Test
    void blankValueIsUnset() {
        assertEquals(Map.of(), resolve(Map.of("HTTPS_PROXY", "  ", "NO_PROXY", "x.org")).properties());
    }

    @Test
    void jvmPropertiesWin_environmentIgnored() {
        Resolution r = EgressProxy.resolve(
                Map.of("HTTPS_PROXY", "http://env:1", "NO_PROXY", "env.org"),
                props("https.proxyHost", "jvm", "https.proxyPort", "3128"));
        assertEquals(Map.of("http.nonProxyHosts", LOOPBACK), r.properties());
        assertTrue(r.summary().contains("https=jvm:3128"), r.summary());
        assertTrue(r.summary().endsWith("(from JVM properties)"), r.summary());
    }

    @Test
    void jvmProperties_operatorBypassKeptAndLoopbackAppended() {
        Resolution r = EgressProxy.resolve(Map.of(),
                props("http.proxyHost", "jvm", "http.nonProxyHosts", "*.corp|localhost"));
        assertEquals("*.corp|localhost|127.*|[::1]", r.properties().get("http.nonProxyHosts"));
    }

    // --- proxy URL --------------------------------------------------------------------------

    @Test
    void proxyUrlForms() {
        assertEquals("p", resolve(Map.of("HTTPS_PROXY", "p:3128")).properties().get("https.proxyHost"));
        assertEquals("p", resolve(Map.of("HTTPS_PROXY", "http://p:3128/")).properties().get("https.proxyHost"));
        assertEquals("p", resolve(Map.of("HTTPS_PROXY", "HTTP://p:3128")).properties().get("https.proxyHost"));
        Resolution v6 = resolve(Map.of("HTTPS_PROXY", "http://[fd00::1]:3128"));
        assertEquals("fd00::1", v6.properties().get("https.proxyHost"));
        assertEquals("3128", v6.properties().get("https.proxyPort"));
    }

    @Test
    void missingPort_ignoredWithWarning() {
        Resolution r = resolve(Map.of("HTTPS_PROXY", "http://p"));
        assertEquals(Map.of(), r.properties());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).startsWith("HTTPS_PROXY ignored"), r.warnings().toString());
    }

    @Test
    void unsupportedSchemes_ignoredWithWarning() {
        for (String url : new String[] {"https://p:3128", "socks5://p:1080", "socks5h://p:1080"}) {
            Resolution r = resolve(Map.of("HTTPS_PROXY", url));
            assertEquals(Map.of(), r.properties(), url);
            assertEquals(1, r.warnings().size(), url);
        }
    }

    @Test
    void credentials_strippedAndNeverEchoed() {
        Resolution r = resolve(Map.of("HTTPS_PROXY", "http://user:s3cret@p:3128"));
        assertEquals("p", r.properties().get("https.proxyHost"));
        assertEquals("3128", r.properties().get("https.proxyPort"));
        assertEquals(1, r.warnings().size());
        assertFalse(r.properties().toString().contains("s3cret"));
        assertFalse(r.summary().contains("s3cret"));
        assertFalse(r.warnings().toString().contains("s3cret"));
        assertFalse(r.warnings().toString().contains("user"));
    }

    @Test
    void oneBadOneGood_goodOneUsed() {
        Resolution r = resolve(Map.of("HTTPS_PROXY", "socks5://p:1080", "HTTP_PROXY", "http://p:3128"));
        assertEquals("p", r.properties().get("http.proxyHost"));
        assertFalse(r.properties().containsKey("https.proxyHost"));
        assertEquals(1, r.warnings().size());
    }

    // --- NO_PROXY ---------------------------------------------------------------------------

    private static String bypass(String noProxy) {
        return resolve(Map.of("HTTPS_PROXY", "http://p:3128", "NO_PROXY", noProxy))
                .properties().get("http.nonProxyHosts");
    }

    @Test
    void noProxy_domainsMatchThemselvesAndSubdomains() {
        assertEquals("example.com|*.example.com|" + LOOPBACK, bypass("example.com"));
        assertEquals("example.com|*.example.com|" + LOOPBACK, bypass(".example.com"));
        assertEquals("example.com|*.example.com|" + LOOPBACK, bypass("example.com,.example.com"));
    }

    @Test
    void noProxy_patternsAndAddressesKept() {
        assertEquals("*.x.org|10.1.2.3|[fd00::1]|" + LOOPBACK, bypass("*.x.org, 10.1.2.3, fd00::1"));
        assertEquals("*|" + LOOPBACK, bypass("*"));
    }

    @Test
    void noProxy_normalisesWhitespaceAndCase() {
        assertEquals("minio.lan|*.minio.lan|" + LOOPBACK, bypass("  MinIO.LAN , ,"));
    }

    @Test
    void noProxy_portStrippedWithWarning() {
        Resolution r = resolve(Map.of("HTTPS_PROXY", "http://p:3128", "NO_PROXY", "host:8080,[fd00::1]:80"));
        assertEquals("host|*.host|[fd00::1]|" + LOOPBACK, r.properties().get("http.nonProxyHosts"));
        assertEquals(2, r.warnings().size());
    }

    @Test
    void noProxy_cidrDroppedWithWarning() {
        Resolution r = resolve(Map.of("HTTPS_PROXY", "http://p:3128", "NO_PROXY", "10.0.0.0/8,lan"));
        assertEquals("lan|*.lan|" + LOOPBACK, r.properties().get("http.nonProxyHosts"));
        assertEquals(1, r.warnings().size());
    }

    @Test
    void noProxy_loopbackNeverDuplicated() {
        assertEquals("localhost|*.localhost|127.0.0.1|127.*|[::1]", bypass("localhost,127.0.0.1"));
    }

    @Test
    void noProxy_withoutProxy_writesNothing() {
        assertEquals(Map.of(), resolve(Map.of("NO_PROXY", "example.com")).properties());
    }

    // --- proxyFor: what the JVM's selector makes of the written properties -----------------

    private ProxyPropertiesSnapshot snapshot;

    @BeforeEach
    void saveProperties() {
        snapshot = ProxyPropertiesSnapshot.take();
        ProxyPropertiesSnapshot.clear();
    }

    @AfterEach
    void restoreProperties() {
        snapshot.restore();
    }

    private static void applyToJvm(Map<String, String> env) {
        resolve(env).properties().forEach(System::setProperty);
    }

    @Test
    void proxyFor_wssIsAskedAsHttps() {
        applyToJvm(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"));
        Proxy proxy = EgressProxy.proxyFor(URI.create("wss://tunnel.example.com"));
        assertEquals(Proxy.Type.HTTP, proxy.type());
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertEquals("proxy.corp", address.getHostString());
        assertEquals(3128, address.getPort());
    }

    @Test
    void proxyFor_onlyHttpsProxy_plainHttpStaysDirect() {
        applyToJvm(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"));
        assertEquals(Proxy.NO_PROXY, EgressProxy.proxyFor(URI.create("ws://tunnel.example.com")));
        assertEquals(Proxy.NO_PROXY, EgressProxy.proxyFor(URI.create("http://ollama.lan:11434")));
    }

    @Test
    void proxyFor_loopbackAndNoProxyHostsStayDirect() {
        applyToJvm(Map.of("HTTPS_PROXY", "http://proxy.corp:3128", "HTTP_PROXY", "http://proxy.corp:3128",
                "NO_PROXY", ".corp.lan"));
        assertEquals(Proxy.NO_PROXY, EgressProxy.proxyFor(URI.create("http://127.0.0.1:8080")));
        assertEquals(Proxy.NO_PROXY, EgressProxy.proxyFor(URI.create("ws://localhost:8080")));
        assertEquals(Proxy.NO_PROXY, EgressProxy.proxyFor(URI.create("https://minio.corp.lan:9000")));
        assertEquals(Proxy.NO_PROXY, EgressProxy.proxyFor(URI.create("https://corp.lan")));
        assertEquals(Proxy.Type.HTTP, EgressProxy.proxyFor(URI.create("https://api.anthropic.com")).type());
    }

    @Test
    void proxyFor_nothingConfigured_direct() {
        assertEquals(Proxy.NO_PROXY, EgressProxy.proxyFor(URI.create("wss://tunnel.example.com")));
    }
}
