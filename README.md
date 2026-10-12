# OrbitStorage

Self-hosted, provider-independent file storage for multiple applications. Java 21, Spring Boot 4.1.1, PostgreSQL, Flyway and Gradle. Local filesystem and S3-compatible adapters (including Hetzner Object Storage and AWS S3). No dashboard is required.

## Start with Docker

Requirements: Docker Engine / Docker Desktop with Compose. The application and database run in containers; no host Java installation is required.

```powershell
# Windows PowerShell, from this directory; creates a private .env with fresh secrets
./scripts/setup.ps1
docker compose up --build -d
curl.exe http://localhost:18080/actuator/health
```

On Linux/macOS:

```sh
cp .env.example .env
chmod 600 .env
# Replace DATABASE_PASSWORD and ORBIT_ADMIN_KEY with independent `openssl rand -hex 32` values.
# Set ORBIT_ENCRYPTION_KEY to `openssl rand -base64 32`.
docker compose up --build -d
curl http://localhost:18080/actuator/health
```

The API binds to **127.0.0.1:18080**; change `ORBIT_PORT` in `.env` if needed. The container listens on port 8080 internally. PostgreSQL has no published port. Use a TLS reverse proxy before exposing the API externally. Keep the encryption key stable: replacing it makes stored provider secrets unreadable. Back up `.env`, PostgreSQL and the object volume together. Never commit `.env`.

## First project

Use the admin secret from `.env` as a bearer token. Examples below use a POSIX shell with `ADMIN_KEY` set privately (do not paste real tokens into issues or shared terminals).

```sh
# 1. Register local storage. Save the returned id as PROVIDER_ID.
curl -X POST http://localhost:18080/api/v1/admin/providers \
  -H "Authorization: Bearer $ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"name":"Local storage","kind":"LOCAL","minimumBillableBytes":0}'

# 2. Create a project. Save the returned id as PROJECT_ID.
curl -X POST http://localhost:18080/api/v1/admin/projects \
  -H "Authorization: Bearer $ADMIN_KEY" -H 'Content-Type: application/json' \
  -d "{\"name\":\"My application\",\"providerId\":\"$PROVIDER_ID\",\"quotaBytes\":1073741824,\"allowedTypes\":[\"image/png\",\"image/jpeg\",\"text/plain\"]}"

# 3. Issue a project key. The plaintext key is returned once; save it as PROJECT_KEY.
curl -X POST http://localhost:18080/api/v1/admin/projects/$PROJECT_ID/keys \
  -H "Authorization: Bearer $ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"permissions":["files:read","files:write","files:delete","usage:read"]}'

# 4. Upload raw bytes (not multipart). Save the returned id as FILE_ID.
curl -X POST http://localhost:18080/api/v1/projects/$PROJECT_ID/files \
  -H "Authorization: Bearer $PROJECT_KEY" -H 'Content-Type: text/plain' \
  -H 'X-Filename: hello.txt' --data-binary @hello.txt

curl -H "Authorization: Bearer $PROJECT_KEY" \
  http://localhost:18080/api/v1/projects/$PROJECT_ID/files/$FILE_ID -o downloaded.txt
curl -H "Authorization: Bearer $PROJECT_KEY" \
  http://localhost:18080/api/v1/projects/$PROJECT_ID/usage
curl -X DELETE -H "Authorization: Bearer $PROJECT_KEY" \
  http://localhost:18080/api/v1/projects/$PROJECT_ID/files/$FILE_ID
```

Uploads require a positive `Content-Length` and a plain `X-Filename` (up to 255 characters). Files stream through the service with a 64 KiB copy buffer. Chunked and multipart requests are unsupported. `allowedTypes` is required; use explicit MIME types, `image/*`, or `*` deliberately. Validation checks the declared MIME type, **not the file's actual contents**; add malware/content inspection upstream if your application requires it. Downloads always use attachment disposition and `nosniff`.

## S3 / Hetzner

Create the bucket in your provider first, then `POST /api/v1/admin/providers`:

```json
{
  "name": "S3 compatible storage",
  "kind": "S3",
  "minimumBillableBytes": 0,
  "s3": {
    "endpoint": "https://your-provider-endpoint",
    "region": "your-region",
    "bucket": "your-bucket",
    "accessKey": "your-access-key",
    "secretKey": "your-secret-key",
    "pathStyle": true
  }
}
```

For AWS S3, omit `endpoint`, use the bucket's region and typically set `pathStyle` to false. For Hetzner, use your bucket's documented endpoint/region. Credentials are encrypted with AES-256-GCM before persistence and never returned by the API. Use credentials limited to the chosen bucket's object operations and bucket health checks (`s3:ListBucket` for HeadBucket on AWS); the application does not create buckets. HTTP endpoints are supported for local S3 testing; use HTTPS externally.

Namespaces are `provider-id/project-id/file-id`. Local provider roots are always beneath `ORBIT_LOCAL_ROOT`; clients cannot choose filesystem paths. Projects have one immutable provider assignment. Changing assignments/migration is outside this release.

## Limits and accounting

Project creation defaults: 10 MiB maximum file, 200 MiB daily admitted data, 20 upload attempts/minute, 2 concurrent uploads. `quotaBytes` and `allowedTypes` are mandatory. Override defaults in the project JSON; update them using `PATCH /api/v1/admin/projects/{id}`. Limit changes apply to subsequent requests and never delete existing objects.

Global environment settings are in `.env.example`: default global budget 100 GiB, warning at 80%, blocking at 95%, absolute maximum request body 100 MiB. Quota checks use estimated billable bytes plus billable reserved capacity; `minimumBillableBytes` provides a configurable per-object floor. These are estimates, not cost/billing guarantees. Project usage includes up to 90 days of daily admitted and successfully uploaded byte history, retained even after file deletion.

