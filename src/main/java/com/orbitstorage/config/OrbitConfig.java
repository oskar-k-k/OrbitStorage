package com.orbitstorage.config;

import java.nio.file.Path;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrbitConfig {
    public final String adminKey;
    public final String monitoringKey;
    public final byte[] encryptionKey;
    public final Path localRoot;
    public final long globalBudget;
    public final long maxRequestBytes;
    public final int warningPercent;
    public final int blockingPercent;

    public OrbitConfig(@Value("${orbit.admin-key}") String adminKey,
            @Value("${orbit.monitoring-key}") String monitoringKey,
            @Value("${orbit.encryption-key}") String encryptionKey,
            @Value("${orbit.local-root}") String localRoot,
            @Value("${orbit.global-budget}") long globalBudget,
            @Value("${orbit.max-request-bytes}") long maxRequestBytes,
            @Value("${orbit.warning-percent}") int warningPercent,
            @Value("${orbit.blocking-percent}") int blockingPercent) {
        if (adminKey.length() < 32 || adminKey.length() > 480 || (!monitoringKey.isEmpty() && (monitoringKey.length() < 32 || monitoringKey.length() > 480))
                || adminKey.equals(monitoringKey)) throw new IllegalArgumentException("Use distinct, random admin/monitoring keys of at least 32 characters");
        this.encryptionKey = Base64.getDecoder().decode(encryptionKey);
        if (this.encryptionKey.length != 32) throw new IllegalArgumentException("Encryption key must be 32 bytes, Base64 encoded");
        if (globalBudget <= 0 || maxRequestBytes <= 0 || warningPercent <= 0
                || blockingPercent > 100 || warningPercent >= blockingPercent) throw new IllegalArgumentException("Invalid storage limits");
        this.adminKey = adminKey; this.monitoringKey = monitoringKey;
        this.localRoot = Path.of(localRoot).toAbsolutePath().normalize();
        this.globalBudget = globalBudget; this.maxRequestBytes = maxRequestBytes;
        this.warningPercent = warningPercent; this.blockingPercent = blockingPercent;
    }
    public long blockAt(long quota) { return quota / 100 * blockingPercent + quota % 100 * blockingPercent / 100; }
}
