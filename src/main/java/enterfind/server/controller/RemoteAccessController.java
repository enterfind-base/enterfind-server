package enterfind.server.controller;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;

import enterfind.server.model.RemoteAccessConfig;
import enterfind.server.model.RemoteQuota;
import enterfind.server.model.UsageEntry;
import enterfind.server.security.AuthContext;
import enterfind.server.service.RemoteAccessService;
import reactor.core.publisher.Flux;

@Controller
public class RemoteAccessController {

    private static final Logger logger = LoggerFactory.getLogger(RemoteAccessController.class);

    private final RemoteAccessService remoteAccessService;
    private final AuthContext authContext;

    public RemoteAccessController(RemoteAccessService remoteAccessService, AuthContext authContext) {
        this.remoteAccessService = remoteAccessService;
        this.authContext = authContext;
    }

    @QueryMapping
    public RemoteAccessConfig remoteAccessConfig(@Argument String token) {
        authContext.requireAdmin(token);
        return remoteAccessService.getConfig();
    }

    @SubscriptionMapping
    public Flux<RemoteAccessConfig> remoteAccessConfigUpdates(@Argument String token) {
        authContext.requireAdmin(token);
        logger.info("Remote access config subscription started");
        return Flux.merge(
            Flux.just(remoteAccessService.getConfig()),
            remoteAccessService.configUpdates(),
            Flux.interval(Duration.ofSeconds(30)).map(tick -> remoteAccessService.getConfig())
        )
        .doOnNext(config -> logger.debug("Sending config update to subscriber: enabled={}, isConnected={}", config.enabled(), config.isConnected()))
        .doOnCancel(() -> logger.info("Remote access config subscription cancelled"))
        .doOnTerminate(() -> logger.info("Remote access config subscription terminated"));
    }

    @MutationMapping
    public RemoteAccessConfig setRemoteAccessEnabled(@Argument String token, @Argument boolean enabled,
            @Argument String inviteCode) {
        authContext.requireMutationAdmin(token);
        // Deliberately not checked for shape: the tunnel owns what a valid invite code looks like,
        // and a second opinion here could only ever diverge from it.
        remoteAccessService.setRemoteAccessEnabled(enabled, inviteCode);
        return remoteAccessService.getConfig();
    }

    @MutationMapping
    public RemoteAccessConfig reenrollRemoteAccess(@Argument String token) {
        authContext.requireMutationAdmin(token);
        return remoteAccessService.reenroll();
    }

    @QueryMapping
    public List<UsageEntry> remoteUsage(@Argument String token) {
        authContext.requireAdmin(token);
        return List.of();
    }

    @QueryMapping
    public RemoteQuota remoteQuota(@Argument String token) {
        authContext.requireAdmin(token);
        return null;
    }
}
