package enterfind.server.model;

public record UsageEntry(
    String serverId,
    UsageGranularity granularity,
    String period,
    int ingressBytes,
    int egressBytes
) {}
