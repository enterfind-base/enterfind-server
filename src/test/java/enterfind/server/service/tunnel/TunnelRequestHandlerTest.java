package enterfind.server.service.tunnel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("alpha")
public class TunnelRequestHandlerTest extends BaseTunnelTest {

    @LocalServerPort
    private int springPort;


    private TunnelRequestHandler handlerFor(String baseUrl, AtomicReference<TunnelMessage> captured,
            CountDownLatch latch) {
        return new TunnelRequestHandler(baseUrl, msg -> {
            captured.set(msg);
            latch.countDown();
        });
    }

    @Test
    void handle_graphqlRequest_sendsHttpResponse() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-1";
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-1", "router-1",
            "POST", "/graphql",
            Map.of("content-type", "application/json"),
            "{\"query\":\"{__typename}\"}",
            null, fakeS3Url, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Expected response within timeout");

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals("HTTP_RESPONSE", response.type());
        assertEquals("req-1", response.requestId());
        assertEquals("router-1", response.routerConnectionId());
        assertEquals(200, response.status());
        assertNotNull(response.body());
        String decodedGraphql = new String(Base64.getDecoder().decode(response.body()), StandardCharsets.UTF_8);
        assertTrue(decodedGraphql.contains("data") || decodedGraphql.contains("__typename"),
            "Response body should contain GraphQL data: " + decodedGraphql);

