package enterfind.server.service.tunnel;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import com.sun.net.httpserver.HttpServer;

import enterfind.server.BaseDatabaseTest;

public abstract class BaseTunnelTest extends BaseDatabaseTest {

    protected static HttpServer fakeS3Server;
    protected static int fakeS3Port;
    protected static final AtomicReference<byte[]> lastS3Upload = new AtomicReference<>();
    protected static final AtomicInteger fakeS3PutCount = new AtomicInteger(0);

    @BeforeAll
    static void startFakeS3Server() throws Exception {
        fakeS3Server = HttpServer.create(new InetSocketAddress(0), 0);
        fakeS3Port = fakeS3Server.getAddress().getPort();
        fakeS3Server.createContext("/", exchange -> {
            if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                lastS3Upload.set(body);
                fakeS3PutCount.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        fakeS3Server.start();
    }

    @AfterAll
    static void stopFakeS3Server() {
        if (fakeS3Server != null) fakeS3Server.stop(0);
    }

    @BeforeEach
    void resetFakeS3State() {
        lastS3Upload.set(null);
        fakeS3PutCount.set(0);
    }
}
