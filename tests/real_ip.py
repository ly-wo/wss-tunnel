"""Real HTTPS/WSS Traefik -> server -> client -> HTTP/WS app IP propagation.

Uses Docker and tests/requirements-protocols.txt. No production configuration.
"""
import ipaddress
import json
import socket
import ssl
import sqlite3
import subprocess
import tempfile
import threading
import urllib.request
import uuid
from http import HTTPStatus
from pathlib import Path

from websockets.sync.client import connect
from websockets.sync.server import serve
from accounts import BINS, ENV, PASSWORD, port, wait_for


def main():
    name = 'tunnel-ip-test-' + uuid.uuid4().hex[:12]
    processes = []
    headers = {
        'X-Forwarded-For': '192.0.2.99',
        'X-Real-IP': '192.0.2.98',
        'Forwarded': 'for=192.0.2.97',
    }

    def echoed(request):
        return json.dumps({key: request.headers.get_all(key) for key in
                           ['x-forwarded-for', 'x-real-ip', 'forwarded']})

    def http_request(connection, request):
        if request.headers.get('Upgrade', '').lower() != 'websocket':
            return connection.respond(HTTPStatus.OK, echoed(request))

    def websocket(connection):
        connection.send(echoed(connection.request))

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    with tempfile.TemporaryDirectory(prefix='tunnel-real-ip-') as directory:
        work = Path(directory)
        database = work / 'auth.sqlite3'
        token = work / 'token'
        token.write_text('real-ip-test-' + 'x' * 48)
        def openssl(*args):
            subprocess.run(['openssl', *args], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                '-keyout', str(work / 'ca.key'), '-out', str(work / 'ca.pem'),
                '-days', '1', '-subj', '/CN=RealIpTestCA',
                '-addext', 'basicConstraints=critical,CA:TRUE')
        openssl('req', '-new', '-newkey', 'rsa:2048', '-nodes',
                '-keyout', str(work / 'key.pem'), '-out', str(work / 'leaf.csr'),
                '-subj', '/CN=localhost')
        (work / 'leaf.ext').write_text('subjectAltName=DNS:localhost\nbasicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\n')
        openssl('x509', '-req', '-in', str(work / 'leaf.csr'), '-CA', str(work / 'ca.pem'),
                '-CAkey', str(work / 'ca.key'), '-CAcreateserial', '-out', str(work / 'cert.pem'),
                '-days', '1', '-extfile', str(work / 'leaf.ext'))
        tls = ssl.create_default_context(cafile=str(work / 'ca.pem'))
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                             urllib.request.HTTPSHandler(context=tls))
        server_port, proxy_port = port(), port()
        local = serve(websocket, '127.0.0.1', 0, process_request=http_request)
        local_port = local.socket.getsockname()[1]
        threading.Thread(target=local.serve_forever, daemon=True).start()
        config = work / 'dynamic.yaml'
        config.write_text(f'''http:
  routers:
    all:
      rule: Host(`localhost`) || Host(`real-ip.tunnel.test`)
      service: tunnel
      tls: {{}}
  services:
    tunnel:
      loadBalancer:
        servers:
          - url: http://host.docker.internal:{server_port}
tls:
  certificates:
    - certFile: /verify/cert.pem
      keyFile: /verify/key.pem
''')

        def start(args):
            log = work / f'process-{len(processes)}.log'
            output = log.open('w')
            process = subprocess.Popen(args, cwd=work, env=ENV,
                                       stdout=output, stderr=subprocess.STDOUT)
            processes.append((process, output))
            return process, log

        def request(host, path='/', body=None):
            req = urllib.request.Request(
                f'https://localhost:{proxy_port}{path}',
                headers={**headers, 'Host': host, 'Content-Type': 'application/json'},
                data=None if body is None else json.dumps(body).encode())
            with opener.open(req, timeout=15) as response:
                return json.load(response)

        def check_headers(result, expected):
            assert result['x-forwarded-for'] == [expected], result
            assert result['x-real-ip'] == [expected], result
            value = f'for="[{expected}]"' if ':' in expected else f'for={expected}'
            assert result['forwarded'] == [value], result

        try:
            subprocess.run([
                'docker', 'run', '--rm', '-d', '--name', name,
                '--add-host=host.docker.internal:host-gateway',
                '-p', f'127.0.0.1:{proxy_port}:8080',
                '-v', f'{work}:/verify:ro', 'traefik:v3.7.14',
                '--entrypoints.web.address=:8080',
                '--providers.file.filename=/verify/dynamic.yaml',
            ], check=True, stdout=subprocess.DEVNULL)

            def proxy_ready():
                try:
                    # A 502 confirms the router is ready before starting its backend.
                    request('localhost')
                    return True
                except urllib.error.HTTPError as error:
                    return error.code == 502
                except OSError:
                    return False

            wait_for(proxy_ready)
            trusted_peer = None
            for trusted in [False, True]:
                args = [str(BINS / 'tunnel-server'), '--domain', 'tunnel.test',
                        '--connect-domain', 'localhost', '--bind', f'0.0.0.0:{server_port}',
                        '--token-file', str(token), '--database', str(database)]
                if trusted:
                    mask = 128 if ':' in trusted_peer else 32
                    args += ['--trusted-proxies', f'{trusted_peer}/{mask}']
                server, log = start(args)
                wait_for(lambda: 'Listening on' in log.read_text() or server.poll() is not None)
                assert server.poll() is None, log.read_text()
                username = 'trusted' if trusted else 'direct'
                request('localhost', '/_tunnel/api/register',
                        {'username': username, 'password': PASSWORD})
                with sqlite3.connect(database) as db:
                    expected, details = db.execute(
                        "SELECT client_ip,details FROM audit WHERE username=? AND action='register'",
                        (username,)).fetchone()
                    registered = db.execute('SELECT registered_ip FROM users WHERE username=?',
                                            (username,)).fetchone()[0]
                details = json.loads(details)
                ipaddress.ip_address(expected)
                assert expected not in ['192.0.2.99', '192.0.2.98', '192.0.2.97']
                assert registered == expected
                assert details['ip_source'] == ('trusted_proxy' if trusted else 'direct'), details
                if trusted:
                    assert details['peer_ip'] == trusted_peer
                else:
                    trusted_peer = details['peer_ip']
                    assert expected == trusted_peer
                client, client_log = start([
                    str(BINS / 'tunnel-client'), str(local_port),
                    '--server', f'wss://localhost:{proxy_port}',
                    '--ca-cert', str(work / 'ca.pem'),
                    '--token-file', str(token), '--name', 'real-ip',
                ])
                wait_for(lambda: 'https://' in client_log.read_text() or client.poll() is not None)
                assert client.poll() is None, client_log.read_text()
                check_headers(request('real-ip.tunnel.test'), expected)
                with connect(f'wss://real-ip.tunnel.test:{proxy_port}/',
                             sock=socket.create_connection(('127.0.0.1', proxy_port)),
                             ssl=tls, server_hostname='localhost',
                             additional_headers=headers, proxy=None, open_timeout=10) as ws:
                    check_headers(json.loads(ws.recv(timeout=5)), expected)
                stop(client)
                stop(server)
                print(f'Traefik HTTPS/WSS trusted={trusted}: user/audit IP = HTTP IP = WebSocket IP; spoofed headers rejected PASS', flush=True)
        finally:
            for process, output in reversed(processes):
                stop(process)
                output.close()
            local.shutdown()
            subprocess.run(['docker', 'rm', '-f', name],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
