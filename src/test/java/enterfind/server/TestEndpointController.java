package enterfind.server;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Test-only controller providing utility endpoints for integration tests.
 * Loaded only in tests (not in the production jar).
 */
@RestController
@Profile("alpha")
public class TestEndpointController {

    /** Returns a response body ≥ 100 KB to trigger the S3 offload path. */
    @GetMapping("/test/large")
    public String largeResponse() {
        return "X".repeat(120 * 1024); // 120 KB
    }

    /** Returns all received request headers as a flat JSON-like string for header inspection. */
    @GetMapping("/test/headers")
    public Map<String, String> echoHeaders(@RequestHeader Map<String, String> headers) {
        Map<String, String> result = new HashMap<>();
        headers.forEach((name, value) -> result.put(name.toLowerCase(), value));
        return result;
    }

    /** Returns a known 16-byte PNG header to exercise binary response handling. */
    @GetMapping(value = "/test/binary", produces = "image/png")
    public byte[] binaryResponse() {
        return new byte[]{ (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                           0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52 };
    }

    /**
     * Returns ~30 KB to exercise the inline-vs-S3 threshold boundary.
     * Base64-encoded this exceeds the 32768-byte AWS API Gateway frame limit,
     * so it must go through the S3 offload path.
     */
    @GetMapping("/test/medium")
    public String mediumResponse() {
        return "X".repeat(30 * 1024);
    }

    /**
     * Streams 30 KB without a Content-Length header (chunked transfer encoding).
     * Used to exercise the buffer-overflow → drainToTempFile → S3 path when the
     * upstream response size is not known in advance.
     */
    @GetMapping(value = "/test/chunked-large", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<StreamingResponseBody> chunkedLargeResponse() {
        StreamingResponseBody body = out -> {
            byte[] chunk = new byte[4096];
            Arrays.fill(chunk, (byte) 'X');
            int totalBytes = 30 * 1024;
            int written = 0;
            while (written < totalBytes) {
                int toWrite = Math.min(chunk.length, totalBytes - written);
                out.write(chunk, 0, toWrite);
                written += toWrite;
            }
        };
        // ResponseEntity with StreamingResponseBody intentionally omits Content-Length.
        return ResponseEntity.ok().contentType(Objects.requireNonNull(MediaType.TEXT_PLAIN)).body(body);
    }
}
