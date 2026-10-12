package com.orbitstorage.file;

import java.io.InputStream;
import java.util.UUID;
import com.orbitstorage.common.ApiException;
import com.orbitstorage.provider.ProviderService;
import com.orbitstorage.quota.QuotaService;
import com.orbitstorage.ratelimit.UploadRateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class FileService {
    private final FileRepository files;
    private final ProviderService providers;
    private final QuotaService quotas;
    private final UploadRateLimiter limiter;
    private final JdbcTemplate db;
    public FileService(FileRepository files,ProviderService providers,QuotaService quotas,UploadRateLimiter limiter,JdbcTemplate db) {
        this.files=files; this.providers=providers; this.quotas=quotas; this.limiter=limiter; this.db=db;
    }
    public FileRecord get(UUID project,UUID id) { return files.findByIdAndProjectId(id,project).orElseThrow(() -> new ApiException(404,"File not found")); }
    public FileRecord.Metadata upload(UUID project,String filename,String type,long size,InputStream source) {
        limiter.check(project);
        if(filename==null || filename.isBlank() || filename.length()>255 || filename.chars().anyMatch(c -> c<32 || c==127) || filename.contains("/") || filename.contains("\\")) throw new ApiException(400,"X-Filename must be a plain filename of at most 255 characters");
        String contentType;
        try { var parsed=org.springframework.http.MediaType.parseMediaType(type==null?"application/octet-stream":type);
            if(parsed.isWildcardType() || parsed.isWildcardSubtype()) throw new IllegalArgumentException();
            contentType=parsed.getType().toLowerCase(java.util.Locale.ROOT)+"/"+parsed.getSubtype().toLowerCase(java.util.Locale.ROOT);
            if(contentType.length()>255) throw new IllegalArgumentException();
        } catch(Exception ex) { throw new ApiException(415,"Invalid Content-Type"); }
        var reservation=quotas.reserve(project,size,filename,contentType);
        // The servlet owns the request stream. Closing it after a committed upload must not trigger rollback cleanup.
        var limited=new LimitedUploadStream(source,size);
        try {
            providers.get(reservation.providerId()).upload(reservation.objectKey(),limited,size,contentType);
            limited.verifyComplete();
            quotas.finish(reservation);
        } catch(Exception ex) {
            quotas.cleanupPending(reservation.id());
            try { providers.get(reservation.providerId()).delete(reservation.objectKey()); quotas.cancel(reservation.id()); }
            catch(Exception cleanup) { /* Reconciliation retries; capacity remains reserved until deletion is confirmed. */ }
            throw new ApiException(503,"Upload failed; incomplete object cleanup will be retried if necessary");
        }
        return get(project,reservation.id()).metadata();
    }
    public InputStream download(FileRecord file) {
        if(!file.status.equals("READY")) throw new ApiException(409,"File is not available");
        try { return providers.get(file.providerId).download(file.objectKey); }
        catch(Exception ex) { throw new ApiException(503,"Storage provider is unavailable"); }
    }
    public void downloaded(UUID project,long bytes) { db.update("UPDATE projects SET downloaded_bytes=downloaded_bytes+? WHERE id=?",bytes,project); }
    public void delete(UUID project,UUID id) {
        var file=get(project,id);
        if(!quotas.markDeleting(id) && !file.status.equals("DELETING")) throw new ApiException(409,"File operation already in progress");
        try { providers.get(file.providerId).delete(file.objectKey); quotas.finishDelete(id); }
        catch(Exception ex) { throw new ApiException(503,"Deletion pending; reconciliation will retry"); }
    }
}