An upload first takes a database reservation. PostgreSQL row locks serialize capacity changes across projects and replicas. Network streaming occurs outside the database transaction. Failed uploads release reservations after object deletion is confirmed; otherwise reconciliation retries. Admission consumes the daily allowance even if streaming fails, to prevent failed-upload abuse. Deletion never refunds daily allowance. Daily limits reset at **00:00 UTC**. Rate limiting combines Bucket4j with a durable rolling one-minute database window; restarts cannot reset the allowance. Responses include `Retry-After` for HTTP 429.

Reconciliation runs every five minutes, checking known objects in batches and retrying incomplete uploads/deletions. Uploads have a 15-minute application deadline, socket timeouts, and a one-hour crash-recovery grace period. Missing or differently sized objects are marked `MISSING` and cannot be downloaded. Their accounted capacity remains until explicit deletion; reconciliation never automatically deletes completed files. Stale local staging files are removed after one hour.

Reconciled known-object bytes, internal tracked bytes, and estimated billable bytes are exposed separately. Native provider-wide usage is unavailable (`provider_reported_bytes: null`), because bucket totals can include unrelated objects. Object versions, orphaned objects created outside OrbitStorage and incomplete multipart uploads are not enumerated by this MVP. It uses single-object PUTs, not multipart uploads. Configure provider lifecycle rules and monitor billing/versioning separately. Reconciliation is not a billing audit.

## API and monitoring

| Endpoint | Access |
|---|---|
| `POST /api/v1/projects/{id}/files` | `files:write` |
| `GET /api/v1/projects/{id}/files/{fileId}` | `files:read` |
| `GET /api/v1/projects/{id}/files/{fileId}/metadata` | `files:read` |
| `DELETE /api/v1/projects/{id}/files/{fileId}` | `files:delete` |
| `GET /api/v1/projects/{id}/usage` | `usage:read` |
| `GET /api/v1/admin/projects` | Admin |
| `POST /api/v1/admin/projects` | Admin |
| `PATCH /api/v1/admin/projects/{id}` | Admin |
| `POST, GET /api/v1/admin/projects/{id}/keys` | Admin |
| `DELETE /api/v1/admin/projects/{id}/keys/{keyId}` | Admin |
| `POST /api/v1/admin/providers` | Admin |
| `GET /api/v1/admin/storage/overview` | Admin or monitoring key |
| `GET /api/v1/admin/storage/providers` | Admin or monitoring key |
| `GET /api/v1/admin/storage/projects` | Admin or monitoring key |
| `GET /actuator/health` | Public, minimal status |
| `GET /actuator/metrics`, `/actuator/info` | Admin or monitoring key |
| `GET /v3/api-docs` | Admin |

Set a separate `ORBIT_MONITORING_KEY` for read-only Dev Orbit integration. Project keys cannot access administrative endpoints; administrator keys cannot impersonate project keys. Key permissions are never `admin`. Rotate project keys by creating a replacement, updating clients, then revoking the old key. Optional `expiresAt` is an ISO-8601 instant. Only SHA-256 hashes of cryptographically random 256-bit project keys are stored.

Errors use `application/problem+json`: 400 invalid input, 401 invalid/missing key, 403 missing permissions, 404 missing file/project, 409 quota or file-state conflict, 411 missing length, 413 oversized upload, 415 disallowed MIME type, 429 throttled, 503 provider failure.

Set `ORBIT_DOCS_ENABLED=true` to serve Swagger UI at `/swagger-ui/index.html`. The UI and OpenAPI endpoints are admin protected; use a trusted local client capable of sending the admin Authorization header for the initial page/schema request. The JSON at `/v3/api-docs` is directly accessible with `curl -H "Authorization: Bearer ..."`. Import it into your API client for convenient documentation without enabling Swagger UI.

## Build and tests

With Java 21+ and Docker running:

```sh
./gradlew check bootJar
# Windows: .\gradlew.bat check bootJar
```

Testcontainers starts disposable PostgreSQL and [Adobe S3Mock](https://github.com/adobe/S3Mock) containers; no real provider credentials are needed. Tests cover provider contracts, local path checks, S3 upload/download/delete, authentication, permissions/revocation, isolation, limits, simultaneous reservations, cleanup recovery, deletion, usage, encryption and API responses. Integration tests require Docker and fail if it is unavailable. CI runs the same suite on Java 21. S3Mock verifies API compatibility; a staging smoke test with your actual provider is still appropriate before production.

`docker compose logs -f orbit-storage` shows structured JSON logs. API keys, provider credentials, object contents and provider exception messages are excluded. Health/metrics are technical; the storage API provides application statistics.

## Operations

Start with one application replica and PostgreSQL. Durable quotas/rates also protect capacity across replicas, but replicas using LOCAL providers must share the same filesystem volume. Use S3 when deploying on separate hosts. The global lock favors correctness over high write throughput. Reserve DB connections for reconciliation and API traffic; the default connection pool is sufficient for the default two concurrent uploads/project.

At your TLS proxy, set a request-body limit no higher than `ORBIT_MAX_REQUEST_BYTES`, request/header/idle timeouts, a connection limit, and an unauthenticated request rate limit. The service limits authenticated uploads and container memory; edge limits protect against connection-level abuse. Do not allow other processes to mutate the local object directory or replace directories with symlinks. Back up data before upgrades. `docker compose down` preserves volumes; `down -v` deletes them.

See [architecture](docs/architecture.md), [contributing](CONTRIBUTING.md) and [security](SECURITY.md). Licensed under Apache-2.0.
