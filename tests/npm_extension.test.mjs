import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { initProject } from '../extensions/node/init.mjs';

function project(t, pkg = { name: 'sample-project', scripts: { dev: 'vite' } }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'tunnel-npm-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify(pkg, null, 2));
  return root;
}

test('init adds scripts and ignored configuration without touching application source', t => {
  const root = project(t);
  fs.mkdirSync(path.join(root, 'src'));
  fs.writeFileSync(path.join(root, 'src/main.ts'), 'unchanged');
  fs.writeFileSync(path.join(root, '.gitignore'), 'node_modules/');
  initProject(root);
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json')));
  assert.deepEqual(pkg.scripts, { dev: 'vite', tunnel: 'wss-tunnel', 'start:tunnel': 'wss-tunnel --start' });
  assert.equal(fs.readFileSync(path.join(root, '.gitignore'), 'utf8'), 'node_modules/\n.env.tunnel\n.local/tunnel/\n');
  assert.match(fs.readFileSync(path.join(root, '.env.tunnel'), 'utf8'), /connect\.example\.com/);
  assert.equal(fs.readFileSync(path.join(root, 'src/main.ts'), 'utf8'), 'unchanged');
  if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(root, '.env.tunnel')).mode & 0o777, 0o600);
});

test('init preserves existing credentials and repeated initialization is idempotent', t => {
  const root = project(t);
  fs.writeFileSync(path.join(root, '.env.tunnel'), 'existing-private-config');
  initProject(root);
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8');
  const ignore = fs.readFileSync(path.join(root, '.gitignore'), 'utf8');
  initProject(root);
  assert.equal(fs.readFileSync(path.join(root, '.env.tunnel'), 'utf8'), 'existing-private-config');
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before);
  assert.equal(fs.readFileSync(path.join(root, '.gitignore'), 'utf8'), ignore);
});

test('conflicting scripts fail before changing files', t => {
  const root = project(t, { scripts: { tunnel: 'existing-command' } });
  const before = fs.readFileSync(path.join(root, 'package.json'), 'utf8');
  assert.throws(() => initProject(root), /已存在/);
  assert.equal(fs.readFileSync(path.join(root, 'package.json'), 'utf8'), before);
  assert(!fs.existsSync(path.join(root, '.gitignore')));
  assert(!fs.existsSync(path.join(root, '.env.tunnel')));
});

test('CLI initializes the consuming project rather than the installed package directory', t => {
  const root = project(t);
  const result = spawnSync(process.execPath, [path.resolve('extensions/node/cli.mjs'), 'init'], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert(fs.existsSync(path.join(root, '.env.tunnel')));
  const help = spawnSync(process.execPath, [path.resolve('extensions/node/cli.mjs'), '--help'], { cwd: root, encoding: 'utf8' });
  assert.equal(help.status, 0);
  assert.match(help.stdout, /tunnel-client/);
});

test('packed npm artifact installs offline and its bin works in a fresh consuming project', { timeout: 30_000 }, t => {
  const root = project(t);
  const pack = spawnSync('npm', ['pack', '--json', '--ignore-scripts', '--pack-destination', root], { encoding: 'utf8', timeout: 15_000 });
  assert.equal(pack.status, 0, pack.stderr);
  const [metadata] = JSON.parse(pack.stdout);
  const names = metadata.files.map(file => file.path);
  assert(names.includes('extensions/node/cli.mjs'));
  assert(names.includes('extensions/node/tunnel.env.example'));
  assert(!names.some(name => /(^|\/)(\.env(?:\.|$)|\.local|node_modules|target|secrets|credentials)/.test(name)));
  const install = spawnSync('npm', ['install', '--offline', '--ignore-scripts', '--no-audit', '--no-fund', '--save-dev', path.join(root, metadata.filename)], { cwd: root, encoding: 'utf8', timeout: 15_000 });
  assert.equal(install.status, 0, install.stderr);
  const cli = spawnSync('npm', ['exec', '--offline', '--', 'wss-tunnel', 'init'], { cwd: root, encoding: 'utf8', timeout: 10_000 });
  assert.equal(cli.status, 0, cli.stderr);
  assert.equal(JSON.parse(fs.readFileSync(path.join(root, 'package.json'))).scripts.tunnel, 'wss-tunnel');
  assert(fs.existsSync(path.join(root, '.env.tunnel')));
  const installed = path.join(root, 'node_modules/@ly-wo/wss-tunnel');
  assert(!fs.existsSync(path.join(installed, '.env.tunnel')));
});
