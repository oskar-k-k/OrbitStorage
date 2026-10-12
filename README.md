# OrbitStorage

Self-hosted, provider-independent file storage for multiple applications. Java 21, Spring Boot 4.1.1, PostgreSQL, Flyway and Gradle. Local filesystem and S3-compatible adapters (including Hetzner Object Storage and AWS S3). No dashboard is required.

## Quick start

**Deutsch: [Schritt-für-Schritt-Anleitung](docs/getting-started.de.md)** (Installation, Hetzner, erstes Projekt, Datei-Test, Updates und Backups).

Requirements: **Docker with Compose** (running) and **Python 3.10+**. No host Java installation or Python packages are needed. Run commands from the repository on the machine hosting OrbitStorage.

Clone the repository first (Git required):

```sh
git clone https://github.com/oskar-k-k/OrbitStorage.git
cd OrbitStorage
```

Normal installation pulls the versioned image `ghcr.io/oskar-k-k/orbitstorage:0.1.0` from GitHub Container Registry. Published images currently target Linux AMD64 (on Windows, use Docker Desktop with Linux containers). Source builds are optional.

**1. Start OrbitStorage.** This creates only the service configuration and containers. No storage targets, projects or project keys are created, and no bucket details are requested.

Windows (PowerShell):

```powershell
.\orbit.ps1 init
```

Linux/macOS:

```sh
sh ./orbit init
```

`init` generates the internal database password and admin/encryption keys once, writes a private `.env`, pulls missing images, starts PostgreSQL and OrbitStorage, and waits for the API to become healthy. It prints the local API URL (default `http://127.0.0.1:18080`). Existing secrets and data are preserved. Repeating `init` does not build the application. Developers can use `init --rebuild` to build and run local source; a subsequent plain `init` switches back to the published image. The older `scripts/setup.ps1` / `sh scripts/setup.sh` entry points without arguments now also run only `init`.

**2. Add a storage target when needed.**

```powershell
.\orbit.ps1 storage add
```

```sh
sh ./orbit storage add
```

The assistant asks for local or S3 storage, a name, and S3 endpoint/region/bucket/credentials when needed. Access and secret keys are entered invisibly. The bucket must already exist. S3 addressing is chosen automatically; there is no path-style or project question. A successful command prints the storage target ID. Call `storage add` again to add another target. If interrupted, rerun it to resume the pending addition.

**3. List configured storage targets.**

```powershell
.\orbit.ps1 storage list
.\orbit.ps1 storage list --json
```

```sh
sh ./orbit storage list
sh ./orbit storage list --json
```

The list shows IDs, names, storage types and S3 bucket names. It lists connections registered with OrbitStorage, not every bucket in your cloud account. Empty installations return an empty list. Storage commands never start, rebuild or restart containers; run `init` first.

**4. Provision applications later through the admin API.** An authorized provisioning process lists targets, selects an ID, creates its project and obtains a project key. File operations then use only that project key. The admin key stays in the trusted provisioning environment. See the examples below.

The standard S3 layout is `bucket/<project-id>/<file-id>`. Multiple projects can share the bucket. No empty project folders are created by `init` or `storage add`.

The API binds to **127.0.0.1:18080**; PostgreSQL has no published port. Commands do not install Docker, create cloud buckets, configure DNS or modify reverse proxies. For public access, configure HTTPS separately. A host proxy forwards to `127.0.0.1:18080`; a containerized proxy needs appropriate Docker networking. Service health does not validate bucket credentials; test actual S3 transfers separately.

Keep `.env` and `.orbit-setup/` private and back them up with PostgreSQL and stored files. Both are excluded from Git and the Docker build context. Never regenerate the encryption key for an existing deployment. If `.env` is missing but Docker volumes exist, restore it from backup. Old setup checkpoints and projects are preserved: `init` ignores the old project-creation flow, and `storage add` can recover its saved connection without creating a project.

### Unattended commands

```sh
sh ./orbit init --config /secure/path/service.json
sh ./orbit storage add --config /secure/path/storage.json
```

`service.json` may contain only service settings such as:

```json
{"port": 18080, "url": "http://127.0.0.1:18080"}
```

The URL is informational and does not configure DNS or HTTPS. Existing `.env` settings take precedence over `port`. Conflicting shell environment overrides are rejected.

`storage.json` contains a storage configuration:

```json
{"storage": {"name": "Local files", "kind": "LOCAL", "minimumBillableBytes": 0}}
```

For S3, use the provider configuration shown below inside `storage`. `provider` remains accepted as a configuration alias. To reuse a known connection explicitly, use `{"storage":{"id":"<provider-id>"}}`. Keep files containing S3 secrets outside the repository with restricted permissions. `init` rejects storage configuration, and both commands reject project configuration; provisioning belongs to the admin API. The old `--add` and `--new-provider` flags are replaced by `storage add`.

