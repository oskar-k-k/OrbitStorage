package com.orbitstorage.provider;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.*;
import tools.jackson.databind.ObjectMapper;
import com.orbitstorage.auth.Crypto;
import com.orbitstorage.common.ApiException;
import com.orbitstorage.config.OrbitConfig;
import com.orbitstorage.provider.api.StorageProvider;
import com.orbitstorage.provider.local.LocalStorageProvider;
import com.orbitstorage.provider.s3.S3StorageProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProviderService {
    public record S3Config(String endpoint, @NotBlank String region, @NotBlank String bucket,
        @NotBlank String accessKey, @NotBlank String secretKey, boolean pathStyle) {}
    public record CreateProvider(@NotBlank @Size(max=120) String name, @Pattern(regexp="LOCAL|S3") @NotNull String kind,
        @Min(0) long minimumBillableBytes, @jakarta.validation.Valid S3Config s3) {}
    private final JdbcTemplate db;
    private final Crypto crypto;
    private final OrbitConfig config;
    private final ObjectMapper json;
    private final Map<UUID, StorageProvider> adapters = new ConcurrentHashMap<>();
    public ProviderService(JdbcTemplate db, Crypto crypto, OrbitConfig config, ObjectMapper json) {
        this.db=db; this.crypto=crypto; this.config=config; this.json=json;
    }
    public Map<String,Object> create(CreateProvider request) {
        if (request.kind().equals("S3") && request.s3() == null) throw new ApiException(400, "S3 configuration required");
        if (request.s3() != null && request.s3().endpoint() != null && !request.s3().endpoint().isBlank()) {
            var uri = java.net.URI.create(request.s3().endpoint());
            if (uri.getHost() == null || !java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null)
                throw new ApiException(400, "Invalid S3 endpoint");
        }
        UUID id = UUID.randomUUID();
        String encrypted = crypto.encrypt(request.kind().equals("LOCAL") ? "{}" : json.writeValueAsString(request.s3()));
        db.update("INSERT INTO providers(id,name,kind,encrypted_config,minimum_billable_bytes) VALUES (?,?,?,?,?)", id, request.name(), request.kind(), encrypted, request.minimumBillableBytes());
        return Map.of("id",id,"name",request.name(),"kind",request.kind(),"minimumBillableBytes",request.minimumBillableBytes());
    }
    public StorageProvider get(UUID id) {
        return adapters.computeIfAbsent(id, providerId -> {
            var rows = db.queryForList("SELECT kind, encrypted_config FROM providers WHERE id=?", providerId);
            if (rows.isEmpty()) throw new ApiException(404, "Provider not found");
            var row = rows.getFirst();
            try {
                if (row.get("kind").equals("LOCAL")) return new LocalStorageProvider(config.localRoot.resolve(providerId.toString()));
                return new S3StorageProvider(json.readValue(crypto.decrypt((String)row.get("encrypted_config")), S3Config.class));
            } catch (Exception ex) { throw new ApiException(503, "Provider initialization failed"); }
        });
    }
    public java.util.List<Map<String,Object>> list() {
        return db.queryForList("SELECT id,name,kind,minimum_billable_bytes,available,last_reconciled FROM providers ORDER BY name");
    }
    @PreDestroy public void close() { adapters.values().forEach(StorageProvider::close); }
    public java.util.List<Map<String,Object>> storageTargets() {
        var rows = db.queryForList("SELECT id,name,kind,minimum_billable_bytes,available,last_reconciled,encrypted_config FROM providers ORDER BY name,id");
        for(var row:rows) {
            String encrypted=(String)row.remove("encrypted_config");
            if(row.get("kind").equals("S3")) {
                try {
                    var s3=json.readValue(crypto.decrypt(encrypted), S3Config.class);
                    row.put("bucket",s3.bucket()); row.put("region",s3.region()); row.put("endpoint",s3.endpoint());
                } catch(Exception ex) { throw new ApiException(503,"Storage configuration could not be read"); }
            }
        }
        return rows;
    }
}
