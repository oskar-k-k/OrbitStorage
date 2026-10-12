package com.orbitstorage.usage;

import java.util.*;
import com.orbitstorage.project.ProjectService;
import com.orbitstorage.config.OrbitConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UsageService {
    private final JdbcTemplate db;
    private final ProjectService projects;
    private final OrbitConfig config;
    public UsageService(JdbcTemplate db,ProjectService projects,OrbitConfig config) { this.db=db; this.projects=projects; this.config=config; }
    public Map<String,Object> project(UUID id) {
        var p=projects.get(id); Map<String,Object> result=new LinkedHashMap<>(p);
        long billable=((Number)p.get("billable_bytes")).longValue(); long reserved=((Number)p.get("reserved_bytes")).longValue();
        long quota=((Number)p.get("quota_bytes")).longValue();
        result.put("remainingUploadCapacity",Math.max(0,config.blockAt(quota)-billable-reserved));
        result.put("warning",billable+reserved>=quota/100*config.warningPercent);
        result.put("blockingPercent",config.blockingPercent);
        result.put("dailyAdmittedBytes",db.queryForObject("SELECT coalesce(sum(bytes),0) FROM upload_days WHERE project_id=? AND day=(now() AT TIME ZONE 'UTC')::date",Long.class,id));
        result.put("dailyUploadHistory",db.queryForList("SELECT day,bytes AS admitted_bytes,successful_bytes AS uploaded_bytes FROM upload_days WHERE project_id=? ORDER BY day DESC LIMIT 90",id));
        result.put("dailyBudgetResetsAt",java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        result.put("missingFiles",db.queryForObject("SELECT count(*) FROM files WHERE project_id=? AND status='MISSING'",Long.class,id));
        result.put("activeReservations",db.queryForObject("SELECT count(*) FROM files WHERE project_id=? AND status IN ('UPLOADING','CLEANUP')",Long.class,id));
        result.put("billableUsageIsEstimate",true);
        return result;
    }
    public Map<String,Object> overview() {
        var result=new LinkedHashMap<>(db.queryForMap("SELECT coalesce(sum(stored_bytes),0) AS stored_bytes,coalesce(sum(billable_bytes),0) AS estimated_billable_bytes,coalesce(sum(reserved_bytes),0) AS reserved_bytes,coalesce(sum(file_count),0) AS file_count,coalesce(sum(uploaded_bytes),0) AS uploaded_bytes,coalesce(sum(downloaded_bytes),0) AS observed_download_bytes,coalesce(sum(rate_violations),0) AS rate_violations FROM projects"));
        result.put("globalBudget",config.globalBudget); result.put("blockingPercent",config.blockingPercent); result.put("warningPercent",config.warningPercent);
        long billable=((Number)result.get("estimated_billable_bytes")).longValue(), reserved=((Number)result.get("reserved_bytes")).longValue();
        result.put("remainingUploadCapacity",Math.max(0,config.blockAt(config.globalBudget)-billable-reserved));
        result.put("recentUploads",db.queryForList("SELECT id,project_id,actual_size,uploaded_at,status FROM files WHERE status IN ('READY','MISSING') ORDER BY uploaded_at DESC LIMIT 20"));
        result.put("billableUsageIsEstimate",true); return result;
    }
    public List<Map<String,Object>> providers() {
        return db.queryForList("SELECT p.id,p.name,p.kind,p.minimum_billable_bytes,p.available,p.last_reconciled,p.observed_bytes AS reconciled_object_bytes,p.missing_objects,p.mismatched_objects,sum(coalesce(j.stored_bytes,0)) AS internally_tracked_bytes,sum(coalesce(j.billable_bytes,0)) AS estimated_billable_bytes,sum(coalesce(j.reserved_bytes,0)) AS reserved_bytes,CAST(NULL AS bigint) AS provider_reported_bytes FROM providers p LEFT JOIN projects j ON j.provider_id=p.id GROUP BY p.id ORDER BY p.name");
    }
    public List<Map<String,Object>> projects() { return projects.list().stream().map(p -> project((UUID)p.get("id"))).toList(); }
}
