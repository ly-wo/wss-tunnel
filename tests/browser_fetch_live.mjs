// Started by access_auth.py --browser against actual Rust tunnel binaries.
// A local HTTP router maps each URL prefix to a tunnel subdomain without external DNS.
import assert from 'node:assert/strict';
import { createTunnelFetch, TunnelAuthError } from '../client/browser/tunnel.ts';

const base = process.env.TUNNEL_BROWSER_TEST_URL;
assert(base, 'Start with python3 tests/access_auth.py --browser');
for (const [name, auth] of [
  ['basic', { type: 'basic', username: 'visitor', password: 'pass:word' }],
  ['bearer', { type: 'bearer', token: 'access-test-token' }],
  ['both', { type: 'basic', username: 'visitor', password: 'pass:word' }],
  ['both', { type: 'bearer', token: 'access-test-token' }],
]) {
  const request = createTunnelFetch(`${base}${name}/`, () => auth);
  const response = await request('frontend?q=1');
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { authorization: null, path: '/frontend?q=1' });
  const bad = createTunnelFetch(`${base}${name}/`, () => ({ type: 'bearer', token: 'wrong' }));
  await assert.rejects(bad('frontend'), TunnelAuthError);
  await assert.rejects(request('redirect'), TypeError);
}
console.log('PASS: createTunnelFetch through real tunnel (Basic, Bearer, both, 401, credential stripping, redirects)');
