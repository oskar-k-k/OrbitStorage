"""Run with python -m unittest discover -s scripts -p test_setup.py."""
import base64
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

import setup


class FakeApi:
    def __init__(self):
        self.providers, self.projects, self.keys = [], [], []
        self.fail_after = None
        self.mutations = []
        self.bodies = []

    def call(self, method, path, body=None):
        if method == "GET":
            if path.endswith("/providers"):
                return self.providers
            if path.endswith("/projects"):
                return self.projects
            return self.keys
        self.mutations.append((method, path))
        self.bodies.append(body)
        if method == "DELETE":
            next(key for key in self.keys if key["id"] == path.rsplit("/", 1)[1])["revoked"] = True
            return
        if path.endswith("/providers"):
            result = {"id": str(uuid.uuid4()), "name": body["name"]}
            self.providers.append(result)
        elif path.endswith("/projects"):
            result = {"id": str(uuid.uuid4()), "name": body["name"], "provider_id": body["providerId"]}
            self.projects.append(result)
        else:
            result = {"id": body["requestId"], "key": "orb_test", "revoked": False}
            self.keys.append(result)
        if self.fail_after == path.rsplit("/", 1)[1]:
            self.fail_after = None
            raise setup.SetupError("Simulated lost response")
        return result


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.api = FakeApi()
        # ACL integration is exercised by the real Docker smoke test.
        self.protection = patch.object(setup, "protect")
        self.protection.start()
        self.addCleanup(self.protection.stop)

    def wizard(self, state=None):
        if state is None:
            path = self.root / ".orbit-setup/storage.json"
            state = json.loads(path.read_text()) if path.exists() else {}
        return setup.Wizard(self.root, self.api, state, True)

    def provision(self, wizard):
        return wizard.provider({"name": "Storage", "kind": "LOCAL", "minimumBillableBytes": 0})

    def test_repeated_setup_preserves_storage_without_projects(self):
        provider = self.provision(self.wizard())
        mutations = len(self.api.mutations)
        self.assertEqual(provider, self.provision(self.wizard()))
        self.assertEqual(mutations, len(self.api.mutations))
        self.assertEqual([], self.api.projects)
        self.assertEqual([], self.api.keys)

    def test_lost_provider_response_is_recovered(self):
        self.api.fail_after = "providers"
        with self.assertRaises(setup.SetupError):
            self.provision(self.wizard())
        self.provision(self.wizard())
        self.assertEqual(1, len(self.api.providers))

    def test_init_does_not_touch_storage_or_project_api(self):
        legacy = self.root / ".orbit-setup/state.json"
        legacy.parent.mkdir()
        original = json.dumps({"providerId": "old", "projectName": "Old pending project", "keyId": "untouched"})
        legacy.write_text(original)
        with patch.object(setup, "ROOT", self.root), patch.object(setup.shutil, "which", return_value="docker"), \
             patch.object(setup, "run", return_value=""), patch.object(setup, "ensure_env", return_value={"ORBIT_ADMIN_KEY": "admin"}), \
             patch.object(setup, "wait_ready"), patch.object(setup, "Api", return_value=self.api), \
             patch("builtins.input", side_effect=AssertionError("init asked a question")):
            setup.main(["init"])
        self.assertEqual(original, legacy.read_text())
        self.assertTrue(json.loads((legacy.parent / "init.json").read_text())["complete"])
        self.assertFalse(self.api.mutations)
        self.assertFalse((legacy.parent / "storage.json").exists())

    def test_list_only_reads_api_and_does_not_start_docker(self):
        (self.root / ".env").write_text("existing")
        with patch.object(setup, "ROOT", self.root), patch.object(setup, "run", side_effect=AssertionError("Started docker")), \
             patch.object(setup, "ensure_env", return_value={"ORBIT_ADMIN_KEY": "admin"}), patch.object(setup, "Api", return_value=self.api):
            setup.main(["storage", "list", "--json"])
        self.assertFalse(self.api.mutations)
        self.assertFalse((self.root / ".orbit-setup").exists())

    def test_init_uses_published_image_unless_source_build_requested(self):
        for flags in ([], ["--rebuild"]):
            with self.subTest(flags=flags), patch.object(setup, "ROOT", self.root), \
                 patch.object(setup.shutil, "which", return_value="docker"), \
                 patch.object(setup, "run", return_value="") as run, \
                 patch.object(setup, "ensure_env", return_value={"ORBIT_ADMIN_KEY": "admin"}), \
                 patch.object(setup, "wait_ready"):
                setup.main(["init"] + flags)
                command = next(call.args[0] for call in run.call_args_list if "up" in call.args[0])
                if flags:
                    self.assertIn(str(self.root / "docker-compose.build.yml"), command)
                    self.assertIn("--build", command)
                else:
                    self.assertNotIn("--build", command)
                    self.assertEqual(["--pull", "missing"], command[-2:])

    def test_storage_commands_require_init(self):
        with patch.object(setup, "ROOT", self.root):
            with self.assertRaisesRegex(setup.SetupError, "Run init first"):
                setup.main(["storage", "list"])
        self.assertFalse((self.root / ".env").exists())

    def test_add_recovers_legacy_storage_without_creating_projects(self):
        (self.root / ".env").write_text("existing")
        legacy = self.root / ".orbit-setup/state.json"
        legacy.parent.mkdir()
        legacy.write_text(json.dumps({"providerId": "old", "providerName": "Existing", "projectName": "Unused"}))
        self.api.providers = [{"id": "old", "name": "Existing", "kind": "LOCAL"}]
        with patch.object(setup, "ROOT", self.root), patch.object(setup, "ensure_env", return_value={"ORBIT_ADMIN_KEY": "admin"}), \
             patch.object(setup, "Api", return_value=self.api), patch("builtins.input", side_effect=AssertionError("Unexpected prompt")):
            setup.main(["storage", "add"])
        self.assertEqual("old", self.wizard().state["providerId"])
        self.assertTrue(self.wizard().state["complete"])
        self.assertFalse(self.api.mutations)
        self.assertTrue(json.loads(legacy.read_text())["storageMigrated"])

    def test_legacy_project_configuration_is_rejected(self):
        config = self.root / "config.json"
        config.write_text(json.dumps({"project": {"name": "Legacy"}}))
        with patch.object(setup.shutil, "which", return_value="docker"), patch.object(setup, "run", return_value=""):
            with self.assertRaisesRegex(setup.SetupError, "Create projects later"):
                setup.main(["init", "--config", str(config)])

    def test_s3_secrets_are_not_in_checkpoint(self):
        wizard = self.wizard()
        wizard.provider({"name": "S3", "kind": "S3", "s3": {"secretKey": "private-secret", "accessKey": "private-access"}})
        checkpoint = (self.root / ".orbit-setup/storage.json").read_text()
        self.assertNotIn("private-secret", checkpoint)
        self.assertNotIn("private-access", checkpoint)

    def test_ambiguous_recovery_does_not_guess(self):
        self.api.providers = [{"id": "a", "name": "Storage"}, {"id": "b", "name": "Storage"}]
        with self.assertRaises(setup.SetupError):
            self.wizard({"providerName": "Storage"}).provider()
        self.assertFalse(self.api.mutations)

    def test_environment_secrets_are_generated_once(self):
        with patch.object(setup, "run", return_value=""):
            first = setup.ensure_env(self.root, 18080)
            second = setup.ensure_env(self.root, 19000)
        self.assertEqual(first, second)
        self.assertNotEqual(first["DATABASE_PASSWORD"], first["ORBIT_ADMIN_KEY"])
        self.assertEqual(32, len(base64.b64decode(first["ORBIT_ENCRYPTION_KEY"])))

    def test_missing_environment_with_existing_volumes_stops(self):
        with patch.object(setup, "run", return_value="existing_postgres_data\n"):
            with self.assertRaises(setup.SetupError):
                setup.ensure_env(self.root, 18080)
        self.assertFalse((self.root / ".env").exists())

    def test_placeholder_config_is_preserved(self):
        original = "DATABASE_PASSWORD=replace-with-secret\n"
        (self.root / ".env").write_text(original)
        with self.assertRaises(setup.SetupError):
            setup.ensure_env(self.root, 18080)
        self.assertEqual(original, (self.root / ".env").read_text())

    def test_url_rejects_credential_forwarding_and_plain_public_http(self):
        for url in ("http://example.com", "https://user:password@example.com", "https://example.com/path", "https://example.com\nTOKEN=secret"):
            with self.subTest(url=url), self.assertRaises(setup.SetupError):
                setup.valid_url(url, public=True)
        self.assertEqual("http://127.0.0.1:18080", setup.valid_url("http://127.0.0.1:18080/", public=True))

    def test_noninteractive_missing_config_never_prompts(self):
        with patch("builtins.input", side_effect=AssertionError("Unexpected prompt")):
            with self.assertRaises(setup.SetupError):
                self.wizard().provider()

    def test_interactive_s3_request_and_secret_input(self):
        wizard = setup.Wizard(self.root, self.api, {})
        answers = ["s3", "S3 storage", "https://s3.example.com", "region", "bucket"]
        with patch("builtins.input", side_effect=answers), patch.object(setup.getpass, "getpass", side_effect=["access", "secret"]) as hidden:
            wizard.provider()
        self.assertTrue(self.api.bodies[0]["s3"]["pathStyle"])
        self.assertEqual(2, hidden.call_count)
        self.assertEqual(1, len(self.api.providers))
        self.assertNotIn("secret", (self.root / ".orbit-setup/storage.json").read_text())

    def test_aws_default_does_not_ask_for_path_style(self):
        wizard = setup.Wizard(self.root, self.api, {})
        with patch("builtins.input", side_effect=["s3", "AWS", "aws", "eu-central-1", "bucket"]), \
             patch.object(setup.getpass, "getpass", side_effect=["access", "secret"]):
            wizard.provider()
        self.assertFalse(self.api.bodies[0]["s3"]["pathStyle"])
        self.assertIsNone(self.api.bodies[0]["s3"]["endpoint"])

    def test_setup_lock_prevents_two_wizards_and_releases_on_exit(self):
        folder = self.root / ".orbit-setup"
        with setup.setup_lock(folder):
            with self.assertRaises(setup.SetupError):
                with setup.setup_lock(folder):
                    self.fail("A second setup acquired the lock")
        with setup.setup_lock(folder):
            pass


if __name__ == "__main__":
    unittest.main()
