"""Public restore protocol on the actual packaged JVM; disposable test data only.

Signed passkey tests model a TLS proxy forwarding canonical Origin and Secure
cookies to the loopback backend. HTTPS/browser interoperability has separate tests.
The authenticator private key is fixture-side only and never enters the archive.
"""
import base64
import hashlib
import json
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
from http.cookies import SimpleCookie

from passkey_replica_acceptance import Authenticator


def verify_public_restore(root, app, fixture, fixture_classpath, environment, *, deployment_origin="NONE"):
    fixture.mkdir(mode=0o700)
    prepared = subprocess.run(['java', '-cp', fixture_classpath,
                              'net.resheim.timekeeper.server.InstanceRestorePublicFixture', str(fixture)],
                             cwd=root, env=environment, capture_output=True, timeout=40)
    if prepared.returncode:
        raise ValueError('Public restore source fixture failed')
    environment = dict(environment, TIMEKEEPER_SERVER_DB_PASSWORD_FILE=str(fixture / 'database-secret'))
    archive = (fixture / 'source.tkbackup').read_bytes()
    source_digest = hashlib.sha256(archive).hexdigest()
    credential = json.loads((fixture / 'authenticator.json').read_text())
    authenticator = Authenticator.__new__(Authenticator)
    authenticator.credential = base64.urlsafe_b64decode(credential['credential'] + '=' * (-len(credential['credential']) % 4))
    authenticator.handle = credential['handle']
    authenticator.rp = credential['rp']
    authenticator.private = fixture / 'authenticator.pem'
    operation = None
    for restoring in (True, False):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        base = 'http://127.0.0.1:' + str(port)
        origin = base if restoring or deployment_origin == 'NONE' else deployment_origin
        cookie = ''
        csrf = ''
        owner = ''
        def api(method, path, data=None, binary=False, token=None):
            nonlocal cookie, csrf, owner
            headers = {'Origin': origin}
            if token:
                headers['Authorization'] = 'Bearer ' + token
            elif cookie:
                headers.update(Cookie=cookie, **{'X-CSRF-Token': csrf, 'X-Timekeeper-Owner': owner})
            if data is not None:
                headers['Content-Type'] = 'application/octet-stream' if binary else 'application/json'
                if not binary:
                    data = json.dumps(data).encode()
            request = urllib.request.Request(base + '/api/v1' + path, data=data, headers=headers, method=method)
            with urllib.request.urlopen(request, timeout=45) as response:
                cookies = SimpleCookie()
                for header in response.headers.get_all('Set-Cookie', []):
                    cookies.load(header)
                if cookies:
                    cookie = '; '.join(key + '=' + value.value for key, value in cookies.items())
                if response.headers.get('X-Timekeeper-Owner'):
                    owner = response.headers['X-Timekeeper-Owner']
                result = response.read()
                if response.headers.get_content_type() != 'application/json':
                    return result
                result = json.loads(result)
                if 'csrf' in result:
                    csrf = result['csrf']
                return result
        command = ['java', '-Xmx384m', '-Dquarkus.http.host=127.0.0.1', '-Dquarkus.http.port=' + str(port),
                   '-Dquarkus.config.locations=' + (fixture / 'destination.properties').as_uri(), '-jar', str(app / 'quarkus-run.jar')]
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen(command, cwd=root, env=environment, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise ValueError('Public restore server exited before readiness')
                    try:
                        with urllib.request.urlopen(base + '/q/health/ready', timeout=2) as response:
                            if response.status == 200:
                                break
                    except (urllib.error.URLError, TimeoutError):
                        pass
                    if time.monotonic() > deadline:
                        raise ValueError('Public restore server did not become ready')
                    time.sleep(0.25)
                api('GET', '/auth/status')
                if restoring:
                    api('POST', '/auth/setup', {'code': (fixture / 'setup-code').read_text(), 'username': 'destination-admin',
                                              'password': 'Disposable public destination password', 'displayName': 'Destination administrator', 'email': 'destination@example.org'})
                    operation = api('POST', '/admin/instance/uploads', {'currentPassword': 'Disposable public destination password',
                                   'bytes': len(archive), 'sha256': source_digest})['operation']['id']
                    path = '/admin/instance/uploads/' + operation
                    for offset in range(0, len(archive), 1048576):
                        api('PUT', path + '/content?offset=' + str(offset), archive[offset:offset + 1048576], binary=True)
                    api('POST', path + '/validate', {'currentPassword': 'Disposable public destination password', 'passphrase': 'Public fixture archive passphrase'})
                    deadline = time.monotonic() + 60
                    while api('GET', path)['operation']['state'] != 'REVIEW':
                        if time.monotonic() > deadline:
                            raise ValueError('Public archive did not reach review')
                        time.sleep(0.1)
                    stage = api('GET', path + '/staging')
                    api('POST', path + '/staging', {'currentPassword': 'Disposable public destination password',
                        'passphrase': 'Public fixture archive passphrase', 'operationVersion': stage['operationVersion'],
                        'publicOrigin': deployment_origin, 'passkeyRpId': 'AUTO', 'passkeysEnabled': deployment_origin != 'NONE'})
                    deadline = time.monotonic() + 60
                    while True:
                        stage = api('GET', path + '/staging')
                        if stage['phase'] == 'READY':
                            break
                        if stage['phase'] == 'FAILED' or time.monotonic() > deadline:
                            raise ValueError('Public restore preparation did not finish')
                        time.sleep(0.1)
                    if stage['target']['packageId'] != 'dagsverk':
                        raise ValueError('Destination package identity was not taken from its actual configuration')
                    confirmed = api('POST', path + '/confirm', {'currentPassword': 'Disposable public destination password',
                        'operationVersion': stage['operationVersion'], 'generation': stage['generation'], 'confirmation': 'RESTORE dagsverk'})
                    if confirmed['phase'] != 'PENDING_RESTART':
                        raise ValueError('Public confirmation did not commit maintenance consent')
                    try:
                        api('GET', '/clients')
                        raise ValueError('Pending restore did not fence ordinary API access')
                    except urllib.error.HTTPError as blocked:
                        if blocked.code != 503:
                            raise
                else:
                    api('POST', '/auth/login', {'username': 'user-a', 'password': 'Preserved archive account password', 'remember': False})
                    clients = api('GET', '/clients')['items']
                    if len(clients) != 1 or clients[0]['id'] != '11111111-1111-4111-8111-111111111111' or clients[0]['version'] != 17:
                        raise ValueError('Public restore did not preserve source client identity/version')
                    label = api('GET', '/day-labels/2026-10-27')
                    note = api('GET', '/day-notes/2026-10-27/content')
                    if label['text'] != 'Restored label' or label['version'] != 3 or note['body'] != '# Restored journal\n' or note['version'] != 5:
                        raise ValueError('Public restore did not preserve separate journal and label data')
                    week = api('GET', '/week-notes/2026-W44')
                    if week['body'] != '# Restored week\n' or week['version'] != 4:
                        raise ValueError('Public restore did not preserve weekly journal data')
                    if api('GET', '/clients', token='tkp_disposable_public_restore_acceptance_token')['items'][0]['id'] != clients[0]['id']:
                        raise ValueError('Restored API token did not retain its access and owner binding')
                    if api('GET', '/journal-assets/restored.txt') != 'Restored attachment bytes æ\n'.encode():
                        raise ValueError('Public restore did not preserve exact journal attachment bytes')
                    if deployment_origin != 'NONE':
                        keys = api('GET', '/auth/passkeys')['items']
                        if (len(keys) != 1 or keys[0]['rpId'] != authenticator.rp
                                or keys[0]['id'] != '44444444-4444-4444-4444-444444444444'
                                or keys[0]['version'] != 6
                                or keys[0]['usableHere'] != (deployment_origin == 'https://source.example')):
                            raise ValueError('Restore changed the original passkey RP binding')
                        original_owner = owner
                        # A fresh browser binding is required for each sign-in;
                        # replaying an imported or bootstrap session is forbidden.
                        cookie = csrf = owner = ''
                        api('GET', '/auth/status')
                        options = api('POST', '/auth/passkeys/login/options', {'remember': False})
                        assertion = authenticator.assertion(options, origin)
                        if deployment_origin == 'https://source.example':
                            api('POST', '/auth/passkeys/login/finish', assertion)
                            if owner != original_owner:
                                raise ValueError('Same-RP restored passkey changed account ownership')
                            api('GET', '/clients')
                        else:
                            try:
                                api('POST', '/auth/passkeys/login/finish', assertion)
                                raise ValueError('Changed-RP restore accepted the original RP credential')
                            except urllib.error.HTTPError as rejected:
                                if rejected.code != 401:
                                    raise
                            api('POST', '/auth/login', {'username': 'user-a', 'password': 'Preserved archive account password', 'remember': False})
                            replacement = Authenticator(fixture)
                            options = api('POST', '/auth/passkeys/register/options', {'name': 'Re-enrolled destination credential', 'currentPassword': 'Preserved archive account password'})
                            api('POST', '/auth/passkeys/register/finish', replacement.registration(options, origin))
                            cookie = csrf = owner = ''
                            api('GET', '/auth/status')
                            options = api('POST', '/auth/passkeys/login/options', {'remember': False})
                            api('POST', '/auth/passkeys/login/finish', replacement.assertion(options, origin))
                            if owner != original_owner:
                                raise ValueError('Re-enrolled destination passkey changed account ownership')
                            api('GET', '/clients')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    if hashlib.sha256((fixture / 'source.tkbackup').read_bytes()).hexdigest() != source_digest:
        raise ValueError('Public restoration modified the original encrypted source')
