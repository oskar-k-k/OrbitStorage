# OrbitStorage – Project Specification

## 1. Project Overview

**Project Name:** OrbitStorage  
**Type:** Open-Source, Self-Hosted Storage Management Service  
**Primary Language:** Java 21+  
**Framework:** Spring Boot  
**Database:** PostgreSQL  
**Deployment:** Docker / Docker Compose  
**API:** REST / JSON  
**License:** Apache License 2.0 (proposed)

### Vision

OrbitStorage is a lightweight, extensible, provider-independent storage management service.

It provides a unified REST API for managing files across different storage providers while centralizing authentication, authorization, upload limits, rate limiting, storage quotas, and usage monitoring.

The service should be usable by multiple independent applications and projects simultaneously.

OrbitStorage will initially be deployed on a Hetzner Cloud server and use Hetzner Object Storage as its primary storage backend.

However, the architecture must remain completely provider-independent so that other developers can self-host OrbitStorage with different storage backends.

The project will be publicly released on GitHub.

### Core Principles

1. **Provider-independent:** Business logic must never depend directly on Hetzner, AWS, or a specific filesystem.
2. **Self-hosted:** Users retain control of their infrastructure and storage credentials.
3. **Multi-project:** One instance can serve multiple independent applications.
4. **Multi-provider:** Different projects can use different storage providers simultaneously.
5. **Secure by default:** Authentication, authorization, and resource protection are mandatory.
6. **Lightweight:** Avoid unnecessary microservices and infrastructure dependencies.
7. **Extensible:** New storage providers should be easy to implement.
8. **Observable:** Expose storage usage, quotas, upload statistics, and service health through APIs.
9. **Open-source friendly:** Clear configuration, documentation, testing, and Docker-based installation.

---

## 2. Technology Stack

Use the following technologies:

- Java 21 LTS
- Spring Boot (current stable version compatible with Java 21)
- Spring Web MVC
- Spring Security
- Spring Validation
- Spring Data JPA for application metadata
- PostgreSQL
- Flyway for database migrations
- AWS SDK for Java v2 (S3)
- Bucket4j for rate limiting
- Spring Boot Actuator
- Gradle
- Docker / Docker Compose
- JUnit 5, Mockito, Testcontainers
- OpenAPI / Swagger UI

Prefer established libraries and simple implementations over custom frameworks.

Use a modular monolith architecture.

Do not introduce Kafka, Kubernetes, Redis, or separate microservices unless a concrete requirement makes them necessary.

---

## 3. High-Level Architecture

OrbitStorage consists of four main areas.

### API Layer

Provides endpoints for:

- File uploads
- File downloads
- File deletion
- File metadata
- Storage usage
- Storage quotas
- Project administration
- Storage provider administration
- Health and monitoring

### Core Layer

Contains provider-independent logic:

- Authentication and authorization
- File management
- Upload validation
- Rate limiting
- Storage quota enforcement
- Upload reservations
- Usage accounting
- Project management
- Storage provider selection

### Provider Layer

Contains implementations for different storage backends.

Initial providers:

**S3StorageProvider**
- Hetzner Object Storage
- AWS S3
- Other compatible S3 providers

**LocalStorageProvider**
- Local filesystem
- Configurable storage directory
- Support for Docker-mounted volumes

Future providers may include Azure Blob Storage and Google Cloud Storage.

### Persistence Layer

PostgreSQL stores:

- Projects
- API key metadata and secure key hashes
- Provider configurations and encrypted secrets
- Storage assignments
- File metadata
- Storage quotas
- Upload reservations
- Usage statistics

Actual file contents must never be stored in PostgreSQL.

---

## 4. Storage Provider Abstraction

Define a common storage provider contract.

Suggested operations:

- upload
- download
- delete
- exists
- getMetadata
- getUsage, where supported

Provider implementations must be interchangeable.

Avoid assuming every provider supports the same native features.

For example, some providers may expose usage statistics while others require OrbitStorage to calculate usage through its own metadata and reconciliation processes.

Provider-specific configuration and behavior must remain inside provider adapters.

Use streaming APIs to avoid loading complete files into memory.

The provider abstraction should be designed so that additional implementations can be added without modifying core business logic.

---

## 5. Multi-Project and Multi-Provider Support

OrbitStorage must support multiple projects within one installation.

Example:

| Project | Storage Provider |
|---|---|
| Lilsi | Hetzner S3 |
| Oskar-Lab | Local Filesystem |
| Dev Orbit | Hetzner S3 |
| Another Application | AWS S3 |

Each project must have:

- Unique identifier
- Name
- Assigned storage backend
- Independent credentials
- Upload configuration
- Rate limits
- Storage quota
- Usage statistics

Multiple projects may share the same physical storage provider, but their files and permissions must remain logically isolated.

Use provider-specific namespaces, prefixes, or buckets to enforce separation.

One OrbitStorage installation must be capable of operating multiple configured providers simultaneously.

For the MVP, each project has one active storage assignment. Provider migration and multi-provider replication are future features.

---

## 6. File Management

Implement the following operations:

### Upload

