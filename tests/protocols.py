"""Protocol matrix through Traefik. Requires websockets from requirements-protocols.txt."""
import concurrent.futures
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from http import HTTPStatus

from websockets.exceptions import ConnectionClosed
from websockets.sync.client import connect
from websockets.sync.server import serve

ROOT = Path(__file__).resolve().parents[1]
BINS = Path(os.environ.get('CARGO_TARGET_DIR', ROOT / 'target')).resolve() / 'debug'
IMAGE = os.environ.get('TUNNEL_TEST_IMAGE')
PREFIX = f'tunnel-protocol-test-{os.getpid()}'
PROCESSES = []


def command(*args):
    return subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def start(args, log):
    output = open(log, 'w')
    process = subprocess.Popen(args, stdout=output, stderr=subprocess.STDOUT)
    PROCESSES.append((process, output))
    return process


with tempfile.TemporaryDirectory(prefix='tunnel-protocols-') as directory:
    work = Path(directory)
    (work / 'token').write_text('test-token-' + 'x' * 48)
    (work / 'token').chmod(0o444)
    command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(work / 'ca.key'), '-out', str(work / 'ca.pem'), '-days', '1', '-subj', '/CN=ProtocolTestCA', '-addext', 'basicConstraints=critical,CA:TRUE')
    command('openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(work / 'key.pem'), '-out', str(work / 'leaf.csr'), '-subj', '/CN=localhost')
    (work / 'leaf.ext').write_text('subjectAltName=DNS:localhost\nbasicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\n')
    command('openssl', 'x509', '-req', '-in', str(work / 'leaf.csr'), '-CA', str(work / 'ca.pem'), '-CAkey', str(work / 'ca.key'), '-CAcreateserial', '-out', str(work / 'cert.pem'), '-days', '1', '-extfile', str(work / 'leaf.ext'))
    tls_server = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls_server.load_cert_chain(work / 'cert.pem', work / 'key.pem')
    tls_client = ssl.create_default_context(cafile=str(work / 'ca.pem'))

    def http_request(connection, request):
        if request.headers.get('Upgrade', '').lower() != 'websocket':
            return connection.respond(HTTPStatus.OK, request.path)
        return None

    def echo(connection):
        try:
            connection.send('welcome')
            for message in connection:
                if message == 'close':
                    connection.close(4001, 'local-close')
                    break
                connection.send(message)
        except ConnectionClosed:
            pass

    locals_ = [serve(echo, '127.0.0.1', 18800, process_request=http_request, subprotocols=['chat'], max_size=5*1024*1024),
               serve(echo, '127.0.0.1', 18843, ssl=tls_server, process_request=http_request, subprotocols=['chat'], max_size=5*1024*1024)]
    for local in locals_:
        threading.Thread(target=local.serve_forever, daemon=True).start()
    upstream = f'{PREFIX}-server:8080' if IMAGE else 'host.docker.internal:18090'
    (work / 'dynamic.yaml').write_text(f'''http:
  routers:
    plain:
      rule: Host(`localhost`) || HostRegexp(`^[a-z0-9-]+\\.tunnel\\.test$`)
      entryPoints: [web]
      service: tunnel
    secure:
      rule: Host(`localhost`) || HostRegexp(`^[a-z0-9-]+\\.tunnel\\.test$`)
      entryPoints: [websecure]
      service: tunnel
      tls: {{}}
  services:
    tunnel:
      loadBalancer:
        servers:
          - url: http://{upstream}
tls:
  certificates:
    - certFile: /verify/cert.pem
      keyFile: /verify/key.pem
''')

    def launch(endpoint, target, name, local_ca=True, server_ca=True):
        args = [str(BINS / 'tunnel-client'), '--server', endpoint, '--target', target,
                '--token-file', str(work / 'token'), '--name', name]
        if server_ca:
            args += ['--ca-cert', str(work / 'ca.pem')]
        if local_ca:
            args += ['--local-ca-cert', str(work / 'ca.pem')]
        log = work / f'{name}.log'
        process = start(args, log)
        for _ in range(100):
            text = log.read_text()
            if re.search(r'https://[a-z0-9-]+\.tunnel\.test', text):
                return process, f'{name}.tunnel.test'
            if process.poll() is not None:
                raise RuntimeError(text)
            time.sleep(.1)
        raise RuntimeError(f'No URL assigned: {log.read_text()}')

    def public_http(host, secure=True):
        scheme, port = ('https', 18543) if secure else ('http', 18580)
        result = subprocess.check_output(['curl', '-sS', '--max-time', '10', '--cacert', str(work / 'ca.pem'),
                                         f'{scheme}://localhost:{port}/api?q=1', '-H', f'Host: {host}', '-w', '\n%{http_code}'])
        body, status = result.rsplit(b'\n', 1)
        return int(status), body

    def public_ws(host, secure=True):
        port = 18543 if secure else 18580
        return connect(f'{"wss" if secure else "ws"}://{host}:{port}/chat?q=1',
                       sock=socket.create_connection(('127.0.0.1', port)),
                       **({'ssl': tls_client, 'server_hostname': 'localhost'} if secure else {}),
                       subprotocols=['chat'], open_timeout=10, close_timeout=2, max_size=5*1024*1024, proxy=None)

    def echo_check(host, secure):
        with public_ws(host, secure) as ws:
            assert ws.subprotocol == 'chat'
            assert ws.recv(timeout=5) == 'welcome'
            for value in ['你好 WebSocket', bytes(range(256)) * 32]:
                ws.send(value)
                assert ws.recv(timeout=5) == value
            assert ws.ping().wait(5)
            ws.send('close')
            try:
                ws.recv(timeout=5)
                raise AssertionError('Expected local close')
            except ConnectionClosed as error:
                assert error.rcvd.code == 4001 and error.rcvd.reason == 'local-close', error

    try:
        if IMAGE:
            command('docker', 'network', 'create', PREFIX)
            command('docker', 'run', '--rm', '-d', '--name', PREFIX + '-server', '--network', PREFIX,
                    '-v', str(work / 'token') + ':/run/secrets/token:ro', IMAGE,
                    '--domain', 'tunnel.test', '--connect-domain', 'localhost', '--token-file', '/run/secrets/token')
        else:
            start([str(BINS / 'tunnel-server'), '--domain', 'tunnel.test', '--connect-domain', 'localhost',
                   '--bind', '0.0.0.0:18090', '--token-file', str(work / 'token'), '--database', str(work / 'auth.sqlite3')], work / 'server.log')
        command('docker', 'run', '--rm', '-d', '--name', PREFIX + '-traefik',
                *(['--network', PREFIX] if IMAGE else ['--add-host=host.docker.internal:host-gateway']),
                '-p', '127.0.0.1:18580:80', '-p', '127.0.0.1:18543:443',
                '-v', str(work) + ':/verify:ro', 'traefik:v3.7.14', '--entrypoints.web.address=:80',
                '--entrypoints.websecure.address=:443', '--providers.file.filename=/verify/dynamic.yaml')
        time.sleep(1)
        count = 0
        for endpoint_scheme in ['ws', 'wss', 'http', 'https']:
            port = 18543 if endpoint_scheme in ['wss', 'https'] else 18580
            for target_scheme in ['http', 'https', 'ws', 'wss']:
                local_port = 18843 if target_scheme in ['https', 'wss'] else 18800
                count += 1
                process, host = launch(f'{endpoint_scheme}://localhost:{port}',
                                       f'{target_scheme}://localhost:{local_port}', f'matrix-{count}')
                for secure in [False, True]:
                    assert public_http(host, secure) == (200, b'/api?q=1')
                    echo_check(host, secure)
                process.terminate()
                process.wait(5)
                print(f'{endpoint_scheme} tunnel -> {target_scheme} local: HTTP/HTTPS + WS/WSS PASS', flush=True)
        process, host = launch('wss://localhost:18543', 'https://localhost:18843', 'untrusted', local_ca=False)
        assert public_http(host)[0] == 502
        process.terminate()
        process.wait(5)
        print('Untrusted local HTTPS certificate rejected PASS', flush=True)
        try:
            launch('wss://localhost:18543', 'http://localhost:18800', 'bad-server-cert', server_ca=False)
            raise AssertionError('Untrusted tunnel certificate accepted')
        except RuntimeError as error:
            assert 'certificate' in str(error)
        print('Untrusted tunnel certificate rejected PASS', flush=True)
        process, host = launch('ws://localhost:18580', 'ws://localhost:18800', 'parallel')
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda _: echo_check(host, True), range(8)))
        with public_ws(host) as ws:
            assert ws.recv(timeout=5) == 'welcome'
            data='\0' * (2*1024*1024)
            ws.send(data)
            assert ws.recv(timeout=10) == data
            print('Large text with JSON control characters PASS', flush=True)
            process.terminate()
            process.wait(5)
            try:
                ws.recv(timeout=5)
                raise AssertionError('Expected close after tunnel disconnect')
            except ConnectionClosed:
                pass
        print('Concurrent WebSocket streams and tunnel disconnect cleanup PASS', flush=True)
    finally:
        for process, output in reversed(PROCESSES):
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            output.close()
        for local in locals_:
            local.shutdown()
        subprocess.run(['docker', 'rm', '-f', PREFIX + '-traefik'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if IMAGE:
            subprocess.run(['docker', 'rm', '-f', PREFIX + '-server'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(['docker', 'network', 'rm', PREFIX], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
