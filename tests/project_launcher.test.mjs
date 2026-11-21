import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { tunnelPlan, verifyClient } from '../extensions/node/tunnel.mjs';

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'project-tunnel-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'password'), 'private-access-password');
  fs.writeFileSync(path.join(root, 'token'), 'private-server-token');
  fs.writeFileSync(path.join(root, '.env.tunnel'), 'TUNNEL_ENDPOINT=https://connect.example.com\nTUNNEL_NAME=sample-app\nTUNNEL_USERNAME=user\nTUNNEL_APP_COMMAND_JSON=["node","scripts/start.mjs"]\nTUNNEL_ACCESS_BASIC_USERNAME=visitor\nTUNNEL_ACCESS_BASIC_PASSWORD_FILE=password\n');
  return root;
}

test('project config builds independent access flags without exposing credential contents', t => {
  const root = fixture(t);
  const plan = tunnelPlan(root, {});
  assert.equal(plan.target, 'http://127.0.0.1:3000');
  assert.deepEqual(plan.args.slice(-4), ['--access-basic-username', 'visitor', '--access-basic-password-file', path.join(root, 'password')]);
  assert(!plan.args.join(' ').includes('private-access-password'));
  assert(!plan.args.includes('--token-file'));
  const bearer = tunnelPlan(root, { TUNNEL_USERNAME: '', TUNNEL_TOKEN_FILE: 'token', TUNNEL_ACCESS_BASIC_USERNAME: '', TUNNEL_ACCESS_BASIC_PASSWORD_FILE: '', TUNNEL_ACCESS_BEARER_TOKEN_FILE: 'password' });
  assert(bearer.args.includes('--token-file'));
  assert(bearer.args.includes('--access-bearer-token-file'));
  assert(!Object.keys(bearer.env).some(key => key.startsWith('TUNNEL_')));
});

test('rejects incomplete authentication and nonlocal or insecure endpoints', t => {
  const root = fixture(t);
  for (const overrides of [
    { TUNNEL_ENDPOINT: 'http://example.com' },
    { TUNNEL_TARGET: 'http://example.com:3000' },
    { TUNNEL_TARGET: 'http://127.0.0.1:3000/path' },
    { TUNNEL_NAME: 'BAD_NAME' },
    { TUNNEL_ACCESS_BASIC_PASSWORD_FILE: '' },
    { TUNNEL_ACCESS_BASIC_USERNAME: '', TUNNEL_ACCESS_BASIC_PASSWORD_FILE: '' },
  ]) assert.throws(() => tunnelPlan(root, overrides));
});

test('detects old clients before any connection', t => {
  const root = fixture(t);
  const bin = path.join(root, 'old-client');
  fs.writeFileSync(bin, '#!/bin/sh\nprintf "old client help"\n', { mode: 0o700 });
  assert.throws(() => verifyClient({ ...tunnelPlan(root, {}), bin }), /版本过旧/);
});

