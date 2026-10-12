package com.orbitstorage.project;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
import com.orbitstorage.auth.Crypto;
import com.orbitstorage.common.ApiException;
import com.orbitstorage.config.OrbitConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    public record CreateProject(@NotBlank @Size(max=120) String name, @NotNull UUID providerId,
        @Positive long quotaBytes, @Positive Long maxFileBytes, @Positive Long dailyUploadBytes,
        @Min(1) @Max(10000) Integer uploadsPerMinute, @Min(1) @Max(50) Integer concurrentUploads,
        @NotEmpty @Size(max=100) Set<@NotBlank @Size(max=200) String> allowedTypes) {}
    public record UpdateProject(@Size(min=1,max=120) String name, @Positive Long quotaBytes,
        @Positive Long maxFileBytes, @Positive Long dailyUploadBytes,
        @Min(1) @Max(10000) Integer uploadsPerMinute, @Min(1) @Max(50) Integer concurrentUploads,
        @Size(min=1,max=100) Set<@NotBlank @Size(max=200) String> allowedTypes) {}
    public record CreateKey(@NotEmpty Set<String> permissions, Instant expiresAt) {}
    private static final Set<String> PERMISSIONS = Set.of("files:read","files:write","files:delete","usage:read");
    private final JdbcTemplate db;
    private final OrbitConfig config;
    public ProjectService(JdbcTemplate db, OrbitConfig config) { this.db=db; this.config=config; }
    public Map<String,Object> get(UUID id) {
        var rows = db.queryForList("SELECT * FROM projects WHERE id=?",id);
        if (rows.isEmpty()) throw new ApiException(404,"Project not found"); return rows.getFirst();
    }
    public List<Map<String,Object>> list() { return db.queryForList("SELECT * FROM projects ORDER BY name"); }
    public Map<String,Object> create(CreateProject req) {
        if (db.queryForObject("SELECT count(*) FROM providers WHERE id=?",Long.class,req.providerId()) == 0) throw new ApiException(404,"Provider not found");
        long max = req.maxFileBytes()==null ? 10485760 : req.maxFileBytes();
        if (max > config.maxRequestBytes) throw new ApiException(400,"Project file limit exceeds global request limit");
        validateTypes(req.allowedTypes());
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO projects(id,name,provider_id,storage_assignment_id,quota_bytes,max_file_bytes,daily_upload_bytes,uploads_per_minute,concurrent_uploads,allowed_types) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id,req.name(),req.providerId(),UUID.randomUUID(),req.quotaBytes(),max,req.dailyUploadBytes()==null?209715200:req.dailyUploadBytes(),
            req.uploadsPerMinute()==null?20:req.uploadsPerMinute(),req.concurrentUploads()==null?2:req.concurrentUploads(),String.join(",",req.allowedTypes()));
        return get(id);
    }
    @Transactional public Map<String,Object> update(UUID id, UpdateProject req) {
        db.queryForObject("SELECT id FROM capacity_lock WHERE id=1 FOR UPDATE",Integer.class);
        if (db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE",id).isEmpty()) throw new ApiException(404,"Project not found");
        if (req.maxFileBytes()!=null && req.maxFileBytes()>config.maxRequestBytes) throw new ApiException(400,"Project file limit exceeds global request limit");
        if (req.allowedTypes()!=null) validateTypes(req.allowedTypes());
        db.update("UPDATE projects SET name=coalesce(?,name),quota_bytes=coalesce(?,quota_bytes),max_file_bytes=coalesce(?,max_file_bytes),daily_upload_bytes=coalesce(?,daily_upload_bytes),uploads_per_minute=coalesce(?,uploads_per_minute),concurrent_uploads=coalesce(?,concurrent_uploads),allowed_types=coalesce(?,allowed_types) WHERE id=?",
            req.name(),req.quotaBytes(),req.maxFileBytes(),req.dailyUploadBytes(),req.uploadsPerMinute(),req.concurrentUploads(),req.allowedTypes()==null?null:String.join(",",req.allowedTypes()),id);
        return get(id);
    }
    public Map<String,Object> createKey(UUID id, CreateKey req) {
        get(id);
        if (!PERMISSIONS.containsAll(req.permissions())) throw new ApiException(400,"Unsupported project permission");
        if (req.expiresAt()!=null && !req.expiresAt().isAfter(Instant.now())) throw new ApiException(400,"Key expiration must be in the future");
        String key=Crypto.randomKey(); UUID keyId=UUID.randomUUID();
        db.update("INSERT INTO api_keys(id,project_id,key_hash,permissions,expires_at) VALUES (?,?,?,?,?)",keyId,id,Crypto.hash(key),String.join(",",req.permissions()),req.expiresAt()==null?null:java.sql.Timestamp.from(req.expiresAt()));
        return Map.of("id",keyId,"key",key,"permissions",req.permissions());
    }
    public List<Map<String,Object>> keys(UUID id) { get(id); return db.queryForList("SELECT id,permissions,created_at,expires_at,revoked FROM api_keys WHERE project_id=? ORDER BY created_at DESC",id); }
    public void revoke(UUID project, UUID key) {
        if (db.update("UPDATE api_keys SET revoked=true WHERE id=? AND project_id=?",key,project)==0) throw new ApiException(404,"API key not found");
    }
    private void validateTypes(Set<String> types) {
        for (String type:types) {
            if (type.contains(",") || (!type.equals("*") && !type.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+*-]+"))) throw new ApiException(400,"Invalid allowed content type");
        }
    }
}
