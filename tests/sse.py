#!/usr/bin/env python3
"""Independent local SSE regression. No business project, Docker or public service.
Build the binaries first. For real mixed-version tests pass --legacy-dir pointing
at binaries built from the unmodified ab3b6c6 commit (not a new binary with SSE off).
"""
import argparse
import base64
import concurrent.futures
import hashlib
import platform
import http.client
import http.server
import os
from pathlib import Path
import select
import socket
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
BASIC = 'Basic ' + base64.b64encode(b'visitor:password').decode()
BEARER = 'Bearer fixture-access'
PAYLOAD = 'data: 中文🙂\r\n\r\n'.encode() + bytes([0, 255, 128]) + b'\n: heartbeat\n\n'
state_lock = threading.Lock()
active, counts, closed, seen_auth = set(), {}, set(), []


def eventually(predicate, seconds=8):
    deadline = time.monotonic() + seconds
    while not predicate():
        assert time.monotonic() < deadline, 'condition did not become true'
        time.sleep(.03)


def peer_closed(sock):
    readable, _, _ = select.select([sock], [], [], 0)
    return bool(readable) and not sock.recv(1, socket.MSG_PEEK)


class Backend(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def do_POST(self):
        self.do_GET()

    def do_GET(self):
        path = self.path
        with state_lock:
            active.add(path)
            counts[path] = 0
            seen_auth.append(self.headers.get('Authorization'))
        try:
            if self.headers.get('Upgrade', '').lower() == 'websocket':
                self.send_response(101)
                self.send_header('Upgrade', 'websocket')
                self.send_header('Connection', 'Upgrade')
                key = self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
                self.send_header('Sec-WebSocket-Accept', base64.b64encode(hashlib.sha1(key.encode()).digest()).decode())
                self.end_headers()
                self.wfile.write(b'\x81\x05hello')
                self.wfile.flush()
                self.close_connection = True
                self.connection.settimeout(5)
                self.connection.recv(1024)  # consume the close frame or TCP EOF
                return
            if path.startswith('/delayed'):
                delay = 20 if path.startswith('/delayed-json') else 40
                until = time.monotonic() + delay
                while time.monotonic() < until:
                    if peer_closed(self.connection):
                        return
                    time.sleep(.02)
            if path.startswith('/json') or path.startswith('/delayed-json'):
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', '11')
                self.end_headers()
                self.wfile.write(b'{"ok":true}')
                return
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
            self.send_header('Transfer-Encoding', 'chunked')
            self.end_headers()
            self.wfile.flush()
            if path.startswith('/finite') or self.command != 'GET':
                # Every UTF-8 byte and CR/LF boundary is deliberately fragmented.
                for byte in PAYLOAD:
                    self.chunk(bytes([byte]))
                self.wfile.write(b'0\r\n\r\n')
                return
            if path.startswith('/broken'):
                self.chunk(b'data: first\n\n')
                self.close_connection = True  # missing chunked terminator is an error
                return
            if path.startswith('/idle'):
                while not peer_closed(self.connection):
                    time.sleep(.02)
                return
            if path.startswith('/flood'):
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
                while True:
                    self.chunk(b'x' * 16384)
                    with state_lock:
                        counts[path] += 1
            else:
                while True:
                    if peer_closed(self.connection):
                        return
                    self.chunk(b'data: tick\n\n')
                    time.sleep(.05)
        except (OSError, ConnectionError):
            self.close_connection = True
        finally:
            with state_lock:
                active.discard(path)
                closed.add(path)

    def chunk(self, value):
        self.wfile.write(f'{len(value):x}\r\n'.encode() + value + b'\r\n')
        self.wfile.flush()


class Fixture:
    def __init__(self, work, bins, server_flags=()):
        self.work, self.bins = work, bins
        self.procs, self.logs = [], []
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            self.port = probe.getsockname()[1]
        self.start([bins / 'tunnel-server', '--domain', 'tunnel.test', '--connect-domain', '127.0.0.1', '--bind', f'127.0.0.1:{self.port}', '--public-scheme', 'http', '--token-file', work / 'token', '--database', work / f'auth-{self.port}.db', *server_flags], 'server')
        eventually(lambda: self.listening())

    def listening(self):
        try:
            with socket.create_connection(('127.0.0.1', self.port), .1):
                return True
        except OSError:
            return False

    def start(self, args, name):
        log = open(self.work / f'{self.port}-{name}.log', 'w+')
        self.logs.append(log)
        env = {k: v for k, v in os.environ.items() if not k.startswith('TUNNEL_') and k != 'CONNECT_DOMAIN'}
        process = subprocess.Popen([str(v) for v in args], cwd=self.work, env=env, stdout=log, stderr=log)
        self.procs.append(process)
        return process, Path(log.name)

    def client(self, backend, name='sse', bins=None, flags=()):
        proc, log = self.start([(bins or self.bins) / 'tunnel-client', str(backend.server_port), '--server', f'ws://127.0.0.1:{self.port}', '--token-file', self.work / 'token', '--name', name,
                                '--access-basic-username', 'visitor', '--access-basic-password-file', self.work / 'password', '--access-bearer-token-file', self.work / 'access', *flags], name)
        eventually(lambda: proc.poll() is not None or f'http://{name}.tunnel.test' in log.read_text())
        assert proc.poll() is None, log.read_text()
        return proc

    def request(self, path, name='sse', accept='text/event-stream', auth=BASIC, method='GET', timeout=40):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=timeout)
        headers = {'Host': f'{name}.tunnel.test'}
        if accept is not None:
            headers['Accept'] = accept
        if auth is not None:
            headers['Authorization'] = auth
        connection.request(method, path, headers=headers)
        return connection, connection.getresponse()

    def close(self):
        for proc in reversed(self.procs):
            proc.terminate()
        for proc in reversed(self.procs):
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        for log in self.logs:
            log.close()


