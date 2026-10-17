package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import enterfind.server.config.FakeConnectProxy;
import enterfind.server.config.ProxyPropertiesSnapshot;
import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.S3BucketDataSource;

/**
 * The S3 client takes its proxy from the JVM's selector. The AWS SDK's own lookup reads only the
 * {@code http.*} properties, so these cases set the {@code https.*} ones alone — what {@code HTTPS_PROXY}
 * maps to — against HTTPS endpoints. The fake proxy refuses every request, so nothing leaves the machine.
 */
class S3BucketDataProviderProxyTest {

    private ProxyPropertiesSnapshot snapshot;
    private FakeConnectProxy proxy;
    private S3BucketDataProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        snapshot = ProxyPropertiesSnapshot.take();
        ProxyPropertiesSnapshot.clear();
        proxy = new FakeConnectProxy();
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", String.valueOf(proxy.port()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.shutdown();
        }
        proxy.close();
        snapshot.restore();
    }

    private static S3BucketDataSource dataSource(String nameOrUrl) {
        return new S3BucketDataSource("s3proxytest00001", "proxy test", null, null, null,
                DataSourceStatus.IDLE, nameOrUrl, "access", "secret", null, "us-east-1", null);
    }

    private List<String> listThroughProvider(String nameOrUrl) {
        provider = new S3BucketDataProvider(dataSource(nameOrUrl));
        assertThrows(SystemIssueException.class, () -> provider.getItems(""));
        return proxy.requests();
    }

    @Test
    void awsBucket_httpsProxyOnly_goesThroughProxy() {
        List<String> requests = listThroughProvider("enterfind-proxy-test-bucket");

        assertFalse(requests.isEmpty(), "S3 request should reach the proxy");
        assertTrue(requests.get(0).matches("CONNECT [^ ]*amazonaws\\.com:443 HTTP/1\\.1"), requests.get(0));
    }

    @Test
    void customHttpsEndpoint_goesThroughProxy_withoutLocalDns() {
        List<String> requests = listThroughProvider("https://s3.invalid/bucket");

        assertFalse(requests.isEmpty(), "S3 request should reach the proxy");
        assertEquals("CONNECT s3.invalid:443 HTTP/1.1", requests.get(0));
    }

    @Test
    void endpointInNonProxyHosts_staysDirect() {
        System.setProperty("http.nonProxyHosts", "*.invalid|localhost|127.*|[::1]");

        assertEquals(List.of(), listThroughProvider("https://s3.invalid/bucket"));
    }

    @Test
    void httpEndpoint_httpsProxyOnly_staysDirect() {
        assertEquals(List.of(), listThroughProvider("http://s3.invalid:9000/bucket"));
    }
}
