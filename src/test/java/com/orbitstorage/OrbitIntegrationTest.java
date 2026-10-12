package com.orbitstorage;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.file.Files;
import tools.jackson.databind.ObjectMapper;
import com.orbitstorage.auth.Crypto;
import com.orbitstorage.common.ApiException;
import com.orbitstorage.file.*;
import com.orbitstorage.provider.ProviderService;
import com.orbitstorage.provider.api.StorageProvider;
import com.orbitstorage.project.ProjectService;
import com.orbitstorage.quota.QuotaService;
import com.orbitstorage.ratelimit.UploadRateLimiter;
import com.orbitstorage.usage.ReconciliationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrbitIntegrationTest {
    static final String ADMIN="test-admin-key-with-32-random-characters";
    static final String MONITOR="test-monitor-key-with-32-random-characters";
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl); p.add("spring.datasource.username",POSTGRES::getUsername); p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("orbit.admin-key",() -> ADMIN); p.add("orbit.monitoring-key",() -> MONITOR);
        p.add("orbit.encryption-key",() -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("orbit.global-budget",() -> 10000); p.add("orbit.max-request-bytes",() -> 10000);
        p.add("orbit.reconciliation-delay-ms",() -> 3600000);
        p.add("orbit.local-root",() -> { try { return Files.createTempDirectory("orbit-tests").toString(); } catch(IOException ex) { throw new UncheckedIOException(ex); } });
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ProjectService projects;
    @Autowired ProviderService providers;
    @Autowired QuotaService quotas;
    @Autowired UploadRateLimiter limiter;
    @Autowired FileService files;
    @Autowired FileRepository repository;
    @Autowired ReconciliationService reconciliation;
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void resetDatabase() { db.execute("TRUNCATE providers CASCADE"); }
    UUID project(long quota,long max,long daily,int rate,int concurrency) {
        UUID provider=(UUID)providers.create(new ProviderService.CreateProvider("local","LOCAL",0,null)).get("id");
        return (UUID)projects.create(new ProjectService.CreateProject("test",provider,quota,max,daily,rate,concurrency,Set.of("text/plain"))).get("id");
    }
    String key(UUID project,String... permissions) { return (String)projects.createKey(project,new ProjectService.CreateKey(Set.of(permissions),null)).get("key"); }
    HttpResponse<byte[]> request(String method,String path,String key,byte[] body,String type,String filename) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(key!=null) request.header("Authorization","Bearer "+key);
        if(type!=null) request.header("Content-Type",type);
        if(filename!=null) request.header("X-Filename",filename);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @SuppressWarnings("unchecked") Map<String,Object> body(HttpResponse<byte[]> response) { return json.readValue(response.body(),Map.class); }
    String path(UUID project) { return "/api/v1/projects/"+project+"/files"; }
    @Test void fileLifecycleHeadersUsageAndProjectIsolation() throws Exception {
        UUID a=project(1000,100,1000,20,2),b=project(1000,100,1000,20,2);
        String aKey=key(a,"files:read","files:write","files:delete","usage:read"),bKey=key(b,"files:read");
        byte[] content="hello".getBytes();
        var uploaded=request("POST",path(a),aKey,content,"text/plain","hello.txt"); assertEquals(201,uploaded.statusCode());
        String id=(String)body(uploaded).get("id");
        var download=request("GET",path(a)+"/"+id,aKey,null,null,null); assertEquals(200,download.statusCode()); assertArrayEquals(content,download.body());
        assertTrue(download.headers().firstValue("content-disposition").orElseThrow().contains("attachment"));
        assertEquals("nosniff",download.headers().firstValue("x-content-type-options").orElseThrow());
        assertEquals(403,request("GET",path(a)+"/"+id,bKey,null,null,null).statusCode());
        assertEquals(404,request("GET",path(b)+"/"+id,bKey,null,null,null).statusCode());
        assertEquals(200,request("GET",path(a)+"/"+id+"/metadata",aKey,null,null,null).statusCode());
        var usage=body(request("GET","/api/v1/projects/"+a+"/usage",aKey,null,null,null));
        assertEquals(5,((Number)usage.get("stored_bytes")).intValue()); assertEquals(5,((Number)usage.get("downloaded_bytes")).intValue());
        assertEquals(204,request("DELETE",path(a)+"/"+id,aKey,null,null,null).statusCode());
        usage=body(request("GET","/api/v1/projects/"+a+"/usage",aKey,null,null,null));
        assertEquals(0,((Number)usage.get("stored_bytes")).intValue()); assertEquals(5,((Number)usage.get("dailyAdmittedBytes")).intValue());
        assertEquals(404,request("GET",path(a)+"/"+id,aKey,null,null,null).statusCode());
    }
    @Test void authenticationPermissionsRevocationAndMonitoringAreSeparate() throws Exception {
        UUID project=project(1000,100,1000,20,2);
        String read=key(project,"files:read");
        assertEquals(401,request("GET","/api/v1/admin/projects",null,null,null,null).statusCode());
        assertEquals(401,request("GET","/api/v1/admin/projects","invalid",null,null,null).statusCode());
        assertEquals(403,request("GET","/api/v1/admin/projects",read,null,null,null).statusCode());
        assertEquals(403,request("POST",path(project),read,new byte[1],"text/plain","a.txt").statusCode());
        assertEquals(403,request("POST",path(project),ADMIN,new byte[1],"text/plain","a.txt").statusCode());
        assertEquals(200,request("GET","/api/v1/admin/storage/overview",MONITOR,null,null,null).statusCode());
        assertEquals(403,request("GET","/api/v1/admin/projects",MONITOR,null,null,null).statusCode());
        var rotated=projects.createKey(project,new ProjectService.CreateKey(Set.of("files:read"),null));
        assertNotEquals(read,rotated.get("key"));
        var old=db.queryForObject("SELECT id FROM api_keys WHERE key_hash=?",UUID.class,Crypto.hash(read)); projects.revoke(project,old);
        assertEquals(401,request("GET",path(project)+"/"+UUID.randomUUID(),read,null,null,null).statusCode());
        assertEquals(404,request("GET",path(project)+"/"+UUID.randomUUID(),(String)rotated.get("key"),null,null,null).statusCode());
        assertEquals(200,request("GET","/actuator/health",null,null,null,null).statusCode());
        assertEquals(200,request("GET","/v3/api-docs",ADMIN,null,null,null).statusCode());
    }
    @Test void uploadLimitsRateAndDailyBudget() throws Exception {
        UUID project=project(1000,10,15,20,2); String write=key(project,"files:write","files:delete");
        assertEquals(413,request("POST",path(project),write,new byte[11],"text/plain","a.txt").statusCode());
        assertEquals(415,request("POST",path(project),write,new byte[1],"image/png","a.png").statusCode());
        assertEquals(400,request("POST",path(project),write,new byte[1],"text/plain","../x").statusCode());
        assertEquals(400,request("POST",path(project),write,new byte[1],"text/plain",null).statusCode());
        var upload=request("POST",path(project),write,new byte[10],"text/plain","a.txt"); assertEquals(201,upload.statusCode());
        assertEquals(204,request("DELETE",path(project)+"/"+body(upload).get("id"),write,null,null,null).statusCode());
        var blocked=request("POST",path(project),write,new byte[6],"text/plain","a.txt"); assertEquals(429,blocked.statusCode()); assertTrue(blocked.headers().firstValue("retry-after").isPresent());
        UUID limited=project(1000,10,1000,1,2); String limitedKey=key(limited,"files:write");
        assertEquals(201,request("POST",path(limited),limitedKey,new byte[1],"text/plain","a.txt").statusCode());
        assertEquals(429,request("POST",path(limited),limitedKey,new byte[1],"text/plain","b.txt").statusCode());
    }
    @Test void concurrentReservationsCannotBypassQuota() throws Exception {
        UUID project=project(1000,600,10000,20,10); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(8)) {
            List<Future<QuotaService.Reservation>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(executor.submit(() -> { start.await(); try { return quotas.reserve(project,600,"a.txt","text/plain"); } catch(ApiException ex) { assertEquals(409,ex.status); return null; } }));
            start.countDown(); List<QuotaService.Reservation> admitted=new ArrayList<>();
            for(var future:futures) { var reservation=future.get(20,TimeUnit.SECONDS); if(reservation!=null) admitted.add(reservation); }
            assertEquals(1,admitted.size()); assertEquals(600,((Number)projects.get(project).get("reserved_bytes")).intValue());
            quotas.cancel(admitted.getFirst().id()); assertEquals(0,((Number)projects.get(project).get("reserved_bytes")).intValue());
        }
    }
    @Test void globalBudgetAndConcurrencyApplyAcrossReservations() {
        UUID a=project(20000,6000,20000,20,1),b=project(20000,6000,20000,20,2);
        var reservation=quotas.reserve(a,6000,"a.txt","text/plain");
        assertEquals(429,assertThrows(ApiException.class,() -> quotas.reserve(a,1,"b.txt","text/plain")).status);
        assertEquals(409,assertThrows(ApiException.class,() -> quotas.reserve(b,4000,"b.txt","text/plain")).status);
        quotas.cancel(reservation.id()); var other=quotas.reserve(b,4000,"b.txt","text/plain"); quotas.cancel(other.id());
    }
    @Test void failedUploadCleanupReservationRecoveryAndMissingObjectDetection() throws Exception {
        UUID project=project(1000,100,1000,20,2);
        var mockProviders=mock(ProviderService.class); var adapter=mock(StorageProvider.class);
        when(mockProviders.get(any())).thenReturn(adapter);
        doThrow(new IOException("provider unavailable")).when(adapter).upload(anyString(),any(),anyLong(),anyString());
        doThrow(new IOException("delete unavailable")).when(adapter).delete(anyString());
        var service=new FileService(repository,mockProviders,quotas,limiter,db);
        assertEquals(503,assertThrows(ApiException.class,() -> service.upload(project,"a.txt","text/plain",10,new ByteArrayInputStream(new byte[10]))).status);
        assertEquals(10,((Number)projects.get(project).get("reserved_bytes")).intValue());
        assertEquals("CLEANUP",db.queryForObject("SELECT status FROM files",String.class));
        reconciliation.reconcile(); assertEquals(0,((Number)projects.get(project).get("reserved_bytes")).intValue());
        doNothing().when(adapter).delete(anyString());
        assertThrows(ApiException.class,() -> service.upload(project,"b.txt","text/plain",10,new ByteArrayInputStream(new byte[10])));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM files",Integer.class));
        var stale=quotas.reserve(project,10,"stale.txt","text/plain");
        db.update("UPDATE files SET uploaded_at=now()-interval '2 hours' WHERE id=?",stale.id()); reconciliation.reconcile();
        assertEquals(0,((Number)projects.get(project).get("reserved_bytes")).intValue());
        var ready=files.upload(project,"ready.txt","text/plain",5,new ByteArrayInputStream(new byte[5]));
        var record=files.get(project,ready.id()); providers.get(record.providerId).delete(record.objectKey);
        reconciliation.reconcile(); assertEquals("MISSING",files.get(project,ready.id()).status);
        assertEquals(5,((Number)projects.get(project).get("stored_bytes")).intValue());
        files.delete(project,ready.id()); assertEquals(0,((Number)projects.get(project).get("stored_bytes")).intValue());
    }
    @Test void providerSecretsAreEncryptedAndNeverReturned() throws Exception {
        var provider=providers.create(new ProviderService.CreateProvider("s3","S3",128,new ProviderService.S3Config("https://example.com","us-east-1","bucket","access-secret","secret-secret",true)));
        String encrypted=db.queryForObject("SELECT encrypted_config FROM providers WHERE id=?",String.class,provider.get("id"));
        assertFalse(encrypted.contains("secret"));
        var response=request("GET","/api/v1/admin/storage/providers",ADMIN,null,null,null);
        assertEquals(200,response.statusCode()); String payload=new String(response.body());
        assertFalse(payload.contains("secret-secret")); assertFalse(payload.contains("encrypted_config"));
        assertTrue(payload.contains("provider_reported_bytes"));
    }
    @Test void administrationApiAndBillableFloor() throws Exception {
        var providerResponse=request("POST","/api/v1/admin/providers",ADMIN,
            json.writeValueAsBytes(Map.of("name","local","kind","LOCAL","minimumBillableBytes",128)),"application/json",null);
        assertEquals(201,providerResponse.statusCode()); String provider=(String)body(providerResponse).get("id");
        var created=request("POST","/api/v1/admin/projects",ADMIN,json.writeValueAsBytes(Map.of("name","app","providerId",provider,"quotaBytes",1000,"maxFileBytes",100,"allowedTypes",Set.of("text/plain"))),"application/json",null);
        assertEquals(201,created.statusCode()); UUID project=UUID.fromString((String)body(created).get("id"));
        var issued=request("POST","/api/v1/admin/projects/"+project+"/keys",ADMIN,json.writeValueAsBytes(Map.of("permissions",Set.of("files:write","usage:read"))),"application/json",null);
        assertEquals(201,issued.statusCode()); String key=(String)body(issued).get("key");
        var upload=request("POST",path(project),key,new byte[10],"text/plain","floor.txt"); assertEquals(201,upload.statusCode());
        assertEquals(128,((Number)body(upload).get("estimatedBillableSize")).intValue());
        assertNotEquals(provider,body(upload).get("storageAssignmentId"));
        assertEquals(128,((Number)projects.get(project).get("billable_bytes")).intValue());
        var updated=request("PATCH","/api/v1/admin/projects/"+project,ADMIN,json.writeValueAsBytes(Map.of("maxFileBytes",5)),"application/json",null);
        assertEquals(200,updated.statusCode());
        assertEquals(413,request("POST",path(project),key,new byte[6],"text/plain","blocked.txt").statusCode());
    }
    @Test void rejectsUnknownLengthAndExpiredKeys() throws Exception {
        UUID project=project(1000,100,1000,20,2); String key=key(project,"files:write");
        var chunked=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path(project)))
            .header("Authorization","Bearer "+key).header("Content-Type","text/plain").header("X-Filename","chunked.txt")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[10]))).build();
        assertEquals(411,http.send(chunked,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals(0,((Number)projects.get(project).get("reserved_bytes")).intValue());
        db.update("UPDATE api_keys SET expires_at=now()-interval '1 minute' WHERE key_hash=?",Crypto.hash(key));
        assertEquals(401,request("POST",path(project),key,new byte[1],"text/plain","a.txt").statusCode());
    }
}
