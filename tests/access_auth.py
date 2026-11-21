#!/usr/bin/env python3
"""Local end-to-end access gate regression; no Docker or third-party Python packages.
Run cargo build -p tunnel-client -p tunnel-server, then python3 tests/access_auth.py.
"""
import base64
import hashlib
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
BINS = Path(os.environ.get('TUNNEL_TEST_BIN', ROOT / 'target/debug'))
BASIC = 'Basic ' + base64.b64encode(b'visitor:pass:word').decode()
BEARER = 'Bearer access-test-token'
hits = []

class Backend(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_): pass
    def do_GET(self):
        hits.append((self.path, self.headers.get('Authorization')))
        if self.headers.get('Upgrade', '').lower() == 'websocket':
            self.send_response(101)
            self.send_header('Upgrade', 'websocket')
            self.send_header('Connection', 'Upgrade')
            key = self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
            self.send_header('Sec-WebSocket-Accept', base64.b64encode(hashlib.sha1(key.encode()).digest()).decode())
            self.end_headers()
            self.wfile.write(b'\x81\x05hello')
            self.wfile.flush()
            self.connection.settimeout(5)
            try: self.rfile.read(1)
            except OSError: pass
            return
        body = json.dumps({'authorization': self.headers.get('Authorization'), 'path': self.path}).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

with tempfile.TemporaryDirectory(prefix='tunnel-access-') as directory:
    work = Path(directory)
    env = {k: v for k, v in os.environ.items() if not k.startswith('TUNNEL_') and k != 'CONNECT_DOMAIN'}
    procs, logs = [], []
    backend = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
    for name, value in [('token', 'test-' + 'x' * 48), ('password', 'pass:word\n'), ('bearer', 'access-test-token\n'), ('empty', '')]:
        (work / name).write_text(value)
    def start(args, name):
        log = open(work / (name + '.log'), 'w'); logs.append(log)
        p = subprocess.Popen([str(arg) for arg in args], cwd=work, env=env, stdout=log, stderr=log)
        procs.append(p); return p
    def request(host, authorization=None, ws=False):
        c = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        headers = {'Host': host + '.tunnel.test'}
        if authorization: headers['Authorization'] = authorization
        if ws:
            headers.update({'Connection': 'Upgrade', 'Upgrade': 'websocket', 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ=='})
        c.request('GET', '/check?q=1', headers=headers)
        r = c.getresponse(); status = r.status; h = r.getheaders()
        # On upgrade the local server sends a greeting: check actual tunneled WS data.
        body = r.fp.read(7) if status == 101 else r.read()
        c.close()
        return status, h, body
    try:
        start([BINS/'tunnel-server', '--domain', 'tunnel.test', '--connect-domain', '127.0.0.1', '--bind', f'127.0.0.1:{port}', '--public-scheme', 'http', '--token-file', work/'token', '--database', work/'auth.db'], 'server')
        deadline = time.monotonic() + 10
        while True:
            try:
                with socket.create_connection(('127.0.0.1', port), .1): break
            except OSError:
                if time.monotonic() > deadline: raise AssertionError('server startup timed out')
                time.sleep(.05)
        base = [BINS/'tunnel-client', str(backend.server_port), '--server', f'ws://127.0.0.1:{port}', '--token-file', work/'token']
        basic = ['--access-basic-username', 'visitor', '--access-basic-password-file', work/'password']
        bearer = ['--access-bearer-token-file', work/'bearer']
        for name, flags in [('basic', basic), ('bearer', bearer), ('both', basic+bearer), ('open', [])]:
            p = start(base + ['--name', name] + flags, name)
            deadline = time.monotonic() + 10
            while f'http://{name}.tunnel.test' not in (work/(name+'.log')).read_text():
                assert p.poll() is None, (work/(name+'.log')).read_text()
                assert time.monotonic() < deadline, 'client startup timed out'
                time.sleep(.05)
        for name in ['basic', 'bearer', 'both']:
            for value in [None, 'Bearer wrong', 'Basic !!!']:
                for ws in [False, True]:
                    before = len(hits)
                    status, headers, _ = request(name, value, ws)
                    assert status == 401, (name, status)
                    assert len(hits) == before, 'unauthorized request reached backend'
                    if not ws:
                        assert any(k.lower() == 'www-authenticate' for k,v in headers)
                        assert ('cache-control', 'no-store') in [(k.lower(),v) for k,v in headers]
            for value in ([BASIC] if name == 'basic' else [BEARER] if name == 'bearer' else [BASIC, BEARER]):
                status, _, body = request(name, value)
                assert status == 200 and json.loads(body)['authorization'] is None
                status, _, body = request(name, value, True)
                assert status == 101 and body == b'\x81\x05hello', (status, body, (work/(name+'.log')).read_text())
                assert hits[-1][1] is None
        assert request('basic', BEARER)[0] == 401
        assert request('bearer', BASIC)[0] == 401
        assert request('open')[0] == 200
        assert json.loads(request('open', 'Bearer backend-credential')[2])['authorization'] == 'Bearer backend-credential'
        for flags in [['--access-basic-username','visitor'], ['--access-basic-password-file',work/'password'], ['--access-bearer-token-file',work/'empty']]:
            result = subprocess.run([str(x) for x in base+flags], cwd=work,env=env,capture_output=True,timeout=5)
            assert result.returncode != 0
        print('PASS: Basic/Bearer/both, HTTP + WS payloads, 401 before local access, challenges, secret stripping, open-mode compatibility, invalid CLI configuration')
    finally:
        for p in reversed(procs):
            p.terminate()
            try: p.wait(timeout=5)
            except subprocess.TimeoutExpired: p.kill(); p.wait()
        for log in logs: log.close()
        backend.shutdown(); backend.server_close()
