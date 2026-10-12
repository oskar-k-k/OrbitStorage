# Architecture decisions

* Feature-oriented modular monolith: `auth`, `project`, `file`, `provider`, `quota`, `ratelimit`, `usage`, `monitoring`, `config`, `common`.
* PostgreSQL is authoritative for metadata/counters. Flyway owns the schema; Hibernate validates it. JPA retrieves file metadata, JDBC performs explicit atomic accounting transactions. File contents are never stored in the database.
* All capacity mutations acquire the singleton `capacity_lock` row first. Reservations include the provider's minimum billable object size. Rate admission uses its own short project-row transaction before capacity admission. No storage I/O happens while holding a capacity lock.
* File state machine: `UPLOADING -> READY -> DELETING -> removed`. A failed upload moves to `CLEANUP -> removed`; missing or corrupt ready objects become `MISSING`, retaining their charged capacity. Recovery retries provider deletion before releasing counters.
* Each project has a unique immutable storage assignment ID alongside its provider ID. Every file persists both IDs. Assignment migration is deliberately unavailable.
* Provider adapters implement a streaming contract. LOCAL uses an atomic staging rename; S3 uses a single known-length PUT. S3 retries cannot rewind an arbitrary servlet stream; failures are reported and cleaned up rather than re-buffered in memory.
* Reconciliation uses a PostgreSQL advisory lock and bounded keyset batches. It compares tracked objects, detects size discrepancies and recovers abandoned operations. It does not claim to measure entire bucket usage or costs.
* API-key entropy is 256 bits. Unsalted SHA-256 is appropriate for these random high-entropy tokens; this is not a password scheme. Provider configuration uses authenticated AES-GCM encryption with a unique random nonce. Admin/monitoring bootstrap keys reside only in deployment configuration.
* Daily admission is UTC and charges failures. Durable rolling request history supplements in-process Bucket4j. The default 95% block threshold leaves operational headroom. Completed metadata and bytes are counted only after upload success; pending cleanup continues to reserve capacity.
* Transport uses raw bytes and mandatory Content-Length to obtain a reservation before consuming the body. MIME policy compares declared types; no malware scanner/content sniffing is included.
