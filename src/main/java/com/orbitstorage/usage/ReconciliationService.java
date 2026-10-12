package com.orbitstorage.usage;

import java.util.UUID;
import com.orbitstorage.provider.ProviderService;
import com.orbitstorage.provider.local.LocalStorageProvider;
import com.orbitstorage.quota.QuotaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ReconciliationService {
    private static final Logger LOG=LoggerFactory.getLogger(ReconciliationService.class);
    private final JdbcTemplate db;
    private final ProviderService providers;
    private final QuotaService quotas;
    public ReconciliationService(JdbcTemplate db,ProviderService providers,QuotaService quotas) { this.db=db; this.providers=providers; this.quotas=quotas; }
    @Scheduled(fixedDelayString="${orbit.reconciliation-delay-ms}",initialDelay=30000)
    public void reconcile() {
        // Session advisory lock prevents two replicas from performing recovery concurrently.
        db.execute((ConnectionCallback<Void>) connection -> {
            try(var query=connection.createStatement(); var rs=query.executeQuery("SELECT pg_try_advisory_lock(73482901)")) {
                rs.next(); if(!rs.getBoolean(1)) return null;
            }
            try {
                for(var provider:db.queryForList("SELECT id FROM providers")) reconcileProvider((UUID)provider.get("id"));
                db.update("DELETE FROM upload_requests WHERE requested_at < now()-interval '2 minutes'");
                db.update("DELETE FROM upload_days WHERE day < (now() AT TIME ZONE 'UTC')::date-90");
            } finally { try(var query=connection.createStatement()) { query.execute("SELECT pg_advisory_unlock(73482901)"); } }
            return null;
        });
    }
    private void reconcileProvider(UUID providerId) {
        long observed=0,missing=0,mismatch=0;
        try {
            var adapter=providers.get(providerId);
            adapter.checkHealth();
            UUID cursor=new UUID(0,0);
            while(true) {
                var rows=db.queryForList("SELECT * FROM files WHERE provider_id=? AND id>? ORDER BY id LIMIT 250",providerId,cursor);
                if(rows.isEmpty()) break;
                for(var file:rows) {
                    UUID id=(UUID)file.get("id"); cursor=id; String key=(String)file.get("object_key"); String status=(String)file.get("status");
                    boolean stale=((java.sql.Timestamp)file.get("uploaded_at")).toInstant().isBefore(java.time.Instant.now().minusSeconds(3600));
                    if(status.equals("CLEANUP") || status.equals("UPLOADING") && stale) {
                        adapter.delete(key); quotas.cancel(id); continue;
                    }
                    if(status.equals("DELETING")) { adapter.delete(key); quotas.finishDelete(id); continue; }
                    if(status.equals("UPLOADING")) continue;
                    var metadata=adapter.getMetadata(key);
                    if(metadata.isEmpty()) {
                        missing++; db.update("UPDATE files SET status='MISSING' WHERE id=? AND status='READY'",id);
                    } else {
                        observed=Math.addExact(observed,metadata.get().size());
                        if(metadata.get().size()!=((Number)file.get("actual_size")).longValue()) {
                            mismatch++; db.update("UPDATE files SET status='MISSING' WHERE id=? AND status='READY'",id);
                        } else db.update("UPDATE files SET status='READY' WHERE id=? AND status='MISSING'",id);
                    }
                }
            }
            if(adapter instanceof LocalStorageProvider local) local.cleanStaging();
            db.update("UPDATE providers SET available=true,last_reconciled=now(),observed_bytes=?,missing_objects=?,mismatched_objects=? WHERE id=?",observed,missing,mismatch,providerId);
        } catch(Exception ex) {
            db.update("UPDATE providers SET available=false WHERE id=?",providerId);
            LOG.warn("Reconciliation failed for provider {} ({})",providerId,ex.getClass().getSimpleName());
        }
    }
}
