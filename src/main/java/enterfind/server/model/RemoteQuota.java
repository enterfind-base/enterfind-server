package enterfind.server.model;

public record RemoteQuota(
    String serverId,
    float monthlyQuotaPercentUsed
) {}
