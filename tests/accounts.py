"""Account, domain ownership, token rotation and audit integration checks.
Runs real server/client binaries on loopback; no third-party Python packages.
"""
import concurrent.futures
import http.server
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import uuid
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BINS = Path(os.environ.get('CARGO_TARGET_DIR', ROOT / 'target')).resolve() / 'debug'
PASSWORD = 'correct-horse-test-password'
ENV = {k: v for k, v in os.environ.items() if not k.startswith('TUNNEL_') and k != 'CONNECT_DOMAIN'}


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait_for(predicate, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.1)
    raise AssertionError('Timed out waiting for expected state')


class Local(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'account tunnel works')


def main():
    with tempfile.TemporaryDirectory(prefix='tunnel-accounts-') as directory:
        work = Path(directory)
        database = work / 'accounts.sqlite3'
        http_port = port()
        local = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Local)
        threading.Thread(target=local.serve_forever, daemon=True).start()
        processes, handles = [], []
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        sequence = 0

        def start(args):
            nonlocal sequence
            sequence += 1
            path = work / f'process-{uuid.uuid4().hex}.log'
            output = path.open('w')
            process = subprocess.Popen(args, stdout=output, stderr=subprocess.STDOUT, cwd=work, env=ENV)
            processes.append(process)
            handles.append(output)
            return process, path

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

        def request(path, method='GET', body=None, session=None, host='localhost', expected=200):
            headers = {'Host': host}
            if session:
                headers['Authorization'] = 'Bearer ' + session
            if body is not None:
                headers['Content-Type'] = 'application/json'
            req = urllib.request.Request(f'http://127.0.0.1:{http_port}{path}',
                                         method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
            try:
                response = opener.open(req, timeout=15)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                data = response.read()
                assert response.status == expected, (method, path, response.status, data)
                if response.headers.get('Content-Type', '').startswith('application/json'):
                    return json.loads(data)
                return data

        def api(path, method='GET', body=None, session=None, expected=200):
            return request('/_tunnel/api/' + path, method, body, session, expected=expected)

        def server(registration=True):
            process, log = start([str(BINS / 'tunnel-server'), '--domain', 'tunnel.test',
                                  '--connect-domain', 'localhost', '--bind', f'127.0.0.1:{http_port}',
                                  '--database', str(database), '--allow-registration', str(registration).lower()])
            wait_for(lambda: 'Listening on' in log.read_text() or process.poll() is not None)
            assert process.poll() is None, log.read_text()
            return process

        def login(name, password=PASSWORD, expected=200):
            return api('login', 'POST', {'username': name, 'password': password}, expected=expected)

        def client(name, username=None, token=None, expected_error=None, allow=True):
            args = [str(BINS / 'tunnel-client'), str(local.server_port), '--server', f'ws://localhost:{http_port}', '--name', name]
            if username:
                args += ['--username', username, '--password-file', str(work / 'password')]
                if allow:
                    args += ['--allow-insecure-auth']
            else:
                file = work / f'token-{len(processes)}'
                file.write_text(token)
                args += ['--token-file', str(file)]
            process, log = start(args)
            if expected_error:
                process.wait(timeout=15)
                assert process.returncode and expected_error in log.read_text(), log.read_text()
            else:
                wait_for(lambda: 'https://' in log.read_text() or process.poll() is not None)
                assert process.poll() is None, log.read_text()
            return process

        (work / 'password').write_text(PASSWORD + '\n')
        try:
            srv = server()
            assert b'WSS TUNNEL' in request('/')
            assert b'localStorage' not in request('/_tunnel/account.js')
            api('me', expected=401)
            api('register', 'POST', {'username': 'alice', 'password': 'short'}, expected=400)
            for user in ['alice', 'bob']:
                api('register', 'POST', {'username': user, 'password': PASSWORD}, expected=201)
            api('register', 'POST', {'username': 'ALICE', 'password': PASSWORD}, expected=409)
            login('alice', 'wrong password', expected=401)
            alice, bob = login('alice')['session'], login('bob')['session']
            assert api('me', session=alice)['token_prefix'] is None
            token1 = api('token', 'POST', {}, alice)['token']
            token_bob = api('token', 'POST', {}, bob)['token']
            api('token', 'POST', {}, token1, expected=401)
            print('PASS: registration, password login, session/connection-token scope separation', flush=True)

            basic = client('password-demo', username='alice')
            assert request('/', host='password-demo.tunnel.test') == b'account tunnel works'
            client('insecure', username='alice', expected_error='require WSS', allow=False)
            api('domains/password-demo', 'DELETE', session=alice, expected=409)
            stop(basic)
            wait_for(lambda: all(d['status'] == 'offline' for d in api('domains', session=alice)['domains']))
            client('password-demo', token=token_bob, expected_error='409')
            api('domains/password-demo', 'DELETE', session=bob, expected=404)
            assert not api('domains', session=bob)['domains']
            print('PASS: actual password connection, plaintext guard, offline ownership and cross-account protection', flush=True)

            token_client = client('token-demo', token=token1)
            token2 = api('token', 'POST', {}, alice)['token']
            assert token1 != token2
            client('old-token', token=token1, expected_error='401')
            wait_for(lambda: token_client.poll() is not None, timeout=35)
            client('token-demo', token=token2)
            print('PASS: single-token rotation rejects old token and disconnects its active tunnel', flush=True)

            # Eight additional reservations bring alice to ten including offline names.
            for index in range(8):
                p = client(f'owned-{index}', token=token2)
                stop(p)
            domains = api('domains', session=alice)['domains']
            assert len(domains) == 10
            client('eleventh', token=token2, expected_error='409')
            # Existing offline reservations can still reconnect at capacity.
            p = client('owned-0', token=token2)
            stop(p)
            wait_for(lambda: next(d for d in api('domains', session=alice)['domains'] if d['name']=='owned-0')['status']=='offline')
            api('domains/owned-0', 'DELETE', session=alice, expected=204)
            p = client('owned-0', token=token_bob)
            stop(p)
            # Two simultaneous reservations competing for alice's final slot.
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                def attempt(name):
                    try:
                        return client(name, token=token2)
                    except AssertionError:
                        return None
                results = list(pool.map(attempt, ['race-one', 'race-two']))
            assert sum(p is not None for p in results) == 1
            assert len(api('domains', session=alice)['domains']) == 10
            print('PASS: ten-domain quota, reconnect at capacity, deletion frees quota, concurrent reservation', flush=True)

            records = api('audit', session=alice)['events']
            actions = {r['action'] for r in records}
            assert {'register', 'login', 'token_rotate', 'tunnel_connect', 'tunnel_disconnect', 'domain_delete', 'tunnel_rejected'} <= actions
            assert any(r['action']=='login' and r['outcome']=='failure' for r in records)
            assert all(r['domain'] not in ['token-demo', 'password-demo'] for r in api('audit', session=bob)['events'] if r['action']=='tunnel_connect')
            for hidden in [PASSWORD, token1, token2, alice]:
                assert hidden not in json.dumps(records)
            with sqlite3.connect(database) as db:
                rows = db.execute('SELECT username,password_hash,token_hash FROM users').fetchall()
                assert len(rows)==2 and all(row[1].startswith('$argon2id$') and len(row[2])==32 for row in rows)
                db.execute("INSERT INTO audit(user_id,username,action,outcome,created_at) VALUES (1,'alice','old','success',0)")
            print('PASS: user-isolated audit, login failures, no password/token plaintext in stored credentials or audit', flush=True)

            # Restart without changing the data directory: identities, token and names persist; liveness resets.
            for p in processes:
                stop(p)
            srv = server(False)
            # Persisted settings take priority over the initial environment seed.
            assert api('config')['registration']
            alice = login('alice')['session']
            assert len(api('domains', session=alice)['domains'])==10
            assert all(d['status']=='offline' for d in api('domains', session=alice)['domains'])
            client('password-demo', token=token_bob, expected_error='409')
            client('token-demo', token=token2)
            with sqlite3.connect(database) as db:
                assert db.execute("SELECT COUNT(*) FROM audit WHERE action='old'").fetchone()[0]==0
            api('logout', 'POST', {}, alice, expected=204)
            api('me', session=alice, expected=401)
            # Malformed account login attempts are bounded without expensive password hashing.
            for _ in range(59):
                login('!', expected=401)
            login('!', expected=429)
            print('PASS: restart persistence, offline status, persisted registration setting, audit retention, logout, login rate limit', flush=True)
        finally:
            for process in processes:
                stop(process)
            for output in handles:
                output.close()
            local.shutdown()
            local.server_close()


if __name__ == '__main__':
    main()
