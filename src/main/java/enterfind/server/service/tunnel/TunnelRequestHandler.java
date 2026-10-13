package enterfind.server.service.tunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TunnelRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(TunnelRequestHandler.class);

    // AWS API Gateway enforces a 32768-byte WebSocket frame limit.
    private static final int MAX_TUNNEL_FRAME_BYTES = 32768;
    // Conservative upper bound for the JSON envelope surrounding the body
    // (type, requestId, routerConnectionId, status, headers, key names).
    private static final int ENVELOPE_OVERHEAD_BYTES = 1024;

    private static final Set<String> ALLOWED_REQUEST_HEADERS = Set.of(
        "content-type",
        "accept",
        "accept-encoding",
        "accept-language",
        "authorization",
        "user-agent",
        "cache-control"
    );

    private final String baseUrl;
    private final Consumer<TunnelMessage> messageSender;
    private final ExecutorService executor;
    private final HttpClient httpClient;

    public TunnelRequestHandler(String baseUrl, Consumer<TunnelMessage> messageSender) {
        this.baseUrl = baseUrl;
        this.messageSender = messageSender;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newHttpClient();
    }

    public void handle(TunnelMessage request) {
        executor.submit(() -> {
            try {
                processRequest(request);
            } catch (Exception e) {
                logger.error("Unexpected error processing tunnel request {}", request.requestId(), e);
                sendErrorResponse(request, 502);
            }
        });
    }

    private void processRequest(TunnelMessage request) {
        String urlString = baseUrl + request.path() + buildQueryString(request.queryStringParameters());

        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(urlString));

        // Set method and body
        String method = request.method() != null ? request.method().toUpperCase() : "GET";
        String bodyString = request.body() != null ? request.body() : "";
        HttpRequest.BodyPublisher bodyPublisher = bodyString.isEmpty()
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(bodyString);

        httpRequestBuilder.method(method, bodyPublisher);

        // Forward only allowlisted headers
        if (request.headers() != null) {
            request.headers().forEach((name, value) -> {
                if (ALLOWED_REQUEST_HEADERS.contains(name.toLowerCase())) {
                    httpRequestBuilder.header(name, value);
                }
            });
        }

        // Mark the request as tunnel-proxied so downstream handlers can detect it
        httpRequestBuilder.header("X-Enterfind-Tunnel-Proxy", "1");

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to execute proxied request to {}: {}", urlString, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            sendErrorResponse(request, 502);
            return;
        }

        int statusCode = response.statusCode();
        Map<String, String> responseHeaders = extractResponseHeaders(response);

        // max raw bytes that fit within a single WebSocket frame after base64 encoding:
        // base64(n) = ceil(n/3)*4 <= MAX_TUNNEL_FRAME_BYTES - ENVELOPE_OVERHEAD_BYTES
        // => n <= floor((MAX_TUNNEL_FRAME_BYTES - ENVELOPE_OVERHEAD_BYTES) * 3 / 4)
        int maxRawBytes = (MAX_TUNNEL_FRAME_BYTES - ENVELOPE_OVERHEAD_BYTES) * 3 / 4;

        try (InputStream body = response.body()) {
            // If Content-Length is already known to exceed the limit, start streaming to S3 immediately.
            long upstreamContentLength = parseContentLength(responseHeaders);
            if (upstreamContentLength > maxRawBytes && request.s3UploadUrl() != null) {
                logger.info("requestId={} path=S3_PIPE content-length={} limit={} — Content-Length known, streaming directly to S3",
                    request.requestId(), upstreamContentLength, maxRawBytes);
                pumpAndUploadToS3(request, null, body, upstreamContentLength, statusCode, responseHeaders);
                return;
            }

            // Buffer bytes while reading; switch to S3 if we overflow the limit.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            boolean overflow = false;

            while ((read = body.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > maxRawBytes) {
                    overflow = true;
                    break;
                }
            }

            if (!overflow) {
                // Fits in a single frame — encode and send inline.
                logger.info("requestId={} path=INLINE buffered={} bytes — fits in frame, sending inline",
                    request.requestId(), buffer.size());
                String bodyStr = Base64.getEncoder().encodeToString(buffer.toByteArray());
                messageSender.accept(TunnelMessage.httpResponse(
                    request.requestId(), request.routerConnectionId(), statusCode, responseHeaders, bodyStr));
                return;
            }

            if (request.s3UploadUrl() == null) {
                logger.warn("requestId={} path=ERROR — response exceeded frame limit but no s3UploadUrl provided",
                    request.requestId());
                sendErrorResponse(request, 502);
                return;
            }

            // Overflow detected: drain to temp file to obtain a known Content-Length for the
            // S3 presigned PUT — presigned URLs reject chunked transfer encoding.
            logger.info("requestId={} path=S3_TEMP_FILE buffered={} bytes — no Content-Length, draining to temp file then S3",
                request.requestId(), buffer.size());
            Path tempFile = null;
            try {
                tempFile = drainToTempFile(buffer.toByteArray(), body);
                logger.info("requestId={} temp-file={} size={} bytes",
                    request.requestId(), tempFile.getFileName(), Files.size(tempFile));
                HttpRequest s3Put = HttpRequest.newBuilder()
                    .uri(URI.create(request.s3UploadUrl()))
                    .PUT(HttpRequest.BodyPublishers.ofFile(tempFile))
                    .header("content-type", responseHeaders.getOrDefault("content-type", "application/octet-stream"))
                    .build();
                httpClient.send(s3Put, HttpResponse.BodyHandlers.discarding());
                messageSender.accept(TunnelMessage.httpResponseS3(
                    request.requestId(), request.routerConnectionId(), statusCode, responseHeaders));
            } catch (IOException e) {
                logger.warn("Failed to drain/upload response to S3 for requestId {}: {}", request.requestId(), e.getMessage());
                sendErrorResponse(request, 502);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendErrorResponse(request, 502);
            } finally {
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                }
            }

        } catch (IOException e) {
            logger.warn("Error reading response stream for requestId {}: {}", request.requestId(), e.getMessage());
            sendErrorResponse(request, 502);
        }
    }

    /**
     * Pipes {@code prefix} (may be null) followed by {@code remaining} to a presigned S3 PUT.
     * The pump runs on a separate executor thread; the calling thread blocks on the S3 send.
     * {@code contentLength} should be the total byte count when known, or -1 for chunked transfer.
     */
    private void pumpAndUploadToS3(TunnelMessage request, byte[] prefix, InputStream remaining,
            long contentLength, int statusCode, Map<String, String> responseHeaders) {
        PipedInputStream pipedIn = new PipedInputStream(65536);
        PipedOutputStream pipedOut;
        try {
            pipedOut = new PipedOutputStream(pipedIn);
        } catch (IOException e) {
            logger.warn("Failed to create pipe for S3 upload, requestId {}: {}", request.requestId(), e.getMessage());
            sendErrorResponse(request, 502);
            return;
        }

        executor.submit(() -> {
            try (PipedOutputStream out = pipedOut) {
                if (prefix != null && prefix.length > 0) {
                    out.write(prefix);
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = remaining.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                logger.warn("Error pumping response to S3 pipe for requestId {}: {}", request.requestId(), e.getMessage());
            }
        });

        // Build a body publisher that exposes the known content-length so S3 receives the header.
        final HttpRequest.BodyPublisher s3BodyPublisher;
        if (contentLength >= 0) {
            final long cl = contentLength;
            final InputStream is = pipedIn;
            s3BodyPublisher = new HttpRequest.BodyPublisher() {
                @Override public long contentLength() { return cl; }
                @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                    HttpRequest.BodyPublishers.ofInputStream(() -> is).subscribe(subscriber);
                }
            };
        } else {
            s3BodyPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> pipedIn);
        }

        try {
            HttpRequest s3Put = HttpRequest.newBuilder()
                .uri(URI.create(request.s3UploadUrl()))
                .PUT(s3BodyPublisher)
                .header("content-type", responseHeaders.getOrDefault("content-type", "application/octet-stream"))
                .build();
            httpClient.send(s3Put, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to upload response to S3 for requestId {}: {}", request.requestId(), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            sendErrorResponse(request, 502);
            return;
        }
        messageSender.accept(TunnelMessage.httpResponseS3(
            request.requestId(), request.routerConnectionId(), statusCode, responseHeaders));
    }

    private Path drainToTempFile(byte[] prefix, InputStream remaining) throws IOException {
        Path tempFile = Files.createTempFile("tunnel-response-", ".tmp");
        try (var out = Files.newOutputStream(tempFile)) {
            if (prefix != null && prefix.length > 0) {
                out.write(prefix);
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = remaining.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        return tempFile;
    }

    private long parseContentLength(Map<String, String> responseHeaders) {
        String cl = responseHeaders.get("content-length");
        if (cl == null) return -1;
        try {
            return Long.parseLong(cl);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        params.forEach((key, value) -> {
            if (sb.length() > 1) sb.append('&');
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private Map<String, String> extractResponseHeaders(HttpResponse<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(), values.get(0));
            }
        });
        return headers;
    }

    private void sendErrorResponse(TunnelMessage request, int status) {
        try {
            messageSender.accept(TunnelMessage.httpResponse(
                request.requestId(), request.routerConnectionId(), status,
                Map.of("content-type", "text/plain"), "Gateway error"));
        } catch (Exception e) {
            logger.error("Failed to send error response for requestId {}", request.requestId(), e);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
