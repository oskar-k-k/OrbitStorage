package com.orbitstorage.ratelimit;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import io.github.bucket4j.Bucket;
import com.orbitstorage.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UploadRateLimiter {
    private record Limit(int capacity, Bucket bucket) {}
    private final ConcurrentHashMap<UUID,Limit> buckets = new ConcurrentHashMap<>();
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public UploadRateLimiter(JdbcTemplate db, TransactionTemplate tx) { this.db=db; this.tx=tx; }
    public void check(UUID project) {
        boolean allowed = Boolean.TRUE.equals(tx.execute(status -> {
            var rows=db.queryForList("SELECT uploads_per_minute FROM projects WHERE id=? FOR UPDATE",project);
            if (rows.isEmpty()) throw new ApiException(404,"Project not found");
            int capacity=((Number)rows.getFirst().get("uploads_per_minute")).intValue();
            Limit limit=buckets.compute(project,(id,old) -> old!=null && old.capacity()==capacity ? old :
                new Limit(capacity,Bucket.builder().addLimit(b -> b.capacity(capacity).refillIntervally(capacity,Duration.ofMinutes(1))).build()));
            // Durable rolling window prevents restarts or additional replicas from bypassing the limit.
            long recent=db.queryForObject("SELECT count(*) FROM upload_requests WHERE project_id=? AND requested_at > now()-interval '1 minute'",Long.class,project);
            if (recent>=capacity || !limit.bucket().tryConsume(1)) {
                db.update("UPDATE projects SET rate_violations=rate_violations+1 WHERE id=?",project); return false;
            }
            db.update("INSERT INTO upload_requests(project_id) VALUES (?)",project); return true;
        }));
        if (!allowed) throw new ApiException(429,"Upload rate limit exceeded",60);
    }
}
