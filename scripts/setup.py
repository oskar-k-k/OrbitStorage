#!/usr/bin/env python3
"""Interactive OrbitStorage setup. Standard library only; Python 3.10+."""
import argparse
import base64
import contextlib
import getpass
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import warnings

ROOT = Path(__file__).resolve().parent.parent


class SetupError(Exception):
    pass


def run(args, root=ROOT, capture=False):
    result = subprocess.run(args, cwd=root, text=True, capture_output=capture)
    if result.returncode:
        raise SetupError(f"Command failed: {' '.join(args[:3])}. Fix the problem and rerun setup.")
    return result.stdout or ""


def protect(path):
    if os.name == "nt":
        identity = run(["whoami"], capture=True).strip()
        run(["icacls", str(path), "/inheritance:r", "/grant:r", f"{identity}:(F)"], capture=True)
    else:
        path.chmod(0o700 if path.is_dir() else 0o600)


def save_private(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    # Establish restrictive permissions before writing any secrets.
    with open(temp, "w", encoding="utf-8") as output:
        protect(temp)
        output.write(text)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temp, path)


@contextlib.contextmanager
def setup_lock(folder):
    folder.mkdir(exist_ok=True)
    protect(folder)
    with open(folder / "lock", "a+b") as lock:
        lock.seek(0)
        lock.write(b"0")
        lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise SetupError("Another setup process is running in this checkout.") from None
        yield


def ask(label, default=None, secret=False):
    while True:
        prompt = label + (f" [{default}]" if default is not None else "") + ": "
        if secret:
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                try:
                    value = getpass.getpass(prompt)
                except getpass.GetPassWarning:
                    raise SetupError("Hidden input requires an interactive terminal.") from None
        else:
            value = input(prompt).strip()
        if not value and default is not None:
            value = str(default)
        if value and not any(ord(c) < 32 for c in value):
            return value
        print("Please enter a value.")


def choice(label, values, default):
    while True:
        value = ask(label, default).lower()
        if value in values:
            return value
        print("Choose " + ", ".join(values))


def name_input(label, default):
    while True:
        value = ask(label, default)
        if len(value) <= 120:
            return value
        print("Use at most 120 characters.")


def valid_url(value, public=False):
    if not isinstance(value, str) or any(c.isspace() or ord(c) < 32 for c in value):
        raise SetupError("URL must not contain whitespace or control characters.")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise SetupError("Use a base URL such as https://storage.example.com (no path or credentials).")
    if public and parsed.scheme != "https" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise SetupError("Public URLs must use HTTPS. Configure your TLS reverse proxy first, or use localhost.")
    return value.rstrip("/")


def ensure_env(root, port):
    if not isinstance(port, int) or not 1 <= port <= 65535:
        raise SetupError("Setup port must be a whole number between 1 and 65535.")
    path = root / ".env"
    if not path.exists():
        project = os.environ.get("COMPOSE_PROJECT_NAME", root.name.lower())
        volumes = run(["docker", "volume", "ls", "--filter", f"label=com.docker.compose.project={project}", "--format", "{{.Name}}"], root, True)
        if volumes.strip():
            raise SetupError("Docker volumes already exist but .env is missing. Restore the original .env from backup.")
        values = {
            "DATABASE_PASSWORD": secrets.token_hex(32), "ORBIT_PORT": str(port),
            "ORBIT_ADMIN_KEY": secrets.token_hex(32), "ORBIT_MONITORING_KEY": secrets.token_hex(32),
            "ORBIT_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
            "ORBIT_GLOBAL_BUDGET": "107374182400", "ORBIT_WARNING_PERCENT": "80",
            "ORBIT_BLOCKING_PERCENT": "95", "ORBIT_MAX_REQUEST_BYTES": "104857600",
            "ORBIT_RECONCILIATION_DELAY_MS": "300000", "ORBIT_DOCS_ENABLED": "false",
        }
        save_private(path, "".join(f"{key}={value}\n" for key, value in values.items()))
    protect(path)
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep:
            raise SetupError("Invalid .env line; use KEY=value.")
        values[key.strip()] = value.strip().strip("\"'")
    for name in ("DATABASE_PASSWORD", "ORBIT_ADMIN_KEY", "ORBIT_ENCRYPTION_KEY"):
        if not values.get(name) or values[name].startswith("replace-with"):
            raise SetupError(f"Existing .env has no valid {name}. It was preserved; fill in or restore this value.")
    for name, value in values.items():
        if name in os.environ and os.environ[name] != value:
            raise SetupError(f"Environment variable {name} overrides .env. Unset it before running setup.")
    return values