- Upload a file to a specified project.
- Validate authentication and project permissions.
- Validate file size and allowed file types.
- Enforce upload rate limits.
- Check remaining storage quota.
- Reserve the required capacity before accepting the upload.
- Stream the file to the configured provider.
- Persist metadata after successful upload.
- Release reservations on failure.

### Download

- Download a file using its identifier.
- Validate project permissions.
- Stream file contents without loading the complete file into memory.
- Return appropriate content headers.
- Track download activity where practical.

### Delete

- Delete a file from its provider.
- Update metadata and storage usage.
- Handle provider failures consistently.
- Prevent unauthorized deletion.

### File Metadata

Store at least:

- File ID
- Project ID
- Storage assignment ID
- Storage object key
- Original filename
- Content type
- Actual size
- Billable size estimate
- Upload timestamp
- File status

Use generated, non-guessable storage keys instead of directly trusting user-provided paths.

---

## 7. Upload Limits and Spam Protection

Implement configurable limits per project.

Initial configuration examples:

| Limit | Default |
|---|---|
| Maximum file size | 10 MB |
| Upload requests per minute | 20 |
| Daily uploaded data | 200 MB |
| Concurrent uploads | 2 |
| Storage quota | Configurable |
| Global storage budget | Configurable |

These values must be configurable, not hardcoded.

### Requirements

- Enforce limits on the server.
- Apply rate limiting using authenticated project or user identity.
- Optionally apply IP-based protection for suspicious unauthenticated traffic.
- Return HTTP 429 for rate-limit violations.
- Support Retry-After headers where appropriate.
- Do not allow parallel uploads to bypass quotas.
- Use atomic database operations for capacity reservations.
- Do not reset daily upload allowances when files are deleted.
- Prevent unlimited request bodies from exhausting server resources.

The service must remain protected even if clients bypass frontend validation.

For the MVP, all uploads and downloads pass through OrbitStorage.

Direct-to-provider uploads using presigned URLs may be implemented later, provided the same authorization, reservation, and quota rules remain enforceable.

---

## 8. Storage Quotas and Usage Tracking

Implement centralized storage accounting.

Track:

- Total stored bytes
- Estimated billable stored bytes
- Stored bytes per project
- File count per project
- Reserved upload capacity
- Remaining project quota
- Global configured storage budget
- Upload volume over time
- Download volume observed by OrbitStorage

### Quota Enforcement

Quota checks must occur before uploads.

Example:

- Warning threshold: 80%
- Upload blocking threshold: 95%

Thresholds must be configurable.

Do not automatically delete existing files when thresholds are exceeded.

Quota checks must account for concurrent uploads.

### Usage Reconciliation

Implement a scheduled reconciliation mechanism that compares OrbitStorage metadata against the underlying storage provider.

Provider-reported usage may be delayed or incomplete.

The system must distinguish:

- Internally tracked usage
- Provider-reported usage
- Estimated billable usage

Never treat estimated provider costs as guaranteed billing amounts.

Account for provider-specific minimum billable object sizes where applicable.

Also consider incomplete multipart uploads and object versioning when supported.

---

## 9. Authentication and Authorization

Use API keys for project-to-service communication.

Requirements:

- Each project can have independent API keys.
- Store only secure hashes of API keys.
- Support key rotation and revocation.
- Authenticate every protected request.
- Enforce project-level access control.
- Prevent access to other projects' files.
- Never expose storage provider credentials to applications.
- Protect administrative endpoints separately.

Use distinct permissions such as:

- files:read
- files:write
- files:delete
- usage:read
- admin

Administrative access must not rely on a normal project API key.

All externally exposed endpoints must use HTTPS in production.

Provider credentials must be stored securely, encrypted at rest, and excluded from logs and API responses.

Never expose unrestricted administrative functionality publicly by default.

---

## 10. REST API

Design a versioned REST API.

Suggested endpoints:

### Files

- POST /api/v1/projects/{projectId}/files
- GET /api/v1/projects/{projectId}/files/{fileId}
- GET /api/v1/projects/{projectId}/files/{fileId}/metadata
- DELETE /api/v1/projects/{projectId}/files/{fileId}

### Usage

- GET /api/v1/projects/{projectId}/usage
- GET /api/v1/admin/storage/overview
- GET /api/v1/admin/storage/providers
- GET /api/v1/admin/storage/projects

### Administration

- GET /api/v1/admin/projects
- POST /api/v1/admin/projects
- PATCH /api/v1/admin/projects/{projectId}
- POST /api/v1/admin/projects/{projectId}/keys
- POST /api/v1/admin/providers

Provide consistent response structures and standardized error handling.

Document the API using OpenAPI.

Administrative endpoints must enforce dedicated administrator authentication and authorization.

---

## 11. Monitoring and Dev Orbit Integration

OrbitStorage must expose monitoring data through a REST API.

The existing desktop application **Dev Orbit** should be able to display OrbitStorage statistics without requiring direct database or provider access.

Example information:

- Total storage usage
- Storage usage by project
- Usage by provider
- Remaining storage quotas
- Current upload reservations
- Recent upload activity
- Rate-limit violations
- Provider availability
- Last successful usage reconciliation
- Estimated storage and traffic costs where supported

