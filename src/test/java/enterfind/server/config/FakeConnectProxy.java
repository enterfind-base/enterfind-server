package enterfind.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An HTTP CONNECT proxy on a random loopback port. Records the request line of every request it
 * receives. With a backend set it answers CONNECT with 200 and pipes bytes to that backend whatever the
 * requested target — so a test can dial a hostname that does not resolve and still reach a local
 * server. Without one it refuses everything with 403.
 */
public class FakeConnectProxy implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile InetSocketAddress backend;

    public FakeConnectProxy() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("fake-connect-proxy").start(this::acceptLoop);
    }

    /** Pipe every CONNECT to this address instead of refusing. */
    public FakeConnectProxy forwardTo(InetSocketAddress backend) {
        this.backend = backend;
        return this;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Request lines received so far, e.g. {@code CONNECT tunnel.invalid:443 HTTP/1.1}. */
    public List<String> requests() {
        return requests;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().start(() -> handle(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String head = readHead(in);
            requests.add(head.lines().findFirst().orElse(""));
            InetSocketAddress target = backend;
            if (target == null || !head.startsWith("CONNECT ")) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            try (Socket upstream = new Socket(target.getAddress(), target.getPort())) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread pump = Thread.ofVirtual().start(() -> {
                    try {
                        in.transferTo(upstream.getOutputStream());
                    } catch (IOException ignored) {
                    }
                });
                upstream.getInputStream().transferTo(out);
                pump.join(1000);
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    /** Reads up to and including the blank line ending the request head, and not a byte further. */
    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            head.append((char) c);
            if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) {
                break;
            }
        }
        return head.toString();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
