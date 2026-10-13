package enterfind.server.model;

public record RemoteAccessConfig(
    String serverId,
    boolean enabled,
    String lastConnected,
    boolean isConnected,
    String statusMessage
) {}