class Api:
    def __init__(self, base, key):
        self.base, self.key = base, key
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        # Never proxy local administration or forward credentials on redirects.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, body=None):
        headers = {"Authorization": "Bearer " + self.key}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raise SetupError(f"API {method} {path}: HTTP {error.code}. Check configuration and rerun setup.") from None
        except (OSError, ValueError):
            raise SetupError(f"API {method} {path}: no confirmed response. Rerun setup to recover.") from None


def wait_ready(api, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if api.call("GET", "/actuator/health").get("status") == "UP":
                return
        except SetupError:
            pass
        time.sleep(2)
    raise SetupError("Service did not become healthy. Check: docker compose logs --tail=100 orbit-storage")


def select_existing(items, label):
    print(f"Existing {label}:")
    for number, item in enumerate(items, 1):
        print(f"  {number}. {item['name']} ({item['id']})")
    print("  0. Create new")
    while True:
        value = ask("Selection", "1" if items else "0")
        if value.isdigit() and 0 <= int(value) <= len(items):
            return items[int(value)-1] if int(value) else None


class Wizard:
    def __init__(self, root, api, state, noninteractive=False, checkpoint="storage.json"):
        self.root, self.api, self.state = root, api, state
        self.noninteractive = noninteractive
        self.checkpoint = checkpoint

    def save(self):
        save_private(self.root / ".orbit-setup" / self.checkpoint, json.dumps(self.state, indent=2) + "\n")

    def provider(self, config=None, force_new=False):
        items = self.api.call("GET", "/api/v1/admin/providers")
        if self.state.get("providerId"):
            if not any(item["id"] == self.state["providerId"] for item in items):
                raise SetupError("Saved storage connection no longer exists. Restore the database before resuming.")
            return self.state["providerId"]
        pending = self.state.get("providerName")
        if pending:
            matches = [item for item in items if item["name"] == pending]
            if len(matches) > 1:
                raise SetupError("Multiple storage connections match the pending name; resolve the ambiguity before continuing.")
            if matches:
                self.state["providerId"] = matches[0]["id"]
                self.save()
                return matches[0]["id"]
        if not pending and not config and not force_new and not self.noninteractive:
            selected = select_existing(items, "storage connections") if items else None
            if selected:
                self.state["providerId"] = selected["id"]
                self.save()
                return selected["id"]
        if config:
            request = dict(config)
            if "id" in request:
                found = next((item for item in items if item["id"] == request["id"]), None)
                if not found:
                    raise SetupError("Configured provider id does not exist.")
                self.state["providerId"] = found["id"]
                self.save()
                return found["id"]
        else:
            if self.noninteractive:
                raise SetupError("Configuration needs a provider object or provider.id.")
            kind = choice("Storage: local or s3", ("local", "s3"), "local").upper()
            request = {"name": pending or name_input("Storage connection name", "Main storage"), "kind": kind, "minimumBillableBytes": 0}
            if kind == "S3":
                endpoint = ask("S3 endpoint URL (enter aws for AWS default)", "aws")
                request["s3"] = {
                    "endpoint": None if endpoint == "aws" else valid_url(endpoint),
                    "region": ask("S3 region"), "bucket": ask("Existing bucket name"),
                    "accessKey": ask("S3 access key", secret=True), "secretKey": ask("S3 secret key", secret=True),
                    "pathStyle": endpoint != "aws",
                }
        if not pending and any(item["name"] == request["name"] for item in items):
            raise SetupError("Storage name already exists. Select it by id or choose a unique name.")
        self.state["providerName"] = request["name"]
        self.save()  # Recovery marker only; never persist S3 credentials here.
        result = self.api.call("POST", "/api/v1/admin/providers", request)
        self.state["providerId"] = result["id"]
        self.save()
        return result["id"]


def main(argv=None):
    if sys.version_info < (3, 10):
        raise SetupError("Python 3.10 or newer is required.")
    parser = argparse.ArgumentParser(description="OrbitStorage: start the service, then manage storage targets separately.")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="Start the service only; no storage targets or projects")
    init.add_argument("--config", type=Path, help="Optional JSON with port and url")
    init.add_argument("--rebuild", action="store_true", help="Build and run local source instead of the published image")
    storages = commands.add_parser("storage", help="Manage configured storage connections")
    actions = storages.add_subparsers(dest="action", required=True)
    add = actions.add_parser("add", help="Register a local or S3 storage without creating projects")
    add.add_argument("--config", type=Path, help="Private JSON containing a storage object")
    listing = actions.add_parser("list", help="List configured storage targets, not all buckets in your cloud account")
    listing.add_argument("--json", action="store_true", help="Print machine-readable JSON")
    arguments = list(sys.argv[1:] if argv is None else argv)
    if not arguments:
        arguments = ["init"]
    args = parser.parse_args(arguments)
    config_path = getattr(args, "config", None)
    config = json.loads(config_path.read_text(encoding="utf-8-sig")) if config_path else {}
    if not isinstance(config, dict):
        raise SetupError("Configuration must be a JSON object.")
    if "project" in config:
        raise SetupError("Create projects later through the admin API, not through init or storage add.")
    if args.command == "init" and ("storage" in config or "provider" in config):
        raise SetupError("init only starts the service. Use storage add --config for storage configuration.")
    folder = ROOT / ".orbit-setup"
    if args.command == "init":
        if not shutil.which("docker"):
            raise SetupError("Docker is required. Install Docker with Compose and start it, then rerun init.")
        run(["docker", "compose", "version"], capture=True)
        run(["docker", "info"], capture=True)
        with setup_lock(folder):
            # Legacy checkpoints are intentionally not consumed or overwritten by init.
            state_path = folder / "init.json"
            state = json.loads(state_path.read_text()) if state_path.exists() else {}
            env = ensure_env(ROOT, config.get("port", 18080))
            base = local_url(env)
            state["url"] = valid_url(config.get("url", state.get("url", base)), public=True)
            save_private(state_path, json.dumps(state, indent=2))
            print("Starting OrbitStorage and PostgreSQL...", flush=True)
            command = ["docker", "compose", "--env-file", str(ROOT / ".env"), "-f", str(ROOT / "docker-compose.yml")]
            if args.rebuild:
                command.extend(["-f", str(ROOT / "docker-compose.build.yml")])
            command.extend(["up", "-d"])
            command.extend(["--build"] if args.rebuild else ["--pull", "missing"])
            run(command)
            wait_ready(Api(base, env["ORBIT_ADMIN_KEY"]))
            state["complete"] = True
            save_private(state_path, json.dumps(state, indent=2))
            print(f"OrbitStorage is running. Local API: {base}")
            if state["url"] != base:
                print(f"Configured public URL: {state['url']} (HTTPS/reverse proxy must be configured separately)")
            print("No storage targets, projects or project keys were created.")
            print("Next: orbit storage add, or GET /api/v1/admin/providers with your admin key.")
            print("Admin key: ORBIT_ADMIN_KEY in the private .env file. Back up .env, PostgreSQL and stored files.")
        return
    if not (ROOT / ".env").exists():
        raise SetupError("Run init first. Storage commands do not start or initialize the service.")
    env = ensure_env(ROOT, 18080)
    api = Api(local_url(env), env["ORBIT_ADMIN_KEY"])
    if args.action == "list":
        items = api.call("GET", "/api/v1/admin/providers")
        if args.json:
            print(json.dumps(items, indent=2))
        elif not items:
            print("No storage targets configured. Run orbit storage add.")
        else:
            print("ID | NAME | TYPE | BUCKET")
            for item in items:
                print(f"{item['id']} | {item['name']} | {item['kind']} | {item.get('bucket', '-')}")
        return
    with setup_lock(folder):
        path = folder / "storage.json"
        state = json.loads(path.read_text()) if path.exists() else {}
        if state.get("complete"):
            state = {}
        if not state:
            # Recover an interrupted pre-command wizard, without resuming project creation.
            legacy = folder / "state.json"
            if legacy.exists():
                old = json.loads(legacy.read_text())
                if not old.get("storageMigrated"):
                    state = {k: old[k] for k in ("providerName", "providerId") if k in old}
        wizard = Wizard(ROOT, api, state, bool(config_path))
        wizard.save()
        provider_id = wizard.provider(config.get("storage", config.get("provider")), force_new=True)
        state["complete"] = True
        wizard.save()
        legacy = folder / "state.json"
        if legacy.exists():
            old = json.loads(legacy.read_text())
            old["storageMigrated"] = True
            save_private(legacy, json.dumps(old, indent=2))
        print(f"Storage ready: {provider_id}")
        print("No projects were created. Use this ID as providerId when provisioning a project through the admin API.")


def local_url(env):
    port = int(env.get("ORBIT_PORT", 18080))
    if not 1 <= port <= 65535:
        raise SetupError("ORBIT_PORT must be between 1 and 65535.")
    return f"http://127.0.0.1:{port}"



if __name__ == "__main__":
    try:
        main()
    except (SetupError, ValueError, KeyError, OSError) as error:
        print("Setup stopped: " + (str(error) if isinstance(error, SetupError) else "Invalid configuration or inaccessible local file. Check your settings and rerun."), file=sys.stderr)
        sys.exit(1)
    except (KeyboardInterrupt, EOFError):
        print("\nSetup interrupted. Rerun the same command to resume.", file=sys.stderr)
        sys.exit(130)
