"""Real server/client checks for administrator settings, audit and proxy trust."""
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import urllib.error
import urllib.request
from accounts import BINS, ENV, PASSWORD, port, wait_for


def main():
    ENV['TUNNEL_TRUSTED_PROXIES'] = ''  # Compose's default empty environment must be accepted.
    with tempfile.TemporaryDirectory(prefix='tunnel-admin-') as directory:
        work = Path(directory)
        dbfile = work / 'auth.sqlite3'
        password = work / 'password'
        password.write_text(PASSWORD)
        # A pre-management database must migrate without losing existing audit rows.
        with sqlite3.connect(dbfile) as db:
            db.executescript("""CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, token_hash BLOB UNIQUE, token_prefix TEXT, token_created_at INTEGER, created_at INTEGER NOT NULL);
            CREATE TABLE audit(id INTEGER PRIMARY KEY,user_id INTEGER REFERENCES users(id),username TEXT NOT NULL,action TEXT NOT NULL,outcome TEXT NOT NULL,domain TEXT,created_at INTEGER NOT NULL);
            INSERT INTO audit(username,action,outcome,created_at) VALUES ('historic','migration_fixture','success',strftime('%s','now'));""")
        http_port = port()
        base = [str(BINS / 'tunnel-server'), '--domain', 'tunnel.test', '--connect-domain', 'localhost', '--database', str(dbfile), '--bind', f'127.0.0.1:{http_port}']
        bootstrap = base + ['--bootstrap-admin', 'admin', '--bootstrap-password-file', str(password), '--bootstrap-only']
        result = subprocess.run(bootstrap, cwd=work, env=ENV, capture_output=True)
        assert result.returncode == 0, result.stderr.decode()
        assert subprocess.run(bootstrap, cwd=work, env=ENV, capture_output=True).returncode != 0
        processes, handles = [], []
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def start(args):
            log = work / f'process-{len(processes)}.log'
            handle = log.open('w')
            p = subprocess.Popen(args, cwd=work, env=ENV, stdout=handle, stderr=subprocess.STDOUT)
            processes.append(p)
            handles.append(handle)
            return p, log

        def stop(p):
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    p.kill()
                    p.wait()

        def server(trusted=False):
            p, log = start(base + (['--trusted-proxies', '127.0.0.1/32'] if trusted else []))
            wait_for(lambda: 'Listening on' in log.read_text() or p.poll() is not None)
            assert p.poll() is None, log.read_text()
            return p

        def api(path, method='GET', body=None, session=None, expected=200, forwarded='198.51.100.23'):
            headers = {'Host': 'localhost', 'User-Agent': 'AdminRegression/1.0', 'X-Forwarded-For': forwarded}
            if session:
                headers['Authorization'] = f'Bearer {session}'
            if body is not None:
                headers['Content-Type'] = 'application/json'
            req = urllib.request.Request(f'http://127.0.0.1:{http_port}/_tunnel/api/{path}', method=method, headers=headers, data=None if body is None else json.dumps(body).encode())
            try:
                response = opener.open(req, timeout=15)
            except urllib.error.HTTPError as e:
                response = e
            with response:
                raw = response.read()
                assert response.status == expected, (path, response.status, raw)
                return json.loads(raw) if raw else None

        def login(name, expected=200):
            return api('login', 'POST', {'username': name, 'password': PASSWORD}, expected=expected)

        def settings(session, **changes):
            value = api('admin/settings', session=session)['settings']
            value.update(changes)
            return api('admin/settings', 'PUT', value, session)['settings']

        def client(name, token=None, username=None, rejected=None):
            args = [str(BINS / 'tunnel-client'), '3000', '--server', f'ws://localhost:{http_port}', '--name', name]
            if token:
                tokenfile = work / f'token-{len(processes)}'
                tokenfile.write_text(token)
                args += ['--token-file', str(tokenfile)]
            else:
                args += ['--username', username, '--password-file', str(password), '--allow-insecure-auth']
            p, log = start(args)
            if rejected:
                p.wait(timeout=15)
                assert p.returncode and rejected in log.read_text(), log.read_text()
            else:
                wait_for(lambda: 'https://' in log.read_text() or p.poll() is not None)
                assert p.poll() is None, log.read_text()
            return p

        try:
            srv = server()
            admin = login('admin')['session']
            api('register', 'POST', {'username': 'alice', 'password': PASSWORD}, expected=201)
            alice = login('alice')['session']
            token = api('token', 'POST', {}, alice)['token']
            assert api('me', session=admin)['is_admin']
            assert not api('me', session=alice)['is_admin']
            for path in ['admin/settings', 'admin/users', 'admin/audit', 'admin/users/1/domains']:
                api(path, session=alice, expected=403)
                api(path, session=token, expected=401)
                api(path, expected=401)
            api('admin/users/1', 'PUT', {'disabled': True, 'domain_limit_override': None}, admin, expected=409)
            user = next(u for u in api('admin/users', session=admin)['users'] if u['username'] == 'alice')
            uid = user['id']
            assert user['registered_ip'] == user['last_login_ip'] == '127.0.0.1'
            assert user['user_agent'] == 'AdminRegression/1.0'
            assert api('admin/audit?action=migration_fixture', session=admin)['events']
            assert api('admin/settings', session=admin)['settings']['default_domain_limit'] == 10
            print('PASS: old database migration, explicit bootstrap, admin isolation and untrusted IP spoof protection', flush=True)

            settings(admin, default_domain_limit=2, registration_enabled=False, audit_retention_days=30)
            api('register', 'POST', {'username': 'blocked', 'password': PASSWORD}, expected=403)
            assert api('me', session=alice)['domain_limit'] == 2
            assert api('audit', session=alice)['retention_days'] == 30
            for name in ['first', 'second']:
                stop(client(name, token=token))
            client('third', token=token, rejected='409')
            settings(admin, default_domain_limit=1)
            stop(client('first', token=token))
            assert len(api('domains', session=alice)['domains']) == 2
            api(f'admin/users/{uid}', 'PUT', {'disabled': False, 'domain_limit_override': 3}, admin, expected=204)
            stop(client('third', token=token))
            assert len(api(f'admin/users/{uid}/domains', session=admin)['domains']) == 3
            client('fourth', token=token, rejected='409')
            api(f'admin/users/{uid}', 'PUT', {'disabled': False, 'domain_limit_override': None}, admin, expected=204)
            assert api('me', session=alice)['domain_limit'] == 1
            bad = api('admin/settings', session=admin)['settings']
            bad['default_domain_limit'] = -1
            api('admin/settings', 'PUT', bad, admin, expected=400)
            assert api('admin/settings', session=admin)['settings']['default_domain_limit'] == 1
            print('PASS: live default/personal quotas, offline reservations, lower-limit reconnect and validation', flush=True)

            live = client('first', token=token)
            password_live = client('second', username='alice')
            api(f'admin/users/{uid}', 'PUT', {'disabled': True, 'domain_limit_override': None}, admin, expected=204)
            wait_for(lambda: live.poll() is not None and password_live.poll() is not None, timeout=2)
            assert all(d['status'] == 'offline' for d in api(f'admin/users/{uid}/domains', session=admin)['domains'])
            api('me', session=alice, expected=401)
            login('alice', expected=401)
            client('second', token=token, rejected='401')
            client('second', username='alice', rejected='401')
            api(f'admin/users/{uid}', 'PUT', {'disabled': False, 'domain_limit_override': None}, admin, expected=204)
            api('me', session=alice, expected=401)  # old sessions remain revoked
            stop(srv)
            srv = server(trusted=True)
            assert not api('config')['registration']
            assert api('admin/settings', session=admin)['settings']['default_domain_limit'] == 1
            alice = login('alice')['session']
            user = next(u for u in api('admin/users', session=admin)['users'] if u['username'] == 'alice')
            assert user['last_login_ip'] == '198.51.100.23'
            assert user['registered_ip'] == '127.0.0.1'
            print('PASS: disabled account revokes sessions, password/token authentication and live tunnels; settings survive restart', flush=True)

            # Trust the nearest untrusted hop, never an arbitrary left-most forwarded value.
            api('me', session=alice, forwarded='192.0.2.66, 198.51.100.24')
            api('me', session=alice, forwarded='invalid, 198.51.100.24')
            api('me?secret=query-must-not-be-logged', session=alice)
            events = api('admin/audit?username=alice&ip=198.51.100.24&action=request', session=admin)['events']
            assert events and all(e['request']['client_ip'] == '198.51.100.24' for e in events)
            assert events[0]['request']['peer_ip'] == '127.0.0.1'
            assert events[0]['request']['ip_source'] == 'trusted_proxy'
            assert isinstance(events[0]['duration_ms'], int) and events[0]['status'] == 200
            changes = api('admin/audit?action=settings_update', session=admin)['events']
            assert changes[0]['data']['before']['default_domain_limit'] == 2
            assert changes[0]['data']['after']['default_domain_limit'] == 1
            page = api('admin/audit', session=admin)
            next_page = api(f"admin/audit?before={page['next_cursor']}", session=admin)
            assert all(e['id'] < page['next_cursor'] for e in next_page['events'])
            api('login', 'POST', {'username': 'missing', 'password': PASSWORD}, expected=401)
            failures = api('admin/audit?action=request&outcome=failure', session=admin)['events']
            assert failures[0]['data']['reason'] == 'Invalid or expired credentials'
            with sqlite3.connect(dbfile) as db:
                raw = repr(db.execute('SELECT * FROM audit').fetchall())
                for secret in [PASSWORD, token, alice, 'query-must-not-be-logged']:
                    assert secret not in raw
                db.execute("INSERT INTO audit(username,action,outcome,created_at) VALUES('old','expired','success',0)")
            settings(admin, audit_retention_days=1, login_attempts_per_minute=1, register_attempts_per_minute=1)
            with sqlite3.connect(dbfile) as db:
                assert db.execute("SELECT COUNT(*) FROM audit WHERE action='expired'").fetchone()[0] == 0
            login('!', expected=429)
            print('PASS: audit IP/filter/cursor/details/before-after, secret redaction, retention and configurable rate limits', flush=True)
        finally:
            for p in processes:
                stop(p)
            for handle in handles:
                handle.close()


if __name__ == '__main__':
    main()
