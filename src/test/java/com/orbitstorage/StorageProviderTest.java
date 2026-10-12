package com.orbitstorage;

import com.orbitstorage.provider.api.StorageProvider;
import com.orbitstorage.provider.local.LocalStorageProvider;
import com.orbitstorage.provider.s3.S3StorageProvider;
import com.orbitstorage.provider.ProviderService.S3Config;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class StorageProviderTest {
    @TempDir Path root;
    @Container static final GenericContainer<?> S3 = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3"))
        .withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090));
    void contract(StorageProvider provider) throws Exception {
        String key=UUID.randomUUID()+"/"+UUID.randomUUID(); byte[] bytes="hello orbit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(provider.exists(key)); provider.upload(key,new ByteArrayInputStream(bytes),bytes.length,"text/plain");
        assertTrue(provider.exists(key)); assertEquals(bytes.length,provider.getMetadata(key).orElseThrow().size());
        try(var source=provider.download(key)) { assertArrayEquals(bytes,source.readAllBytes()); }
        provider.delete(key); provider.delete(key); assertFalse(provider.exists(key));
    }
    @Test void localContract() throws Exception { contract(new LocalStorageProvider(root)); }
    @Test void localRejectsTraversalAndCleansIncompleteUpload() throws Exception {
        var provider=new LocalStorageProvider(root);
        assertThrows(IOException.class,() -> provider.download("../secret"));
        String key=UUID.randomUUID()+"/"+UUID.randomUUID();
        assertThrows(IOException.class,() -> provider.upload(key,new ByteArrayInputStream(new byte[1]),2,"text/plain"));
        assertFalse(provider.exists(key));
        try(var paths=Files.walk(root)) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
    }
    @Test void s3Contract() throws Exception {
        String endpoint="http://"+S3.getHost()+":"+S3.getMappedPort(9090);
        try(var client=S3Client.builder().region(Region.US_EAST_1).endpointOverride(java.net.URI.create(endpoint)).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("orbit-test","orbit-test-password"))).build()) {
            client.createBucket(b -> b.bucket("orbit-contract"));
        }
        try(var provider=new S3StorageProvider(new S3Config(endpoint,"us-east-1","orbit-contract","orbit-test","orbit-test-password",true),UUID.randomUUID().toString())) { contract(provider); }
    }
}
