package com.orbitstorage.file;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name="files")
public class FileRecord {
    @Id public UUID id;
    @Column(name="project_id",nullable=false) public UUID projectId;
    @Column(name="provider_id",nullable=false) public UUID providerId;
    @Column(name="storage_assignment_id",nullable=false) public UUID storageAssignmentId;
    @Column(name="object_key",nullable=false,length=100) public String objectKey;
    @Column(name="original_name",nullable=false) public String originalName;
    @Column(name="content_type",nullable=false) public String contentType;
    @Column(name="actual_size",nullable=false) public long actualSize;
    @Column(name="billable_size",nullable=false) public long billableSize;
    @Column(name="uploaded_at",nullable=false) public Instant uploadedAt;
    @Column(nullable=false,length=20) public String status;
    protected FileRecord() {}
    public Metadata metadata() { return new Metadata(id,projectId,storageAssignmentId,originalName,contentType,actualSize,billableSize,uploadedAt,status); }
    public record Metadata(UUID id, UUID projectId, UUID storageAssignmentId, String originalFilename,
        String contentType, long actualSize, long estimatedBillableSize, Instant uploadedAt, String status) {}
}
