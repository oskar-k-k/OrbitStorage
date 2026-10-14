# Automatic release deployment

The production workflow is opt-in. Ordinary pushes to `main` run tests and publish an image but do not change production. A stable release tag such as `v0.1.1` runs tests, publishes the image and deploys that exact image digest after successful checks. No deployment runs for pull requests or forks.

## Release a change

1. Update the application version in `build.gradle`, the JAR path in `Dockerfile`, and the default image in `docker-compose.yml` and documentation. Commit and push to `main`.
2. Create a new version tag on the intended commit and push it:

   ```sh
   git tag -a v0.1.1 -m "OrbitStorage 0.1.1"
   git push origin v0.1.1
   ```

   This version is an example; use the next unused version. Never move or reuse a published tag.

3. Watch **Actions → Build and integration tests**. The `deploy` job must be green, including its public HTTPS health check.

Re-running a successful deployment with the same digest is safe. Older versions are rejected to prevent an older queued workflow from overwriting a newer release. Deployments are serialized without interrupting an active deployment.

## Server setup (administrator, once)

The deployment scripts are for Linux, a root-owned `/opt/orbitstorage` installation, Compose project `orbitstorage`, local API port 18080 and the image repository `ghcr.io/oskar-k-k/orbitstorage`. They update only the application image. Database and proxy configuration are preserved. Adapt and audit these fixed values for another host.

Install `scripts/deploy_release.py` as `/usr/local/sbin/orbitstorage-deploy` and `scripts/deploy_ssh_entry.sh` as `/usr/local/bin/orbitstorage-deploy-entry`, both root-owned and executable. The helper is installed separately from the application; image updates do not replace this trusted script.

Create a dedicated `orbit-deploy` user with no password login and no Docker group membership. Keep its home, `.ssh` directory and `authorized_keys` root-owned. Add only its dedicated public key with these restrictions:

```text
restrict,command="/usr/local/bin/orbitstorage-deploy-entry" ssh-ed25519 PUBLIC_KEY
```

Allow only this helper in `/etc/sudoers.d/orbitstorage-deploy` and validate it using `visudo -cf`:

```sudoers
orbit-deploy ALL=(root) NOPASSWD: /usr/local/sbin/orbitstorage-deploy *
```

The root-owned helper strictly validates its single argument: `deploy vMAJOR.MINOR.PATCH sha256:DIGEST COMMIT`. It rejects arbitrary commands, shell syntax and malformed references; verifies the remote Git release tag and image revision/source labels; and pulls only from the fixed OrbitStorage image repository. The key has no interactive shell, PTY, SSH forwarding or general Docker access. Publishers with write access to the image repository and release tags remain trusted to publish executable application code.

## GitHub configuration

Create the GitHub environment `production`, restricted to release tags `v*`. Put these **environment secrets** there:

| Secret | Value |
|---|---|
| `ORBIT_DEPLOY_HOST` | Production server hostname or IPv4 address |
| `ORBIT_DEPLOY_SSH_KEY` | Dedicated deployment private key; not a personal/root key |
| `ORBIT_DEPLOY_KNOWN_HOSTS` | Verified server SSH host-key line, obtained through an already trusted connection |

Set these **repository variables**:

| Variable | Value |
|---|---|
| `ORBIT_DEPLOY_ENABLED` | `true` |
| `ORBIT_HEALTH_URL` | Public HTTPS URL ending in `/actuator/health` |

Never commit private keys or server `.env` files. Actions materializes the SSH key only in a temporary file with private permissions and deletes it on exit. SSH host-key verification stays enabled. Public repositories expose workflow code and logs, not the stored secret values. Restrict repository write access accordingly.

## What happens during deployment

The helper pulls the image first, records the previous immutable image reference, then stops only OrbitStorage. It saves a PostgreSQL dump and private configuration under `/opt/orbitstorage/backups/<timestamp>/`, pins `ORBIT_IMAGE` in the existing `.env` to the new digest and starts the application. Startup runs its normal database migrations. The local health check must succeed within three minutes; Actions also checks the public HTTPS endpoint.

There is a brief API interruption during backup and restart. PostgreSQL, the proxy and other applications are not restarted. Object files and S3 credentials are not replaced. Backups stay on the same server, are not automatically expired and do not include S3/local object contents; separately arrange off-server backups and monitor disk usage.

If startup fails and Flyway migration history is unchanged, the helper restores the previous image and checks health again; the job still fails so the incident is visible. If migrations changed, it does not automatically downgrade the application or restore the database. An administrator must inspect and restore a compatible backup if needed. A failure of the external HTTPS check also fails the job, without rolling back a healthy application merely because DNS or the proxy failed.

The server checkout and host-specific Compose/proxy settings are deliberately not overwritten by releases. Changes to networking, Compose, the deployment helper or other host configuration require a separate administrator update. `ORBIT_IMAGE` remains pinned across ordinary `orbit init` calls. To pause automatic deployment, set `ORBIT_DEPLOY_ENABLED=false` in GitHub.