Use Spring Boot Actuator for technical service health and metrics.

Provide a separate application-level monitoring API for storage statistics.

Dev Orbit integration must use authenticated, preferably read-only API access.

OrbitStorage must remain fully functional without Dev Orbit.

Do not build a frontend dashboard in the initial MVP.

---

## 12. Configuration and Deployment

OrbitStorage must support simple self-hosted deployment.

Provide:

- Dockerfile
- docker-compose.yml
- .env.example
- README.md
- Example configuration
- Health checks
- Persistent PostgreSQL volume
- Optional local storage volume

A user should be able to deploy OrbitStorage using Docker Compose and configure a local filesystem or S3-compatible storage backend.

Support environment-based configuration.

Never commit secrets or real credentials.

Use structured logging and avoid logging API keys, provider credentials, or sensitive file contents.

---

## 13. Architecture and Package Structure

Use a feature-oriented modular monolith.

Suggested package structure:

```text
com.orbitstorage
├── auth
├── project
├── file
├── provider
│   ├── api
│   ├── s3
│   └── local
├── quota
├── ratelimit
├── usage
├── monitoring
├── config
└── common
```

Each feature should contain its own relevant controllers, services, repositories, DTOs, and domain models.

Do not create unnecessary abstract base classes.

Prefer interfaces only where they provide meaningful decoupling, especially for storage providers.

Keep business logic independent of HTTP controllers and specific provider SDKs.

Avoid excessive abstraction and premature optimization.

---

## 14. Testing Requirements

Implement automated tests for:

- Storage provider contract
- Local filesystem provider
- S3-compatible provider
- Upload validation
- Authentication and authorization
- Project isolation
- Rate limiting
- Storage quota enforcement
- Concurrent upload reservations
- Failed uploads and cleanup
- File deletion
- Usage accounting
- API responses

Use Testcontainers for PostgreSQL integration tests.

Use a suitable local S3-compatible test environment for S3 integration testing.

Tests must not require real Hetzner credentials.

---

## 15. MVP Scope

The first usable release should include:

1. Spring Boot application with PostgreSQL and Flyway.
2. Project configuration and management.
3. Local filesystem storage provider.
4. S3-compatible storage provider.
5. File upload, download, and deletion.
6. API-key authentication and project isolation.
7. Upload size limits and rate limiting.
8. Project storage quotas and atomic reservations.
9. Usage tracking and reconciliation.
10. Read-only monitoring endpoints.
11. Docker Compose deployment.
12. OpenAPI documentation.
13. Automated tests.
14. Public GitHub README and setup instructions.

Do not implement these features initially:

- Web dashboard
- Kubernetes deployment
- Distributed microservices
- Automatic provider migration
- Cross-provider replication
- CDN management
- User registration platform
- Subscription or payment processing
- Complex multi-tenant billing

---

## 16. Development Instructions for Codex

Act as the lead software architect and developer for OrbitStorage.

Build a production-conscious, maintainable open-source application without overengineering.

Follow these principles:

- Start with a working minimal foundation.
- Implement features incrementally.
- Prefer simple, testable code.
- Keep provider implementations isolated.
- Never bypass authentication or quota enforcement.
- Document important architectural decisions.
- Use current stable dependencies compatible with Java 21.
- Keep the application runnable after each development phase.
- Add tests alongside core functionality.
- Avoid unnecessary dependencies and infrastructure.

### Initial Development Plan

**Phase 1 – Foundation**

- Initialize Spring Boot and Gradle.
- Configure PostgreSQL and Flyway.
- Create Docker Compose configuration.
- Define feature-oriented package structure.
- Configure Actuator and OpenAPI.
- Implement basic health endpoint.

**Phase 2 – Storage Foundation**

- Define the storage provider interface.
- Implement LocalStorageProvider.
- Implement S3StorageProvider.
- Add provider configuration and selection.
- Add provider contract tests.

**Phase 3 – Project and File Management**

- Implement project entities and persistence.
- Implement API-key authentication.
- Implement file metadata persistence.
- Implement upload, download, and deletion.
- Enforce project isolation.

**Phase 4 – Protection**

- Implement configurable file size limits.
- Implement rate limiting.
- Implement daily upload budgets.
- Implement concurrent upload limits.
- Implement atomic quota reservations.
- Add tests for concurrent requests and failures.

**Phase 5 – Monitoring**

- Implement usage accounting.
- Implement provider reconciliation.
- Implement global and project-level statistics.
- Expose read-only administrative monitoring APIs.

**Phase 6 – Open-Source Readiness**

- Add integration tests.
- Add Docker deployment documentation.
- Add configuration examples.
- Add API documentation.
- Add license and contribution guidelines.
- Review security and operational behavior.

### First Task

Begin with Phase 1 only.

Before implementation, briefly explain the proposed package structure and database foundation.

Then initialize the project with a working Spring Boot application, PostgreSQL, Flyway, Docker Compose, and health endpoint.

Do not implement later phases prematurely.

After Phase 1, summarize what was implemented, how to start the application, how to run tests, and what remains for Phase 2.