def close_response(c, r):
    r.close()
    c.close()


def websocket_check(f):
    c = http.client.HTTPConnection('127.0.0.1', f.port, timeout=5)
    c.request('GET', '/ws', headers={'Host':'sse.tunnel.test', 'Authorization':BEARER, 'Connection':'Upgrade', 'Upgrade':'websocket', 'Sec-WebSocket-Version':'13', 'Sec-WebSocket-Key':'dGhlIHNhbXBsZSBub25jZQ=='})
    r = c.getresponse()
    assert r.status == 101 and r.fp.read(7) == b'\x81\x05hello'
    close_response(c, r)


def rss_kib(proc):
    return int(subprocess.check_output(['ps', '-o', 'rss=', '-p', str(proc.pid)]).strip())


def run(args):
    print(f'Environment: {platform.platform()} {platform.machine()}; local supplied binaries', flush=True)
    bins = Path(args.bin_dir).resolve()
    backend = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory(prefix='wss-sse-') as directory:
        work = Path(directory)
        for name, value in [('token', 'fixture-' + 'x' * 40), ('password', 'password'), ('access', 'fixture-access')]:
            (work / name).write_text(value)
        f = Fixture(work, bins)
        try:
            client = f.client(backend, flags=['--sse-idle-timeout', '2', '--sse-backpressure-timeout', '8'])
            c = http.client.HTTPConnection('127.0.0.1', f.port, timeout=5)
            c.request('GET', '/_tunnel/ws', headers={'Host':'127.0.0.1', 'Upgrade':'websocket', 'Connection':'Upgrade', 'Sec-WebSocket-Version':'13', 'Sec-WebSocket-Key':'dGhlIHNhbXBsZSBub25jZQ==', 'X-Tunnel-Negotiation':'1'})
            r = c.getresponse()
            assert r.status == 401, 'capability negotiation must not bypass tunnel authentication'
            close_response(c, r)
            # Headers and data arrive while the upstream remains open.
            c, r = f.request('/open-first')
            assert r.status == 200 and r.read1(100) == b'data: tick\n\n'
            assert '/open-first' in active
            close_response(c, r)
            eventually(lambda: '/open-first' in closed)
            print('PASS first bytes before EOF and cancellation after headers', flush=True)

            for accept in ['text/event-stream', '*/*', 'application/json, text/event-stream; charset=UTF-8;q=0.5', None]:
                c, r = f.request('/finite-bytes', accept=accept)
                assert r.status == 200 and r.read() == PAYLOAD
                close_response(c, r)
            for method, accept, path in [('POST', 'text/event-stream', '/finite-post'), ('GET', 'text/event-stream;q=0,*/*;q=1', '/finite-excluded'), ('GET', 'text/event-stream', '/json'), ('GET', 'text/event-stream;profile=other', '/finite-params')]:
                c, r = f.request(path, method=method, accept=accept)
                assert r.status == 200 and r.getheader('Content-Length') is not None
                assert r.read() == (b'{"ok":true}' if path == '/json' else PAYLOAD)
                close_response(c, r)
            print('PASS byte preservation, EOF and HTTP fallback', flush=True)

            for auth in [BASIC, BEARER, None, 'Bearer wrong']:
                before = len(seen_auth)
                c, r = f.request('/finite-auth', auth=auth)
                if auth in [BASIC, BEARER]:
                    assert r.status == 200 and r.read() == PAYLOAD
                    assert seen_auth[-1] is None
                else:
                    assert r.status == 401 and r.getheader('WWW-Authenticate')
                    r.read()
                    assert len(seen_auth) == before
                close_response(c, r)
            print('PASS Basic/Bearer, rejection before upstream, credential stripping', flush=True)

            # Disconnect before upstream headers; it must not wait for 30s timeout.
            sock = socket.create_connection(('127.0.0.1', f.port))
            sock.sendall(f'GET /delayed-cancel HTTP/1.1\r\nHost: sse.tunnel.test\r\nAuthorization: {BASIC}\r\nAccept: text/event-stream\r\n\r\n'.encode())
            eventually(lambda: '/delayed-cancel' in active)
            sock.close()
            eventually(lambda: '/delayed-cancel' in closed, 5)
            print('PASS cancellation before response headers', flush=True)

            # Slot ownership must cover the body, not merely the response head.
            streams = [f.request(f'/hold-{i}') for i in range(8)]
            c, r = f.request('/limit-ninth')
            assert r.status == 503
            close_response(c, r)
            c, r = f.request('/json-capacity')
            assert r.status == 200 and r.read() == b'{"ok":true}'
            close_response(c, r)
            for c, r in streams:
                close_response(c, r)
            eventually(lambda: all(f'/hold-{i}' in closed for i in range(8)))
            c, r = f.request('/finite-recovered')
            assert r.status == 200 and r.read() == PAYLOAD
            close_response(c, r)
            print('PASS stream limit, ordinary capacity and permit recovery', flush=True)

            # A tiny receive window makes transport buffering fill quickly.
            slow = socket.socket()
            slow.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            slow.connect(('127.0.0.1', f.port))
            slow.sendall(f'GET /flood-slow HTTP/1.1\r\nHost: sse.tunnel.test\r\nAuthorization: {BASIC}\r\nAccept: text/event-stream\r\n\r\n'.encode())
            eventually(lambda: counts.get('/flood-slow', 0) > 4)
            plateau = None
            for _ in range(12):
                a = counts['/flood-slow']
                time.sleep(.25)
                if counts['/flood-slow'] == a:
                    plateau = a
                    break
            assert plateau is not None, 'upstream did not backpressure'
            rss_before = (rss_kib(client), rss_kib(f.procs[0]))
            # Paused reads must not trip the 2-second upstream idle timer.
            time.sleep(2.2)
            rss_after = (rss_kib(client), rss_kib(f.procs[0]))
            assert '/flood-slow' not in closed
            assert counts['/flood-slow'] == plateau
            c, r = f.request('/json-during-slow')
            assert r.status == 200 and r.read() == b'{"ok":true}'
            close_response(c, r)
            c, r = f.request('/finite-during-slow')
            assert r.status == 200 and r.read() == PAYLOAD
            close_response(c, r)
            websocket_check(f)
            slow.close()
            eventually(lambda: '/flood-slow' in closed)
            print(f'PASS backpressure plateau ({plateau} upstream writes), idle pause and concurrent HTTP/SSE/WS; RSS KiB client/server {rss_before} -> {rss_after}', flush=True)

            stalled = socket.socket()
            stalled.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            stalled.connect(('127.0.0.1', f.port))
            stalled.sendall(f'GET /flood-stall HTTP/1.1\r\nHost: sse.tunnel.test\r\nAuthorization: {BASIC}\r\nAccept: text/event-stream\r\n\r\n'.encode())
            eventually(lambda: counts.get('/flood-stall', 0) > 4)
            eventually(lambda: '/flood-stall' in closed, 12)
            stalled.close()
            eventually(lambda: any('BackpressureTimeout' in Path(log.name).read_text() for log in f.logs))
            print('PASS independent backpressure timeout stops upstream', flush=True)

            for path in ['/broken-stream', '/idle-stream']:
                c, r = f.request(path)
                assert r.status == 200
                try:
                    r.read()
                    raise AssertionError('abnormal stream became a clean EOF')
                except (http.client.IncompleteRead, ConnectionResetError):
                    pass
                close_response(c, r)
                eventually(lambda: path in closed)
            print('PASS post-header read error and idle timeout abort the body', flush=True)

            if not args.quick:
                def long_stream():
                    c, r = f.request('/long')
                    start = time.monotonic()
                    while time.monotonic() - start < 33:
                        assert r.read1(100)
                    assert '/long' in active
                    close_response(c, r)
                def delayed_json():
                    c, r = f.request('/delayed-json')
                    assert r.status == 200 and r.read() == b'{"ok":true}'
                    close_response(c, r)
                def head_timeout():
                    c, r = f.request('/delayed-timeout')
                    assert r.status == 504
                    close_response(c, r)
                    eventually(lambda: '/delayed-timeout' in closed)
                with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                    results = [pool.submit(fn) for fn in [long_stream, delayed_json, head_timeout]]
                    for result in results:
                        result.result()
                print('PASS >30s stream, 20s ordinary fallback and upstream header timeout', flush=True)

            c, r = f.request('/finite-private-path-canary?secret=query-canary')
            assert r.read() == PAYLOAD
            close_response(c, r)
            for log in f.logs:
                text = Path(log.name).read_text()
                for private in ['private-path-canary', 'query-canary', 'fixture-access', BASIC]:
                    assert private not in text, 'sensitive value in logs'

            c, r = f.request('/disconnect-tunnel')
            assert r.read1(100)
            client.terminate()
            client.wait(timeout=5)
            try:
                r.read()
                raise AssertionError('tunnel disconnect became normal EOF')
            except (http.client.IncompleteRead, ConnectionResetError):
                pass
            close_response(c, r)
            eventually(lambda: '/disconnect-tunnel' in closed)
            print('PASS tunnel disconnect aborts body and closes upstream', flush=True)
        except BaseException:
            for log in f.logs:
                log.flush()
                print(Path(log.name).read_text()[-4000:])
            raise
        finally:
            f.close()

        # Old binaries are separately built from the untouched source revision.
        if args.legacy_dir:
            old = Path(args.legacy_dir).resolve()
            combinations = [(old, old, [], []), (old, bins, [], []), (bins, old, [], []), (bins, bins, [], []), (bins, bins, ['--http-sse', 'off'], []), (bins, bins, [], ['--http-sse', 'off'])]
            for i, (server_bin, client_bin, sf, cf) in enumerate(combinations):
                f = Fixture(work, server_bin, sf)
                try:
                    f.client(backend, bins=client_bin, flags=cf)
                    c, r = f.request('/finite-compat')
                    assert r.status == 200 and r.read() == PAYLOAD
                    streaming = server_bin == bins and client_bin == bins and not sf and not cf
                    assert (r.getheader('Transfer-Encoding') == 'chunked') == streaming
                    close_response(c, r)
                    websocket_check(f)
                    c, r = f.request('/json-compat')
                    assert r.status == 200 and r.read() == b'{"ok":true}'
                    close_response(c, r)
                    c, r = f.request('/finite-compat', auth='Bearer wrong')
                    assert r.status == 401
                    close_response(c, r)
                finally:
                    f.close()
                print(f'PASS real-version combination/disable case {i + 1}', flush=True)
        else:
            print('SKIP real old/new binaries: supply --legacy-dir', flush=True)
    backend.shutdown()
    backend.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bin-dir', default=ROOT / 'target/debug')
    parser.add_argument('--legacy-dir')
    parser.add_argument('--quick', action='store_true', help='skip the real 33-second and header timeout tests')
    run(parser.parse_args())
