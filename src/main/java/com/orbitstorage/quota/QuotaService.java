package com.orbitstorage.quota;

import java.util.*;
import java.time.Instant;
import com.orbitstorage.common.ApiException;
import com.orbitstorage.config.OrbitConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuotaService {
    public record Reservation(UUID id, UUID projectId, UUID providerId, String objectKey, long size, long billableSize) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final OrbitConfig config;
    public QuotaService(JdbcTemplate db, TransactionTemplate tx, OrbitConfig config) { this.db=db; this.tx=tx; this.config=config; }
    public Reservation reserve(UUID project, long size, String filename, String type) {
        return tx.execute(status -> {
            lock();
            var rows=db.queryForList("SELECT * FROM projects WHERE id=? FOR UPDATE",project);
            if (rows.isEmpty()) throw new ApiException(404,"Project not found");
            var p=rows.getFirst(); UUID provider=(UUID)p.get("provider_id");
            if (size<=0) throw new ApiException(411,"A positive Content-Length is required");
            if (size>config.maxRequestBytes || size>number(p,"max_file_bytes")) throw new ApiException(413,"File exceeds upload size limit");
            Set<String> allowed=Set.of(((String)p.get("allowed_types")).split(","));
            if (!allowed.contains("*") && !allowed.contains(type) && !allowed.contains(type.split("/")[0]+"/*")) throw new ApiException(415,"Content type is not allowed");
            long minimum=db.queryForObject("SELECT minimum_billable_bytes FROM providers WHERE id=?",Long.class,provider);
            long billable=Math.max(size,minimum);
            long active=db.queryForObject("SELECT count(*) FROM files WHERE project_id=? AND status IN ('UPLOADING','CLEANUP')",Long.class,project);
            if (active>=number(p,"concurrent_uploads")) throw new ApiException(429,"Concurrent upload limit exceeded",10);
            long projectLimit=config.blockAt(number(p,"quota_bytes"));
            if (billable>projectLimit-number(p,"billable_bytes")-number(p,"reserved_bytes")) throw new ApiException(409,"Project storage quota exceeded");
            long global=db.queryForObject("SELECT coalesce(sum(billable_bytes+reserved_bytes),0) FROM projects",Long.class);
            if (billable>config.blockAt(config.globalBudget)-global) throw new ApiException(409,"Global storage budget exceeded");
            db.update("INSERT INTO upload_days(project_id,day) VALUES (?,(now() AT TIME ZONE 'UTC')::date) ON CONFLICT DO NOTHING",project);
            long daily=db.queryForObject("SELECT bytes FROM upload_days WHERE project_id=? AND day=(now() AT TIME ZONE 'UTC')::date",Long.class,project);
            if (size>number(p,"daily_upload_bytes")-daily) throw new ApiException(429,"Daily upload budget exceeded",secondsUntilUtcMidnight());
            UUID id=UUID.randomUUID(); String key=project+"/"+id;
            db.update("INSERT INTO files(id,project_id,provider_id,storage_assignment_id,object_key,original_name,content_type,actual_size,billable_size,status) VALUES (?,?,?,?,?,?,?,?,?,'UPLOADING')",id,project,provider,p.get("storage_assignment_id"),key,filename,type,size,billable);
            db.update("UPDATE projects SET reserved_bytes=reserved_bytes+? WHERE id=?",billable,project);
            // Admission consumes daily allowance even on failed uploads, preventing repeated failed-stream abuse.
            db.update("UPDATE upload_days SET bytes=bytes+? WHERE project_id=? AND day=(now() AT TIME ZONE 'UTC')::date",size,project);
            return new Reservation(id,project,provider,key,size,billable);
        });
    }
    public void finish(Reservation r) {
        tx.executeWithoutResult(status -> {
            lock();
            if (db.update("UPDATE files SET status='READY',uploaded_at=now() WHERE id=? AND status='UPLOADING'",r.id())!=1) throw new ApiException(409,"Upload reservation is no longer active");
            db.update("UPDATE projects SET reserved_bytes=reserved_bytes-?,stored_bytes=stored_bytes+?,billable_bytes=billable_bytes+?,file_count=file_count+1,uploaded_bytes=uploaded_bytes+? WHERE id=?",
                r.billableSize(),r.size(),r.billableSize(),r.size(),r.projectId());
            db.update("INSERT INTO upload_days(project_id,day) VALUES (?,(now() AT TIME ZONE 'UTC')::date) ON CONFLICT DO NOTHING",r.projectId());
            db.update("UPDATE upload_days SET successful_bytes=successful_bytes+? WHERE project_id=? AND day=(now() AT TIME ZONE 'UTC')::date",r.size(),r.projectId());
        });
    }
    public void cleanupPending(UUID id) { db.update("UPDATE files SET status='CLEANUP' WHERE id=? AND status='UPLOADING'",id); }
    public void cancel(UUID id) {
        tx.executeWithoutResult(status -> {
            lock();
            var rows=db.queryForList("SELECT project_id,billable_size FROM files WHERE id=? AND status IN ('UPLOADING','CLEANUP') FOR UPDATE",id);
            if (rows.isEmpty()) return;
            var r=rows.getFirst();
            db.update("UPDATE projects SET reserved_bytes=reserved_bytes-? WHERE id=?",r.get("billable_size"),r.get("project_id"));
            db.update("DELETE FROM files WHERE id=?",id);
        });
    }
    public boolean markDeleting(UUID id) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            lock(); return db.update("UPDATE files SET status='DELETING' WHERE id=? AND status IN ('READY','MISSING')",id)==1;
        }));
    }
    public void finishDelete(UUID id) {
        tx.executeWithoutResult(status -> {
            lock();
            var rows=db.queryForList("SELECT * FROM files WHERE id=? AND status='DELETING' FOR UPDATE",id);
            if (rows.isEmpty()) return; var r=rows.getFirst();
            db.update("UPDATE projects SET stored_bytes=stored_bytes-?,billable_bytes=billable_bytes-?,file_count=file_count-1 WHERE id=?",r.get("actual_size"),r.get("billable_size"),r.get("project_id"));
            db.update("DELETE FROM files WHERE id=?",id);
        });
    }
    private void lock() { db.queryForObject("SELECT id FROM capacity_lock WHERE id=1 FOR UPDATE",Integer.class); }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private long secondsUntilUtcMidnight() {
        return java.time.Duration.between(Instant.now(),java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC)).toSeconds()+1;
    }
}
