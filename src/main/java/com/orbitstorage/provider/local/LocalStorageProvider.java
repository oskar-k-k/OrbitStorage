package com.orbitstorage.provider.local;

import com.orbitstorage.provider.api.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;

public class LocalStorageProvider implements StorageProvider {
    private final Path root;
    public LocalStorageProvider(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        if (Files.isSymbolicLink(this.root)) throw new IOException("Storage root must not be a symbolic link");
    }
    private Path path(String key) throws IOException {
        if (!key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}")) throw new IOException("Invalid object key");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IOException("Invalid object path");
        for (Path current = path; !current.equals(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed in storage");
        return path;
    }
    @Override public void upload(String key, InputStream source, long size, String type) throws IOException {
        Path target = path(key); Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            try (var sink = Files.newOutputStream(staging)) {
                long copied = source.transferTo(sink);
                if (copied != size) throw new IOException("Upload size mismatch");
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(staging); }
    }
    @Override public InputStream download(String key) throws IOException { return Files.newInputStream(path(key)); }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }
    @Override public java.util.Optional<ObjectMetadata> getMetadata(String key) throws IOException {
        Path target = path(key);
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? java.util.Optional.of(new ObjectMetadata(Files.size(target))) : java.util.Optional.empty();
    }
    @Override public void checkHealth() throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(root)) throw new IOException("Local storage is unavailable");
    }
    /** Only run once no upload can still be using these files (recovery grace is one hour). */
    public void cleanStaging() throws IOException {
        try (var projects = Files.list(root)) {
            for (Path project : projects.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                try (var files = Files.list(project)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().startsWith(".upload-")).toList()) {
                        if (!Files.isSymbolicLink(file) && Files.getLastModifiedTime(file).toInstant().isBefore(java.time.Instant.now().minusSeconds(3600))) Files.deleteIfExists(file);
                    }
                }
            }
        }
    }
}
