package com.orbitstorage.file;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRepository extends JpaRepository<FileRecord,UUID> {
    Optional<FileRecord> findByIdAndProjectId(UUID id, UUID projectId);
}