test('launcher checks health, propagates child failure, and leaves an existing app running', async t => {
  const root = fixture(t);
  const server = http.createServer((_req, res) => res.end('ok'));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const target = `http://127.0.0.1:${server.address().port}`;
  const bin = path.join(root, 'fake-client');
  fs.writeFileSync(bin, `#!${process.execPath}\nconst fs=require('node:fs'); if(process.argv.includes('--help')) {console.log('--access-basic-username --access-basic-password-file --access-bearer-token-file');} else {fs.writeFileSync(${JSON.stringify(path.join(root,'invoked'))},JSON.stringify({args:process.argv.slice(2),cwd:process.cwd(),leak:process.env.TUNNEL_ACCESS_BASIC_PASSWORD_FILE})); process.exit(7);}`, { mode: 0o700 });
  const module = pathToFileURL(path.resolve('extensions/node/tunnel.mjs')).href;
  const env = { ...process.env, TUNNEL_TARGET: target, TUNNEL_CLIENT_BIN: bin };
  async function run(args) {
    const code = `import {runTunnel} from ${JSON.stringify(module)}; await runTunnel(${JSON.stringify(root)},${JSON.stringify(args)}).catch(e=>{console.error(e.message);process.exitCode=1;});`;
    const child = spawn(process.execPath, ['--input-type=module', '-e', code], { env });
    let output = ''; child.stdout.on('data', b => output += b); child.stderr.on('data', b => output += b);
    return await new Promise(resolve => child.on('close', code => resolve({ code, output })));
  }
  assert.equal((await run(['--check'])).code, 0);
  assert(!fs.existsSync(path.join(root, 'invoked')));
  assert.match((await run(['--start'])).output, /已运行/);
  const result = await run([]);
  assert.equal(result.code, 1); assert.match(result.output, /退出（7）/);
  assert(!result.output.includes('private-access-password'));
  const invocation = JSON.parse(fs.readFileSync(path.join(root, 'invoked'), 'utf8'));
  assert.equal(invocation.cwd, fs.realpathSync(path.join(root, '.local/tunnel/runtime')));
  assert.equal(invocation.leak, undefined);
  assert.equal((await fetch(target)).status, 200);
});

test('start:tunnel stops only its own application when the tunnel exits', async t => {
  const root = fixture(t);
  const probe = http.createServer();
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
  const port = probe.address().port;
  await new Promise(resolve => probe.close(() => resolve()));
  fs.mkdirSync(path.join(root, 'scripts'));
  fs.writeFileSync(path.join(root, 'scripts/start.mjs'), `import http from 'node:http'; import fs from 'node:fs'; fs.writeFileSync('app.pid',String(process.pid)); http.createServer((_q,r)=>r.end('ok')).listen(Number(process.env.PORT),'127.0.0.1');`);
  const bin = path.join(root, 'fake-client');
  fs.writeFileSync(bin, `#!${process.execPath}\nif(process.argv.includes('--help'))console.log('--access-basic-username --access-basic-password-file --access-bearer-token-file');else process.exit(7);`, { mode: 0o700 });
  const code = `import {runTunnel} from ${JSON.stringify(pathToFileURL(path.resolve('extensions/node/tunnel.mjs')).href)}; await runTunnel(${JSON.stringify(root)},['--start']).catch(()=>{process.exitCode=1;});`;
  const child = spawn(process.execPath, ['--input-type=module', '-e', code], { env: { ...process.env, TUNNEL_CLIENT_BIN: bin, TUNNEL_TARGET: `http://127.0.0.1:${port}` }, stdio: 'ignore' });
  t.after(() => child.kill());
  const exit = await new Promise(resolve => child.on('close', resolve));
  assert.equal(exit, 1);
  const appPid = Number(fs.readFileSync(path.join(root, 'app.pid'), 'utf8'));
  assert.throws(() => process.kill(appPid, 0), { code: 'ESRCH' });
});

test('health path and application argv remain local and explicit', t => {
  const root = fixture(t);
  const plan = tunnelPlan(root, { TUNNEL_HEALTH_PATH: '/health?ready=1', TUNNEL_APP_COMMAND_JSON: '["node","server.mjs","literal;argument"]' });
  assert.equal(plan.healthURL.href, 'http://127.0.0.1:3000/health?ready=1');
  assert.deepEqual(plan.appCommand, ['node', 'server.mjs', 'literal;argument']);
  for (const value of ['https://evil.example/', '//evil.example/', 'relative']) {
    assert.throws(() => tunnelPlan(root, { TUNNEL_HEALTH_PATH: value }));
  }
  for (const value of ['node server.mjs', '{}', '[]', '[1]', '["node",null]', '["node","\\u0000"]']) {
    assert.throws(() => tunnelPlan(root, { TUNNEL_APP_COMMAND_JSON: value }));
  }
});
