package enterfind.server.util;

import org.springframework.stereotype.Component;

import enterfind.server.service.RemoteAccessService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The URL a client reached this instance at, for building absolute links back to it.
 *
 * <p>When a request arrives via the remote-access tunnel, the {@code X-Enterfind-Tunnel-Proxy}
 * header is present (injected by {@link enterfind.server.service.tunnel.TunnelRequestHandler}), and
 * the URL is the per-server host {@code https://<serverId>.remote-<stage>.enterfind.com}, from live
 * values in {@link RemoteAccessService} and {@link ProfileHelper}. Otherwise it is the request's own
 * scheme, host and port, which behind a reverse proxy reflect the forwarded values when
 * {@code server.forward-headers-strategy} is set.
 */
@Component
public class PublicBaseUrl {

    private final RemoteAccessService remoteAccessService;
    private final ProfileHelper profileHelper;

    public PublicBaseUrl(RemoteAccessService remoteAccessService, ProfileHelper profileHelper) {
        this.remoteAccessService = remoteAccessService;
        this.profileHelper = profileHelper;
    }

    public String of(HttpServletRequest request) {
        if (viaRemoteAccess(request)) {
            String stage = profileHelper.getActiveProfile();
            String serverId = remoteAccessService.getConfig().serverId();
            return "https://" + serverId + ".remote-" + stage + ".enterfind.com";
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean isDefaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (isDefaultPort ? "" : ":" + port);
    }

    /**
     * Whether the request was relayed through the remote-access tunnel. Any client can send the
     * header, so this only chooses how to address the instance back, never what a caller may do.
     */
    public boolean viaRemoteAccess(HttpServletRequest request) {
        return "1".equals(request.getHeader("X-Enterfind-Tunnel-Proxy"));
    }
}
