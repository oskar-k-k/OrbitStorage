CREATE TABLE providers (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('LOCAL', 'S3')),
    encrypted_config text NOT NULL,
    minimum_billable_bytes bigint NOT NULL CHECK (minimum_billable_bytes >= 0),
    available boolean,
    last_reconciled timestamptz,
    observed_bytes bigint,
    missing_objects bigint,
    mismatched_objects bigint
);
CREATE TABLE projects (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    provider_id uuid NOT NULL REFERENCES providers(id),
    storage_assignment_id uuid NOT NULL UNIQUE,
    quota_bytes bigint NOT NULL CHECK (quota_bytes > 0),
    max_file_bytes bigint NOT NULL CHECK (max_file_bytes > 0),
    daily_upload_bytes bigint NOT NULL CHECK (daily_upload_bytes > 0),
    uploads_per_minute integer NOT NULL CHECK (uploads_per_minute > 0),
    concurrent_uploads integer NOT NULL CHECK (concurrent_uploads > 0),
    allowed_types text NOT NULL,
    stored_bytes bigint NOT NULL DEFAULT 0 CHECK (stored_bytes >= 0),
    billable_bytes bigint NOT NULL DEFAULT 0 CHECK (billable_bytes >= 0),
    reserved_bytes bigint NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
    file_count bigint NOT NULL DEFAULT 0 CHECK (file_count >= 0),
    uploaded_bytes bigint NOT NULL DEFAULT 0,
    downloaded_bytes bigint NOT NULL DEFAULT 0,
    rate_violations bigint NOT NULL DEFAULT 0
);
CREATE TABLE api_keys (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    key_hash varchar(64) NOT NULL UNIQUE,
    permissions text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    revoked boolean NOT NULL DEFAULT false
);
CREATE TABLE files (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    provider_id uuid NOT NULL REFERENCES providers(id),
    storage_assignment_id uuid NOT NULL REFERENCES projects(storage_assignment_id),
    object_key varchar(100) NOT NULL UNIQUE,
    original_name varchar(255) NOT NULL,
    content_type varchar(255) NOT NULL,
    actual_size bigint NOT NULL CHECK (actual_size > 0),
    billable_size bigint NOT NULL CHECK (billable_size >= actual_size),
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL CHECK (status IN ('UPLOADING','READY','DELETING','CLEANUP','MISSING'))
);
CREATE INDEX files_project_idx ON files(project_id, uploaded_at);
CREATE TABLE upload_days (
    project_id uuid NOT NULL REFERENCES projects(id),
    day date NOT NULL,
    bytes bigint NOT NULL DEFAULT 0,
    successful_bytes bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, day)
);
CREATE TABLE upload_requests (
    project_id uuid NOT NULL REFERENCES projects(id),
    requested_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX upload_requests_idx ON upload_requests(project_id, requested_at);
CREATE TABLE capacity_lock (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO capacity_lock VALUES (1);
