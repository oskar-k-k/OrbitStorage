#!/usr/bin/env python3
"""Root-owned, forced-command release deployment on the production host (Linux)."""
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path('/opt/orbitstorage')
REPOSITORY = 'ghcr.io/oskar-k-k/orbitstorage'
GIT_URL = 'https://github.com/oskar-k-k/OrbitStorage.git'


def parse_command(command):
    match = re.fullmatch(r'deploy (v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)) (sha256:[0-9a-f]{64}) ([0-9a-f]{40})', command)
    if not match:
        raise ValueError('Only deploy vMAJOR.MINOR.PATCH sha256:DIGEST COMMIT is permitted')
    return match[1], match[5], match[6]


def version_tuple(tag):
    return tuple(map(int, tag[1:].split('.')))


def pin_image(content, image):
    lines = [line for line in content.splitlines() if not line.startswith('ORBIT_IMAGE=')]
    return '\n'.join(lines) + '\nORBIT_IMAGE=' + image + '\n'


def private_write(path, content):
    temp = path.with_suffix(path.suffix + '.tmp')
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temp, path)


def run(args, capture=False, env=None):
    # Never inherit caller-controlled PATH, Docker configuration or Compose settings.
    clean = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root'}
    if env:
        clean.update(env)
    result = subprocess.run(args, cwd=ROOT, env=clean, check=True, capture_output=capture, text=True, timeout=600)
    return result.stdout.strip() if capture else None


def compose(*args, capture=False, image=None):
    return run(['docker', 'compose', '--project-name', 'orbitstorage', '--env-file', str(ROOT/'.env'),
                '-f', str(ROOT/'docker-compose.yml'), *args], capture, {'ORBIT_IMAGE': image} if image else None)


def migrations():
    return compose('exec', '-T', 'postgres', 'psql', '-U', 'orbitstorage', '-d', 'orbitstorage', '-Atc',
                   'SELECT installed_rank,version,checksum,success FROM flyway_schema_history ORDER BY installed_rank', capture=True)


def healthy(seconds=180):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    end = time.monotonic() + seconds
    while True:
        try:
            with opener.open('http://127.0.0.1:18080/actuator/health', timeout=5) as response:
                if json.load(response).get('status') == 'UP':
                    return True
        except Exception:
            pass
        if time.monotonic() >= end:
            return False
        time.sleep(3)


def deploy(command):
    import fcntl
    tag, digest, revision = parse_command(command)
    state_dir = ROOT / '.orbit-setup'
    state_dir.mkdir(mode=0o700, exist_ok=True)
    with open(state_dir/'deploy.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state_file = state_dir/'deployed.json'
        state = json.loads(state_file.read_text()) if state_file.exists() else {}
        if state and version_tuple(tag) < version_tuple(state['tag']):
            raise ValueError('Refusing an older release; rollback requires the server administrator')
        if state.get('tag') == tag and state.get('digest') != digest:
            raise ValueError('This version is already deployed with another digest; publish a new version')
        refs = run(['git', 'ls-remote', GIT_URL, 'refs/tags/'+tag, 'refs/tags/'+tag+'^{}'], capture=True)
        refs = dict(line.split()[::-1] for line in refs.splitlines())
        if refs.get('refs/tags/'+tag+'^{}', refs.get('refs/tags/'+tag)) != revision:
            raise ValueError('Release tag does not match the requested commit')
        image = REPOSITORY+'@'+digest
        run(['docker', 'pull', image])
        labels = json.loads(run(['docker', 'image', 'inspect', image, '--format', '{{json .Config.Labels}}'], capture=True))
        if labels.get('org.opencontainers.image.revision') != revision or labels.get('org.opencontainers.image.source') != GIT_URL[:-4]:
            raise ValueError('Image provenance labels do not match the release')
        cid = compose('ps', '-q', 'orbit-storage', capture=True)
        previous_id = run(['docker', 'inspect', cid, '--format', '{{.Image}}'], capture=True)
        previous_digests = json.loads(run(['docker', 'image', 'inspect', previous_id, '--format', '{{json .RepoDigests}}'], capture=True))
        previous = next((d for d in previous_digests if d.startswith(REPOSITORY+'@')), previous_id)
        target = run(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'], capture=True)
        if state.get('digest') == digest and previous_id == target and healthy(0):
            print('Release already deployed and healthy')
            return
        original_env = (ROOT/'.env').read_text()
        backup = ROOT/'backups'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
        backup.mkdir(parents=True, mode=0o700)
        backup.parent.chmod(0o700)
        private_write(backup/'.env', original_env)
        private_write(backup/'docker-compose.yml', (ROOT/'docker-compose.yml').read_text())
        private_write(backup/'release.json', json.dumps({'previous_image': previous, 'next_image': image, 'tag': tag, 'revision': revision}))
        before = migrations()
        compose('stop', 'orbit-storage')
        try:
            dump = backup/'database.dump'
            with open(dump, 'wb') as out:
                dump.chmod(0o600)
                subprocess.run(['docker','compose','--project-name','orbitstorage','--env-file',str(ROOT/'.env'),
                                '-f',str(ROOT/'docker-compose.yml'),'exec','-T','postgres','pg_dump','-U','orbitstorage',
                                '-d','orbitstorage','-Fc'], cwd=ROOT, stdout=out, check=True, timeout=120,
                               env={'PATH':'/usr/bin:/bin','HOME':'/root'})
            private_write(ROOT/'.env', pin_image(original_env, image))
            compose('up','-d','--no-deps','orbit-storage')
            if not healthy():
                raise RuntimeError('New release did not become healthy')
        except Exception:
            if migrations() == before:
                # Preserve the known-running image even if the old .env used a mutable tag.
                private_write(ROOT/'.env', pin_image(original_env, previous))
                compose('up','-d','--no-deps','orbit-storage')
                print('Previous image restored; health=' + str(healthy()), flush=True)
            else:
                print('Database migrations changed. Automatic downgrade skipped; administrator recovery required. Backup: '+str(backup), flush=True)
            raise
        private_write(state_file, json.dumps({'tag': tag, 'digest': digest, 'revision': revision, 'backup': str(backup)})+'\n')
        print('Deployed '+tag+' at '+digest+'; health=UP; backup='+str(backup))


if __name__ == '__main__':
    try:
        if os.geteuid() != 0 or len(sys.argv) != 2:
            raise ValueError('Run through the restricted deployment SSH command')
        deploy(sys.argv[1])
    except Exception as error:
        # Subprocess output must never include environment contents or credentials.
        print('Deployment failed: '+(str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
