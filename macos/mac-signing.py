#!/usr/bin/env python3
"""Load the shared BWS signing identity into an isolated build keychain."""
import base64
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile

REFERENCES = ('MACOS_SIGNING_CERTIFICATE_ID', 'MACOS_SIGNING_PASSWORD_ID', 'MACOS_SIGNING_IDENTITY_ID')


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout


def cleanup(directory):
    directory = Path(directory)
    if not directory.name.startswith('mac-app-signing-'):
        raise SystemExit('Refusing an unrelated cleanup directory')
    previous = directory / 'search-list.json'
    if previous.exists():
        run('security', 'list-keychains', '-d', 'user', '-s', *json.loads(previous.read_text()))
    keychain = directory / 'build.keychain-db'
    if keychain.exists():
        run('security', 'delete-keychain', str(keychain))
    shutil.rmtree(directory)


def setup():
    identifiers = {name: os.environ.get(name) for name in REFERENCES}
    if not all(identifiers.values()) and os.environ.get('CI') != 'true':
        # Local builds use the same repository configuration as release CI.
        configured = {item['name']: item['value'] for item in
                      json.loads(run('gh', 'variable', 'list', '--json', 'name,value'))}
        identifiers = {name: identifiers[name] or configured.get(name) for name in REFERENCES}
    if not all(identifiers.values()):
        raise SystemExit('Missing Mac signing BWS lookup references in repository variables')
    values = {key: json.loads(run('bws', 'secret', 'get', identifiers[name], '-o', 'json'))['value']
              for key, name in zip(('certificate', 'password', 'identity'), REFERENCES)}
    if not re.fullmatch(r'[0-9A-Fa-f]{40}', values['identity']):
        raise SystemExit('Invalid signing certificate fingerprint')
    directory = Path(tempfile.mkdtemp(prefix='mac-app-signing-', dir=os.environ.get('RUNNER_TEMP')))
    directory.chmod(0o700)
    previous = [line.strip().strip('"') for line in run('security', 'list-keychains', '-d', 'user').splitlines()]
    (directory / 'search-list.json').write_text(json.dumps(previous))
    try:
        keychain = directory / 'build.keychain-db'
        password = secrets.token_urlsafe(32)
        archive = directory / 'signing.p12'
        archive.write_bytes(base64.b64decode(values['certificate'], validate=True))
        archive.chmod(0o600)
        env = os.environ.copy()
        env['MAC_P12_PASSWORD'] = values['password']
        pem = directory / 'signing.pem'
        # Decrypt through the environment; the long-lived password never enters argv.
        run('/usr/bin/openssl', 'pkcs12', '-in', str(archive), '-passin', 'env:MAC_P12_PASSWORD',
            '-nodes', '-out', str(pem), env=env)
        pem.chmod(0o600)
        cert = directory / 'certificate.pem'
        run('/usr/bin/openssl', 'x509', '-in', str(pem), '-out', str(cert))
        fingerprint = run('/usr/bin/openssl', 'x509', '-in', str(cert), '-noout', '-fingerprint', '-sha1').strip().split('=')[1].replace(':', '')
        if fingerprint != values['identity']:
            raise RuntimeError('Signing certificate fingerprint mismatch')
        run('security', 'create-keychain', '-p', password, str(keychain))
        run('security', 'set-keychain-settings', '-lut', '21600', str(keychain))
        run('security', 'unlock-keychain', '-p', password, str(keychain))
        run('security', 'import', str(pem), '-k', str(keychain), '-T', '/usr/bin/codesign')
        run('security', 'set-key-partition-list', '-S', 'apple-tool:,apple:,codesign:', '-s', '-k', password, str(keychain))
        run('security', 'list-keychains', '-d', 'user', '-s', str(keychain), *previous)
        archive.unlink()
        pem.unlink()
        fields = {'MACOS_SIGNING_IDENTITY': fingerprint, 'MACOS_SIGNING_KEYCHAIN': str(keychain),
                  'MACOS_SIGNING_DIRECTORY': str(directory)}
        destination = os.environ.get('GITHUB_ENV')
        if destination:
            with open(destination, 'a') as output:
                for name, value in fields.items():
                    output.write(f'{name}={value}\n')
        return fields
    except BaseException:
        cleanup(directory)
        raise


if __name__ == '__main__':
    if sys.argv[1:] == ['setup']:
        print(json.dumps(setup()))
    elif len(sys.argv) > 3 and sys.argv[1:3] == ['run', '--']:
        fields = setup()
        try:
            env = os.environ.copy()
            env.update(fields)
            result = subprocess.run(sys.argv[3:], env=env)
        finally:
            cleanup(fields['MACOS_SIGNING_DIRECTORY'])
        raise SystemExit(result.returncode)
    elif sys.argv[1:] == ['cleanup']:
        cleanup(os.environ['MACOS_SIGNING_DIRECTORY'])
    else:
        raise SystemExit('Usage: mac-signing.py setup|cleanup|run -- command')
