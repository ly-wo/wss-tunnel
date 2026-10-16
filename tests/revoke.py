"""Account disable interrupts real clients, active WebSockets and pending HTTP.

Requires tests/requirements-protocols.txt. Runs entirely on loopback.
"""
import concurrent.futures
import json
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from contextlib import ExitStack
from http import HTTPStatus
from pathlib import Path

from websockets.exceptions import ConnectionClosed
from websockets.sync.client import connect
from websockets.sync.server import serve
from accounts import BINS, ENV, PASSWORD, port, wait_for


def main():
    slow_started = threading.Event()
    release_slow = threading.Event()
    local_closed = {name: threading.Event() for name in ['token', 'password']}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def http_request(connection, request):
        if request.path == '/slow':
            slow_started.set()
            release_slow.wait(10)
            return connection.respond(HTTPStatus.OK, 'completed')
        if request.headers.get('Upgrade', '').lower() != 'websocket':
            return connection.respond(HTTPStatus.OK, 'ok')

    def echo(connection):
        try:
            for message in connection:
                connection.send(message)
        except ConnectionClosed:
            pass
        finally:
            name = connection.request.path.strip('/')
            if name in local_closed:
                local_closed[name].set()

    with tempfile.TemporaryDirectory(prefix='tunnel-revoke-') as directory, ExitStack() as stack:
        work = Path(directory)
        password = work / 'password'
        password.write_text(PASSWORD)
        server_port = port()
        processes = []
        local = serve(echo, '127.0.0.1', 0, process_request=http_request)
        local_port = local.socket.getsockname()[1]
        threading.Thread(target=local.serve_forever, daemon=True).start()

        def start(args):
            log = work / f'process-{len(processes)}.log'
            output = log.open('w')
            process = subprocess.Popen(args, cwd=work, env=ENV, stdout=output, stderr=subprocess.STDOUT)
            processes.append((process, output))
            return process, log

        def request(host, path, body=None, session=None, method=None, expected=200):
            headers = {'Host': host}
            if body is not None:
                headers['Content-Type'] = 'application/json'
            if session:
                headers['Authorization'] = f'Bearer {session}'
            req = urllib.request.Request(f'http://127.0.0.1:{server_port}{path}', headers=headers,
                                         data=None if body is None else json.dumps(body).encode(), method=method)
            try:
                response = opener.open(req, timeout=5)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                raw = response.read()
                assert response.status == expected, (path, response.status, raw)
                return json.loads(raw) if raw and response.headers.get('Content-Type', '').startswith('application/json') else raw

        def api(path, **kwargs):
            return request('localhost', '/_tunnel/api/' + path, **kwargs)

        def login(name):
            return api('login', body={'username': name, 'password': PASSWORD})['session']

        def client(name, username=None, token=None):
            args = [str(BINS / 'tunnel-client'), str(local_port), '--server',
                    f'ws://localhost:{server_port}', '--name', name]
            if token:
                tokenfile = work / f'token-{name}'
                tokenfile.write_text(token)
                args += ['--token-file', str(tokenfile)]
            else:
                args += ['--username', username, '--password-file', str(password), '--allow-insecure-auth']
            process, log = start(args)
            wait_for(lambda: 'https://' in log.read_text() or process.poll() is not None)
            assert process.poll() is None, log.read_text()
            return process

        def websocket(name, path):
            ws = stack.enter_context(connect(f'ws://{name}.tunnel.test:{server_port}/{path}',
                                             sock=socket.create_connection(('127.0.0.1', server_port)),
                                             proxy=None, close_timeout=1))
            ws.send('before disable')
            assert ws.recv(timeout=2) == 'before disable'
            return ws

        base = [str(BINS / 'tunnel-server'), '--domain', 'tunnel.test', '--connect-domain', 'localhost',
                '--bind', f'127.0.0.1:{server_port}', '--database', str(work / 'auth.sqlite3')]
        try:
            subprocess.run(base + ['--bootstrap-admin', 'admin', '--bootstrap-password-file', str(password),
                                   '--bootstrap-only'], cwd=work, env=ENV, check=True, capture_output=True)
            server, log = start(base)
            wait_for(lambda: 'Listening on' in log.read_text())
            admin = login('admin')
            for name in ['alice', 'bobby']:
                api('register', body={'username': name, 'password': PASSWORD}, expected=201)
            alice = login('alice')
            token = api('token', body={}, session=alice)['token']
            uid = next(u['id'] for u in api('admin/users', session=admin)['users'] if u['username'] == 'alice')
            token_client = client('alice-token', token=token)
            password_client = client('alice-password', username='alice')
            other = client('other', username='bobby')
            sockets = [websocket('alice-token', 'token'), websocket('alice-password', 'password')]
            unaffected = websocket('other', 'other')
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                pending = pool.submit(request, 'alice-token.tunnel.test', '/slow', expected=502)
                assert slow_started.wait(3)
                started = time.monotonic()
                api(f'admin/users/{uid}', body={'disabled': True, 'domain_limit_override': None},
                    session=admin, method='PUT', expected=204)
                # A quick re-enable must never resurrect the revoked connections.
                api(f'admin/users/{uid}', body={'disabled': False, 'domain_limit_override': None},
                    session=admin, method='PUT', expected=204)
                deadline = started + 2
                for ws in sockets:
                    try:
                        ws.recv(timeout=max(.01, deadline - time.monotonic()))
                        raise AssertionError('Revoked WebSocket stayed open')
                    except ConnectionClosed:
                        pass
                pending.result(timeout=max(.01, deadline - time.monotonic()))
                wait_for(lambda: token_client.poll() is not None and password_client.poll() is not None
                         and all(event.is_set() for event in local_closed.values()),
                         timeout=max(.01, deadline - time.monotonic()))
                assert time.monotonic() < deadline, 'Disable waited for heartbeat instead of immediate cancellation'
            assert other.poll() is None
            unaffected.send('still connected')
            assert unaffected.recv(timeout=2) == 'still connected'
            request('other.tunnel.test', '/', expected=200)
            for name in ['alice-token', 'alice-password']:
                request(f'{name}.tunnel.test', '/', expected=404)
            api('me', session=alice, expected=401)
            events = api('admin/audit?username=alice&action=tunnel_disconnect', session=admin)['events']
            assert len([event for event in events if event['outcome'] == 'revoked']) == 2, events
            client('alice-token', token=token)
            request('alice-token.tunnel.test', '/', expected=200)
            print('PASS: disable closes password/token tunnels, public/local WebSockets and pending HTTP within 2s; other account unaffected; re-enable requires a new connection', flush=True)
        finally:
            release_slow.set()
            stack.close()
            for process, output in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                output.close()
            local.shutdown()


if __name__ == '__main__':
    main()
