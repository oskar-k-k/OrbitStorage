package com.orbitstorage.provider.s3;

import com.orbitstorage.provider.api.StorageProvider;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import com.orbitstorage.provider.ProviderService.S3Config;

public class S3StorageProvider implements StorageProvider {
    private final S3Client client;
    private final String bucket;
    private final String prefix;
    public S3StorageProvider(S3Config config, String prefix) {
        this.bucket = config.bucket(); this.prefix = prefix + "/";
        var builder = S3Client.builder().region(Region.of(config.region()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
            .forcePathStyle(config.pathStyle())
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofMinutes(16)).apiCallAttemptTimeout(Duration.ofMinutes(16)));
        if (config.endpoint() != null && !config.endpoint().isBlank()) builder.endpointOverride(java.net.URI.create(config.endpoint()));
        client = builder.build();
    }
    private String objectKey(String key) {
        if (!key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}")) throw new IllegalArgumentException("Invalid object key");
        return prefix + key;
    }
    @Override public void upload(String key, InputStream source, long size, String type) {
        client.putObject(r -> r.bucket(bucket).key(objectKey(key)).contentLength(size).contentType(type), RequestBody.fromInputStream(source, size));
    }
    @Override public InputStream download(String key) { return client.getObject(r -> r.bucket(bucket).key(objectKey(key))); }
    @Override public void delete(String key) { client.deleteObject(r -> r.bucket(bucket).key(objectKey(key))); }
    @Override public void checkHealth() { client.headBucket(r -> r.bucket(bucket)); }
    @Override public Optional<ObjectMetadata> getMetadata(String key) {
        try { var result = client.headObject(r -> r.bucket(bucket).key(objectKey(key))); return Optional.of(new ObjectMetadata(result.contentLength())); }
        catch (S3Exception ex) { if (ex.statusCode() == 404) return Optional.empty(); throw ex; }
    }
    @Override public void close() { client.close(); }
}
