"""Exercise signing restoration with disposable keystores; never print private key material."""
import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile

script = Path(__file__).resolve().parents[1] / 'tools/restore-signing-key.sh'
checks = 0

def check(value, message):
    global checks
    if not value:
        raise AssertionError(message)
    checks += 1

with tempfile.TemporaryDirectory(prefix='acs-signing-test-') as temporary:
    root = Path(temporary)
    (root / 'build').mkdir()
    target = root / 'build/development.keystore'
    target.write_bytes(b'previous key remains intact')
    before = target.read_bytes()
    def run(secret=None):
        env = os.environ.copy()
        env.pop('KEYSTORE_BASE64', None)
        if secret is not None:
            env['KEYSTORE_BASE64'] = secret
        return subprocess.run(['bash', str(script)], cwd=root, env=env, capture_output=True)
    result = run()
    check(result.returncode != 0 and target.read_bytes() == before, 'missing secret must not replace the key')
    result = run('invalid%%%')
    check(result.returncode != 0 and target.read_bytes() == before, 'invalid base64 must not replace the key')
    result = run(base64.b64encode(b'not a keystore').decode())
    check(result.returncode != 0 and target.read_bytes() == before, 'invalid keystore must not replace the key')
    other = root / 'other.keystore'
    subprocess.run(['keytool','-genkeypair','-keystore',str(other),'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Disposable wrong test key'], check=True, capture_output=True)
    result = run(base64.b64encode(other.read_bytes()).decode())
    check(result.returncode != 0 and target.read_bytes() == before, 'wrong certificate must not replace the key')
    check(not list((root / 'build').glob('signing-key.*')), 'failed restoration removes private staging files')
    # CI's hard requirement must fail before any SDK tools or replacement-key generation.
    guard = root / 'guard'
    guard.mkdir()
    guarded_script = guard / 'build.sh'
    guarded_script.write_bytes((script.parents[1] / 'build.sh').read_bytes())
    guarded_env = os.environ.copy()
    guarded_env.update(CITYSTUDIO_REQUIRE_SIGNING_KEY='1', ANDROID_SDK_ROOT='/unavailable-sdk')
    result = subprocess.run(['bash', str(guarded_script)], env=guarded_env, capture_output=True)
    check(result.returncode != 0 and b'Existing signing key is required' in result.stderr and not (guard/'build/development.keystore').exists(), 'CI missing-key guard never generates a substitute key')
    # Optional local-only success test: path is never placed in GitHub or printed.
    reference = os.environ.get('ACS_REFERENCE_KEYSTORE')
    if reference:
        key = Path(reference).read_bytes()
        result = run(base64.b64encode(key).decode())
        check(result.returncode == 0 and target.read_bytes() == key, 'original certificate is accepted')
        check(target.stat().st_mode & 0o777 == 0o600, 'restored private key permissions are restricted')
        check(base64.b64encode(key) not in result.stdout + result.stderr, 'private key never appears in output')
print(f'SigningWorkflowTests passed: {checks} checks')
