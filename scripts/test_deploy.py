import unittest
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch
import deploy_release as release
from deploy_release import parse_command, pin_image, version_tuple


class DeploymentTest(unittest.TestCase):
    def test_only_exact_release_command_is_accepted(self):
        digest = 'sha256:' + 'a'*64
        revision = 'b'*40
        self.assertEqual(('v0.1.1', digest, revision), parse_command(f'deploy v0.1.1 {digest} {revision}'))
        for command in ('id', '', f'deploy v0.1.1 {digest} {revision}; id',
                        f'deploy main {digest} {revision}', f'deploy v01.1.1 {digest} {revision}',
                        f'deploy v0.1.1 {digest} $(id)', f'deploy v0.1.1 {digest} {revision}\n'):
            with self.subTest(command=command), self.assertRaises(ValueError):
                parse_command(command)

    def test_image_pinning_preserves_secrets_and_removes_old_override(self):
        original = 'DATABASE_PASSWORD=secret\nORBIT_IMAGE=old\nORBIT_ADMIN_KEY=key\n'
        result = pin_image(original, 'new')
        self.assertIn('DATABASE_PASSWORD=secret\n', result)
        self.assertIn('ORBIT_ADMIN_KEY=key\n', result)
        self.assertNotIn('ORBIT_IMAGE=old', result)
        self.assertEqual(1, result.count('ORBIT_IMAGE='))

    def test_release_order_is_numeric(self):
        self.assertGreater(version_tuple('v0.10.0'), version_tuple('v0.9.0'))

    @unittest.skipIf(os.name == 'nt', 'Production deployment uses Linux flock')
    def test_failed_health_rolls_back_only_without_database_migrations(self):
        digest = 'sha256:'+'a'*64
        revision = 'b'*40
        old = release.REPOSITORY+'@sha256:'+'c'*64
        for changed in (False, True):
            with self.subTest(migration_changed=changed), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                (root/'.env').write_text('ORBIT_ADMIN_KEY=private\n')
                (root/'docker-compose.yml').write_text('services: {}\n')
                def fake_run(args, capture=False, env=None):
                    if args[:2] == ['git','ls-remote']:
                        return revision+'\trefs/tags/v0.1.1^{}'
                    if args[:2] == ['docker','pull']:
                        return None
                    if args[:2] == ['docker','inspect']:
                        return 'previous-id'
                    if args[-1] == '{{json .Config.Labels}}':
                        return json.dumps({'org.opencontainers.image.revision':revision,'org.opencontainers.image.source':release.GIT_URL[:-4]})
                    if args[-1] == '{{json .RepoDigests}}':
                        return json.dumps([old])
                    if args[-1] == '{{.Id}}':
                        return 'next-id'
                    raise AssertionError(args)
                with patch.object(release,'ROOT',root), patch.object(release,'run',side_effect=fake_run), \
                     patch.object(release,'compose',return_value='container') as compose, \
                     patch.object(release,'migrations',side_effect=['before','after' if changed else 'before']), \
                     patch.object(release,'healthy',side_effect=[False,True]), \
                     patch.object(release.subprocess,'run'):
                    with self.assertRaisesRegex(RuntimeError,'healthy'):
                        release.deploy(f'deploy v0.1.1 {digest} {revision}')
                self.assertIn('ORBIT_ADMIN_KEY=private', (root/'.env').read_text())
                expected = release.REPOSITORY+'@'+digest if changed else old
                self.assertIn('ORBIT_IMAGE='+expected, (root/'.env').read_text())
                self.assertEqual(1 if changed else 2, sum(call.args[:1]==('up',) for call in compose.call_args_list))
                self.assertFalse((root/'.orbit-setup/deployed.json').exists())


if __name__ == '__main__':
    unittest.main()
