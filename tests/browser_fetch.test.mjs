import test from 'node:test';
import assert from 'node:assert/strict';
import { createTunnelFetch, TunnelAuthError } from '../client/browser/tunnel.ts';

const base = 'https://demo.example/api/';
const bearer = () => ({ type: 'bearer', token: 'token-one' });

test('Bearer uses current login state and preserves method/body/headers/signal without mutation', async t => {
  const calls = [];
  const response = new Response('{"ok":true}');
  t.mock.method(globalThis, 'fetch', async (...args) => { calls.push(args); return response; });
  let token = 'token-one';
  const request = createTunnelFetch(base, () => ({ type: 'bearer', token }));
  const headers = new Headers({ 'Content-Type': 'application/json' });
  const signal = new AbortController().signal;
  assert.equal(await request('items?q=1', { method: 'POST', body: '{"x":1}', headers, signal, redirect: 'follow' }), response);
  assert.equal(calls[0][0].href, 'https://demo.example/api/items?q=1');
  assert.equal(calls[0][1].headers.get('authorization'), 'Bearer token-one');
  assert.equal(calls[0][1].headers.get('content-type'), 'application/json');
  assert.equal(calls[0][1].method, 'POST');
  assert.equal(calls[0][1].body, '{"x":1}');
  assert.equal(calls[0][1].signal, signal);
  assert.equal(calls[0][1].redirect, 'error');
  assert.equal(headers.has('authorization'), false);
  token = 'token-two';
  await request('/health');
  assert.equal(calls[1][1].headers.get('authorization'), 'Bearer token-two');
});

test('Basic encodes Unicode and colons in passwords as UTF-8', async t => {
  t.mock.method(globalThis, 'fetch', async (_url, init) => {
    assert.equal(init.headers.get('authorization'), `Basic ${Buffer.from('访客:密:码').toString('base64')}`);
    return new Response('ok');
  });
  await createTunnelFetch(base, () => ({ type: 'basic', username: '访客', password: '密:码' }))('/');
});

test('rejects cross-origin URLs and header conflicts before reading credentials or making requests', async t => {
  const network = t.mock.method(globalThis, 'fetch', () => { throw new Error('Must not send'); });
  let reads = 0;
  const request = createTunnelFetch(base, () => { reads++; return bearer(); });
  for (const path of ['https://evil.example/', '//evil.example/', 'http://demo.example/', 'https://demo.example:444/', 'https://user:pass@demo.example/', 'data:text/plain,hello']) {
    await assert.rejects(request(path), /configured tunnel origin/);
  }
  for (const headers of [{ authorization: 'business-token' }, [['AUTHORIZATION', 'business-token']], new Headers({ Authorization: 'business-token' })]) {
    await assert.rejects(request('/', { headers }), /reserved/);
  }
  await assert.rejects(request('/', { mode: 'no-cors' }), /no-cors/);
  assert.equal(reads, 0);
  assert.equal(network.mock.callCount(), 0);
  for (const url of ['file:///tmp/test', 'https://user:pass@demo.example/']) {
    assert.throws(() => createTunnelFetch(url, bearer), /base URL/);
  }
});

test('invalid credentials are rejected without disclosure or network activity', async t => {
  const network = t.mock.method(globalThis, 'fetch', () => { throw new Error('Must not send'); });
  for (const auth of [
    { type: 'bearer', token: '' }, { type: 'bearer', token: 'secret\nvalue' },
    { type: 'basic', username: 'user:name', password: 'secret' },
    { type: 'basic', username: 'user', password: '' },
    { type: 'basic', username: 'user', password: 'secret\nvalue' },
  ]) {
    await assert.rejects(createTunnelFetch(base, () => auth)('/'), error => {
      assert(!error.message.includes('secret')); return error instanceof TypeError;
    });
  }
  assert.equal(network.mock.callCount(), 0);
});

test('401 is identifiable; other responses, network failures and aborts retain their semantics', async t => {
  const request = createTunnelFetch(base, bearer);
  const mock = t.mock.method(globalThis, 'fetch', async () => new Response('private', { status: 401 }));
  await assert.rejects(request('/'), error => error instanceof TunnelAuthError && error.status === 401);
  const failure = new Response('unavailable', { status: 503 });
  mock.mock.mockImplementation(async () => failure);
  assert.equal(await request('/'), failure);
  for (const error of [new TypeError('Network failure'), new DOMException('Aborted', 'AbortError')]) {
    mock.mock.mockImplementation(async () => { throw error; });
    await assert.rejects(request('/'), actual => actual === error);
  }
});