        handler.shutdown();
    }

    @Test
    void handle_largeResponse_putsToS3AndSendsHttpResponseS3() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-large";
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-large", "router-1",
            "GET", "/test/large",
            null, null, null, fakeS3Url, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Expected large response within timeout");

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals("HTTP_RESPONSE_S3", response.type());
        assertEquals("req-large", response.requestId());
        assertEquals(200, response.status());
        assertNull(response.body(), "HTTP_RESPONSE_S3 must not have a body field");

        // Fake S3 should have received the PUT
        assertEquals(1, fakeS3PutCount.get(), "Expected exactly one S3 PUT");
        byte[] uploaded = lastS3Upload.get();
        assertNotNull(uploaded);
        assertTrue(uploaded.length >= 100 * 1024, "Uploaded body should be >= 100KB, was " + uploaded.length);

        handler.shutdown();
    }

    @Test
    void handle_allowlistedHeader_forwarded() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-auth";
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-auth", "router-1",
            "GET", "/test/headers",
            Map.of("authorization", "Bearer test-token-value",
                   "content-type", "application/json"),
            null, null, fakeS3Url, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals(200, response.status());
        assertNotNull(response.body());
        String decodedHeaders = new String(Base64.getDecoder().decode(response.body()), StandardCharsets.UTF_8);
        assertTrue(decodedHeaders.contains("authorization"),
            "Response body should contain 'authorization' header, body was: " + decodedHeaders);

        handler.shutdown();
    }

    @Test
    void handle_blockedHeader_notForwarded() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-blocked";
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-blocked", "router-1",
            "GET", "/test/headers",
            Map.of("x-amz-security-token", "secret-aws-token",
                   "content-type", "application/json"),
            null, null, fakeS3Url, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals(200, response.status());
        assertNotNull(response.body());
        String decodedBlocked = new String(Base64.getDecoder().decode(response.body()), StandardCharsets.UTF_8);
        assertFalse(decodedBlocked.contains("x-amz-security-token"),
            "Blocked header should not be forwarded to Spring, body was: " + decodedBlocked);

        handler.shutdown();
    }

    @Test
    void handle_unknownPath_sendsHttpResponseWith404() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-404";
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-404", "router-1",
            "GET", "/nonexistent-path-that-does-not-exist",
            null, null, null, fakeS3Url, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals(404, response.status());

        handler.shutdown();
    }

    @Test
    void handle_connectionRefused_sendsHttpResponseWith502() throws Exception {
        // Find a free port, then use it without starting a server to get connection refused
        int unusedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            unusedPort = ss.getLocalPort();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + unusedPort, captured, latch);

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-502";
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-502", "router-1",
            "GET", "/anything",
            null, null, null, fakeS3Url, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(15, TimeUnit.SECONDS));

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals(502, response.status());
        // Error message must not expose raw exception details
        assertFalse(response.body().contains("Exception"),
            "Error response should not contain raw exception, body was: " + response.body());

        handler.shutdown();
    }

    @Test
    void handle_binaryResponse_bodyIsBase64Encoded() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-binary";
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-binary", "router-1",
            "GET", "/test/binary",
            null, null, null, fakeS3Url, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Expected response within timeout");

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals("HTTP_RESPONSE", response.type());
        assertEquals(200, response.status());
        assertNotNull(response.body());

        byte[] decoded = Base64.getDecoder().decode(response.body());
        byte[] expected = { (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52 };
        assertArrayEquals(expected, decoded, "Binary response must survive the tunnel round-trip unchanged");

        handler.shutdown();
    }

    @Test
    void handle_chunkedLargeResponse_drainsToTempFileAndPutsToS3() throws Exception {
        // Path C: no Content-Length from upstream (chunked transfer encoding),
        // body overflows the inline buffer → drainToTempFile → S3 PUT → HTTP_RESPONSE_S3.
        // Use a WatchService to observe temp file creation; events are queued even if the
        // file is already deleted by the time we poll.
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));

        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            tmpDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<TunnelMessage> captured = new AtomicReference<>();
            TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

            String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-chunked";
            TunnelMessage request = new TunnelMessage(
                "HTTP_REQUEST", null, null, null, null, "req-chunked", "router-1",
                "GET", "/test/chunked-large",
                null, null, null, fakeS3Url, null, null, null, null, null
            );

            handler.handle(request);
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Expected response within timeout");

            // Drain queued watch events looking for a tunnel-response-*.tmp creation.
            Path observedTempFile = null;
            poll:
            for (int attempt = 0; attempt < 30 && observedTempFile == null; attempt++) {
                WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                for (WatchEvent<?> event : key.pollEvents()) {
                    String name = ((Path) event.context()).toString();
                    if (name.startsWith("tunnel-response-") && name.endsWith(".tmp")) {
                        observedTempFile = tmpDir.resolve((Path) event.context());
                        break poll;
                    }
                }
                key.reset();
            }

            assertNotNull(observedTempFile,
                "Expected a tunnel-response-*.tmp file to be created in " + tmpDir);

            // The finally block deletes the file after messageSender fires; wait for it.
            final Path tempFile = observedTempFile;
            long deadline = System.currentTimeMillis() + 2000;
            while (Files.exists(tempFile) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(Files.exists(tempFile), "Temp file should be deleted after S3 upload");

            TunnelMessage response = captured.get();
            assertNotNull(response);
            assertEquals("HTTP_RESPONSE_S3", response.type());
            assertEquals("req-chunked", response.requestId());
            assertEquals(200, response.status());
            assertNull(response.body(), "HTTP_RESPONSE_S3 must not have a body field");

            assertEquals(1, fakeS3PutCount.get(), "Expected exactly one S3 PUT");
            byte[] uploaded = lastS3Upload.get();
            assertNotNull(uploaded);
            assertTrue(uploaded.length >= 30 * 1024,
                "Uploaded body should be >= 30KB (got " + uploaded.length + " bytes)");

            handler.shutdown();
        }
    }

    @Test
    void handle_largeResponseWithoutS3Url_sends502() throws Exception {
        // Path D: response overflows the inline buffer but no s3UploadUrl is provided.
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> captured = new AtomicReference<>();
        TunnelRequestHandler handler = handlerFor("http://localhost:" + springPort, captured, latch);

        // No s3UploadUrl (null)
        TunnelMessage request = new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-no-s3", "router-1",
            "GET", "/test/chunked-large",
            null, null, null, null, null, null, null, null, null
        );

        handler.handle(request);
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Expected error response within timeout");

        TunnelMessage response = captured.get();
        assertNotNull(response);
        assertEquals(502, response.status());
        assertEquals(0, fakeS3PutCount.get(), "Expected no S3 PUT when no upload URL is given");

        handler.shutdown();
    }
}
