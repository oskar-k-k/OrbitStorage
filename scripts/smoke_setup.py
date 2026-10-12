"""Isolated Docker smoke test: python scripts/smoke_setup.py. Deletes only its own containers/volumes."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import urllib.request
import uuid

import setup


def main():
    source = Path(__file__).resolve().parent.parent
    name = "orbit-setup-test-" + uuid.uuid4().hex[:10]
    root = source / "build" / name
    root.mkdir(parents=True)
    for path in source.iterdir():
        if path.name in ("src", "gradle", "scripts"):
            shutil.copytree(path, root / path.name, ignore=shutil.ignore_patterns("__pycache__"))
        elif path.name in ("Dockerfile", "docker-compose.yml", "docker-compose.build.yml", ".dockerignore", "build.gradle", "settings.gradle", "gradlew", "gradlew.bat"):
            shutil.copy2(path, root / path.name)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    config = {"port": port}
    config_path = root / "build/setup-config.json"
    config_path.parent.mkdir()
    config_path.write_text(json.dumps(config))
    env = dict(os.environ, COMPOSE_PROJECT_NAME=name, ORBIT_IMAGE="orbitstorage-local:dev")
    command = [sys.executable, str(root / "scripts/setup.py"), "init", "--config", str(config_path)]
    try:
        subprocess.run(["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.build.yml",
                        "build", "orbit-storage"], cwd=root, env=dict(env, DATABASE_PASSWORD="build-only",
                        ORBIT_ADMIN_KEY="build-only", ORBIT_ENCRYPTION_KEY="build-only"), check=True)
        subprocess.run(command, cwd=root, env=env, check=True)
        state_path = root / ".orbit-setup/init.json"
        first = json.loads(state_path.read_text())
        original_env = (root / ".env").read_bytes()
        container_command = ["docker", "compose", "-p", name, "ps", "-q", "orbit-storage"]
        first_container = subprocess.check_output(container_command, cwd=root, env=env)
        subprocess.run(command, cwd=root, env=env, check=True)
        assert subprocess.check_output(container_command, cwd=root, env=env) == first_container, "Repeat setup recreated the running application"
        assert json.loads(state_path.read_text()) == first, "Repeat setup changed IDs or key"
        assert (root / ".env").read_bytes() == original_env, "Repeat setup changed secrets"
        admin_key = next(line.split("=", 1)[1] for line in (root / ".env").read_text().splitlines() if line.startswith("ORBIT_ADMIN_KEY="))
        admin = setup.Api(f"http://127.0.0.1:{port}", admin_key)
        assert admin.call("GET", "/api/v1/admin/projects") == [], "Initial setup created a project"
        assert not list((root / ".orbit-setup").glob("application-*.env"))
        assert admin.call("GET", "/api/v1/admin/providers") == [], "init created storage"
        cli = [sys.executable, str(root / "scripts/setup.py")]
        listing = subprocess.check_output(cli + ["storage", "list", "--json"], cwd=root, env=env, text=True)
        assert json.loads(listing) == []
        config = {"storage": {"name": "Smoke local", "kind": "LOCAL", "minimumBillableBytes": 0}}
        config_path.write_text(json.dumps(config))
        add_command = cli + ["storage", "add", "--config", str(config_path)]
        subprocess.run(add_command, cwd=root, env=env, check=True)
        first = json.loads((root / ".orbit-setup/storage.json").read_text())
        listing = subprocess.check_output(cli + ["storage", "list", "--json"], cwd=root, env=env, text=True)
        assert len(json.loads(listing)) == 1
        assert admin.call("GET", "/api/v1/admin/projects") == []
        # Applications provision projects later through the authorized admin API.
        project = admin.call("POST", "/api/v1/admin/projects", {"name": "API-created project",
            "providerId": first["providerId"], "quotaBytes": 10485760, "maxFileBytes": 1024, "allowedTypes": ["text/plain"]})
        issued = admin.call("POST", f"/api/v1/admin/projects/{project['id']}/keys", {"permissions": ["files:read", "files:write", "files:delete", "usage:read"]})
        api = setup.Api(admin.base, issued["key"])
        payload = b"OrbitStorage setup smoke test"
        request = urllib.request.Request(api.base + "/api/v1/files", method="POST", data=payload,
            headers={"Authorization": "Bearer " + issued["key"], "Content-Type": "text/plain", "X-Filename": "smoke.txt"})
        with api.opener.open(request) as response:
            file_id = json.load(response)["id"]
        request = urllib.request.Request(api.base + "/api/v1/files/" + file_id,
            headers={"Authorization": "Bearer " + issued["key"]})
        with api.opener.open(request) as response:
            assert response.read() == payload, "Download differs from upload"
        api.call("DELETE", "/api/v1/files/" + file_id)
        assert api.call("GET", "/api/v1/usage")["stored_bytes"] == 0
        # Adding storage never provisions another project or modifies existing keys.
        config["storage"] = {"name": "Second local storage", "kind": "LOCAL", "minimumBillableBytes": 0}
        config_path.write_text(json.dumps(config))
        subprocess.run(add_command, cwd=root, env=env, check=True)
        second = json.loads((root / ".orbit-setup/storage.json").read_text())
        assert second["providerId"] != first["providerId"]
        assert len(admin.call("GET", "/api/v1/admin/projects")) == 1
        keys = admin.call("GET", f"/api/v1/admin/projects/{project['id']}/keys")
        assert len(keys) == 1 and keys[0]["id"] == issued["id"] and not keys[0]["revoked"]
        assert subprocess.check_output(container_command, cwd=root, env=env) == first_container
        print("PASS: init without storage/projects, storage add/list, later API provisioning, file lifecycle, additional storage without restart or project/key changes.")
    finally:
        subprocess.run(["docker", "compose", "-p", name, "down", "-v", "--remove-orphans"], cwd=root, env=env, check=True)
        print(f"Test containers and volumes removed. Logs/configuration remain in {root} (git-ignored).")


if __name__ == "__main__":
    main()