## Project provisioning through the API

Use the admin API to provision projects after the storage service is running. First discover registered storage targets using `GET /api/v1/admin/providers` with the admin bearer key. The response includes IDs, names, types and S3 bucket/endpoint/region, but no credentials. Use the selected ID as `providerId` when creating a project; the API retains this field name for compatibility. If you already ran `storage add`, skip step 1 below.

### First project through the API

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
curl -X POST http://localhost:18080/api/v1/files \
  -H "Authorization: Bearer $PROJECT_KEY" -H 'Content-Type: text/plain' \
  -H 'X-Filename: hello.txt' --data-binary @hello.txt

curl -H "Authorization: Bearer $PROJECT_KEY" \
  http://localhost:18080/api/v1/files/$FILE_ID -o downloaded.txt
curl -H "Authorization: Bearer $PROJECT_KEY" \
  http://localhost:18080/api/v1/usage
curl -X DELETE -H "Authorization: Bearer $PROJECT_KEY" \
  http://localhost:18080/api/v1/files/$FILE_ID
```

Uploads require a positive `Content-Length` and a plain `X-Filename` (up to 255 characters). Files stream through the service with a 64 KiB copy buffer. Chunked and multipart requests are unsupported. `allowedTypes` is required; use explicit MIME types, `image/*`, or `*` deliberately. Validation checks the declared MIME type, **not the file's actual contents**; add malware/content inspection upstream if your application requires it. Downloads always use attachment disposition and `nosniff`.

File and usage requests infer the project from the bearer project key. Applications only configure the OrbitStorage base URL and their project key; project IDs are needed for administration only. The former `/api/v1/projects/{projectId}/files...` and `/api/v1/projects/{projectId}/usage` routes have been replaced by `/api/v1/files...` and `/api/v1/usage`; update existing clients accordingly. Access to another project's file ID returns 404.

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

S3 object keys are `project-id/file-id`: projects appear directly at the bucket root, with files beneath them. One existing bucket can serve multiple projects; a separate bucket per project is optional. Provider IDs identify storage connection configurations in the database and are not included in S3 object keys. Original filenames remain in file metadata; keys use generated UUIDs. There are no nested project folders or client-selected paths.

Local storage uses `ORBIT_LOCAL_ROOT/provider-id/project-id/file-id`, retaining a separate filesystem root for each local provider. Projects have one immutable provider assignment. Changing assignments/migration is outside this release.

Upgrade note: earlier versions used `provider-id/project-id/file-id` in S3. Existing S3 objects must be moved to `project-id/file-id` in the same bucket before running this version (stop OrbitStorage during migration). This release does not automatically migrate or fall back to the old keys. Back up objects and the database before migrating; database object keys already use `project-id/file-id` and do not need to change.

## Limits and accounting

Project creation defaults: 10 MiB maximum file, 200 MiB daily admitted data, 20 upload attempts/minute, 2 concurrent uploads. `quotaBytes` and `allowedTypes` are mandatory. Override defaults in the project JSON; update them using `PATCH /api/v1/admin/projects/{id}`. Limit changes apply to subsequent requests and never delete existing objects.

Global environment settings are in `.env.example`: default global budget 100 GiB, warning at 80%, blocking at 95%, absolute maximum request body 100 MiB. Quota checks use estimated billable bytes plus billable reserved capacity; `minimumBillableBytes` provides a configurable per-object floor. These are estimates, not cost/billing guarantees. Project usage includes up to 90 days of daily admitted and successfully uploaded byte history, retained even after file deletion.

An upload first takes a database reservation. PostgreSQL row locks serialize capacity changes across projects and replicas. Network streaming occurs outside the database transaction. Failed uploads release reservations after object deletion is confirmed; otherwise reconciliation retries. Admission consumes the daily allowance even if streaming fails, to prevent failed-upload abuse. Deletion never refunds daily allowance. Daily limits reset at **00:00 UTC**. Rate limiting combines Bucket4j with a durable rolling one-minute database window; restarts cannot reset the allowance. Responses include `Retry-After` for HTTP 429.

Reconciliation runs every five minutes, checking known objects in batches and retrying incomplete uploads/deletions. Uploads have a 15-minute application deadline, socket timeouts, and a one-hour crash-recovery grace period. Missing or differently sized objects are marked `MISSING` and cannot be downloaded. Their accounted capacity remains until explicit deletion; reconciliation never automatically deletes completed files. Stale local staging files are removed after one hour.

A failure on one object does not stop checks or cleanup of the remaining objects. Failed operations are retried on later runs. An incomplete scan marks the provider as unavailable/degraded (`available: false`) and preserves its last complete usage snapshot and `last_reconciled` timestamp; partial totals are not published as a successful scan.

Reconciled known-object bytes, internal tracked bytes, and estimated billable bytes are exposed separately. Native provider-wide usage is unavailable (`provider_reported_bytes: null`), because bucket totals can include unrelated objects. Object versions, orphaned objects created outside OrbitStorage and incomplete multipart uploads are not enumerated by this MVP. It uses single-object PUTs, not multipart uploads. Configure provider lifecycle rules and monitor billing/versioning separately. Reconciliation is not a billing audit.

## API and monitoring

If no storage targets exist, `GET /api/v1/admin/providers` returns `200` with `[]`. Creating a valid project request in that state returns `409 application/problem+json` with detail `No storage found. Add a storage target before creating a project.` If storage exists but the selected `providerId` does not, creation returns `404` with `Storage target not found for the selected providerId`. Invalid request bodies still return `400`. These administrative operations require an admin key; project registration is not anonymous.


| Endpoint | Access |
|---|---|
| `POST /api/v1/files` | `files:write` |
| `GET /api/v1/files/{fileId}` | `files:read` |
| `GET /api/v1/files/{fileId}/metadata` | `files:read` |
| `DELETE /api/v1/files/{fileId}` | `files:delete` |
| `GET /api/v1/usage` | `usage:read` |
| `GET /api/v1/admin/projects` | Admin |
| `POST /api/v1/admin/projects` | Admin |
| `PATCH /api/v1/admin/projects/{id}` | Admin |
| `POST, GET /api/v1/admin/projects/{id}/keys` | Admin |
| `DELETE /api/v1/admin/projects/{id}/keys/{keyId}` | Admin |
| `GET, POST /api/v1/admin/providers` | Admin |
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

Setup tests: `python -m unittest discover -s scripts -p test_setup.py`. The full setup smoke test (`python scripts/smoke_setup.py`) builds an isolated Docker installation, verifies init without any storage targets or projects, storage add/list, later API provisioning, file operations and additional storage without changing projects or keys, then removes only its test containers and volumes. Test artifacts remain under git-ignored `build/`.

With Java 21+ and Docker running:

```sh
./gradlew check bootJar
# Windows: .\gradlew.bat check bootJar
```

Testcontainers starts disposable PostgreSQL and [Adobe S3Mock](https://github.com/adobe/S3Mock) containers; no real provider credentials are needed. Tests cover provider contracts, local path checks, S3 upload/download/delete, authentication, permissions/revocation, isolation, limits, simultaneous reservations, cleanup recovery, deletion, usage, encryption and API responses. Integration tests require Docker and fail if it is unavailable. CI runs the same suite on Java 21. S3Mock verifies API compatibility; a staging smoke test with your actual provider is still appropriate before production.

`docker compose logs -f orbit-storage` shows structured JSON logs. API keys, provider credentials, object contents and provider exception messages are excluded. Health/metrics are technical; the storage API provides application statistics.

## Operations

### Updates and image publication

Back up first, read migration notes, then run `git pull --ff-only` and `sh ./orbit init` (Windows: `.\orbit.ps1 init`). Releases pin the image version in `docker-compose.yml`; an optional `ORBIT_IMAGE` in `.env` overrides it and must be updated explicitly. For a deliberately mutable tag, run `docker compose pull orbit-storage` before `init`. Do not delete `.env` or volumes during updates. Database migrations run on startup; downgrades may require restoring the matching database backup.

GitHub Actions runs Java integration tests, Python tests and an isolated installation smoke test before publishing an image. Pushes to `main` publish `:main` and a commit tag; release tags such as `v0.1.0` publish `:0.1.0`. Images live in GHCR, not in Git. Maintainers must make the GHCR package public once under package settings so fresh installations can pull without login. To release a new version, update the default Compose image and documentation, push the commit, then push its matching `vX.Y.Z` tag and verify the Actions run and anonymous image pull. Production should use version tags or digests, not `:main`.

For backup and HTTPS deployment steps, see the [installation guide](docs/getting-started.de.md).

Start with one application replica and PostgreSQL. Durable quotas/rates also protect capacity across replicas, but replicas using LOCAL providers must share the same filesystem volume. Use S3 when deploying on separate hosts. The global lock favors correctness over high write throughput. Reserve DB connections for reconciliation and API traffic; the default connection pool is sufficient for the default two concurrent uploads/project.

At your TLS proxy, set a request-body limit no higher than `ORBIT_MAX_REQUEST_BYTES`, request/header/idle timeouts, a connection limit, and an unauthenticated request rate limit. The service limits authenticated uploads and container memory; edge limits protect against connection-level abuse. Do not allow other processes to mutate the local object directory or replace directories with symlinks. Back up data before upgrades. `docker compose down` preserves volumes; `down -v` deletes them.

See [architecture](docs/architecture.md), [contributing](CONTRIBUTING.md) and [security](SECURITY.md). Licensed under Apache-2.0.
