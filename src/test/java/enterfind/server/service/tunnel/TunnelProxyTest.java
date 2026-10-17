package enterfind.server.service.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import enterfind.server.config.EgressProxy;
import enterfind.server.config.FakeConnectProxy;
import enterfind.server.config.ProxyPropertiesSnapshot;

/**
 * The tunnel through an HTTP CONNECT proxy, wired the way {@code RemoteAccessService} wires it:
 * {@link EgressProxy#configure} on a {@link TunnelClient}, with the proxy coming from the JVM properties.
 * No Spring context: the tunnel's local forwarding target is never called.
 */
class TunnelProxyTest {

    private ProxyPropertiesSnapshot snapshot;
    private FakeTunnelServer tunnelServer;
    private FakeConnectProxy proxy;
    private TunnelClient client;

    @BeforeEach
    void setUp() throws Exception {
        snapshot = ProxyPropertiesSnapshot.take();
        ProxyPropertiesSnapshot.clear();

        CountDownLatch started = new CountDownLatch(1);
        tunnelServer = new FakeTunnelServer(new InetSocketAddress("127.0.0.1", 0)) {
            @Override
            public void onStart() {
                super.onStart();
                started.countDown();
            }
        };
        tunnelServer.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "Fake tunnel server should start");

        proxy = new FakeConnectProxy();
        proxy.forwardTo(new InetSocketAddress("127.0.0.1", tunnelServer.getPort()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null && client.isOpen()) {
            client.closeBlocking();
        }
        proxy.close();
        tunnelServer.stop(1000);
        snapshot.restore();
    }

    /** What EgressProxy.apply() writes for HTTP_PROXY / HTTPS_PROXY pointing at the fake proxy. */
    private void configureJvmProxy() {
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(proxy.port()));
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", String.valueOf(proxy.port()));
        System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]");
    }

    private TunnelClient newClient(URI uri) {
        return new TunnelClient(uri, null, "1.0.0", null, disconnect -> {},
                "http://127.0.0.1:1", "ws://127.0.0.1:1");
    }

    @Test
    void tunnelHostIsResolvedByTheProxy_notLocally() throws Exception {
        configureJvmProxy();
        CountDownLatch registered = new CountDownLatch(1);
        tunnelServer.setOnMessageReceived(raw -> {
            if (raw.contains("\"REGISTER\"")) {
                registered.countDown();
            }
        });

        // .invalid never resolves: reaching the server proves the hostname went to the proxy in CONNECT.
        URI uri = URI.create("ws://tunnel.invalid:" + tunnelServer.getPort());
        client = newClient(uri);
        Proxy chosen = EgressProxy.configure(client, uri);

        assertEquals(proxy.port(), ((InetSocketAddress) chosen.address()).getPort());
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "Tunnel should connect through the proxy");
        assertTrue(registered.await(5, TimeUnit.SECONDS), "REGISTER should reach the tunnel server");
        assertEquals(List.of("CONNECT tunnel.invalid:" + tunnelServer.getPort() + " HTTP/1.1"), proxy.requests());
    }

    @Test
    void noProxyConfigured_clientUntouched() throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + tunnelServer.getPort());
        client = newClient(uri);

        assertEquals(Proxy.NO_PROXY, EgressProxy.configure(client, uri));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
        assertEquals(List.of(), proxy.requests());
    }

    @Test
    void loopbackTunnelEndpoint_staysDirectEvenWithProxy() throws Exception {
        configureJvmProxy();
        URI uri = URI.create("ws://127.0.0.1:" + tunnelServer.getPort());
        client = newClient(uri);

        assertEquals(Proxy.NO_PROXY, EgressProxy.configure(client, uri));
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
        assertEquals(List.of(), proxy.requests());
    }
}
