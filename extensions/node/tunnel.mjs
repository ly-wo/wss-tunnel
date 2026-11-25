/** Project-level tunnel launcher. Does not modify the Vue app or API handlers. */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';
import { spawn, spawnSync } from 'node:child_process';

export function tunnelPlan(root, env = process.env) {
  const configFile = path.resolve(root, env.TUNNEL_PROJECT_CONFIG || '.env.tunnel');
  const config = { ...(fs.existsSync(configFile) ? parseEnv(fs.readFileSync(configFile, 'utf8')) : {}), ...env };
  const required = key => {
    if (!config[key]?.trim()) throw new Error(`请在 .env.tunnel 配置 ${key}`);
    return config[key].trim();
  };
  const file = key => {
    const value = path.resolve(root, required(key));
    fs.accessSync(value, fs.constants.R_OK);
    if (!fs.statSync(value).isFile()) throw new Error(`${key} 必须指向文件`);
    return value;
  };
  const server = required('TUNNEL_ENDPOINT');
  const target = config.TUNNEL_TARGET || 'http://127.0.0.1:3000';
  const endpoint = new URL(server), local = new URL(target);
  if (!['https:', 'wss:'].includes(endpoint.protocol) || endpoint.username || endpoint.password) throw new Error('TUNNEL_ENDPOINT 必须是不含凭据的 HTTPS/WSS 地址');
  if (local.pathname !== '/' || local.search || local.hash || !['http:', 'https:'].includes(local.protocol) || !['localhost', '127.0.0.1', '[::1]'].includes(local.hostname) || local.username || local.password) throw new Error('TUNNEL_TARGET 必须指向本机 HTTP(S) 服务');
  const name = required('TUNNEL_NAME');
  if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) throw new Error('TUNNEL_NAME 不是合法子域名');
  const args = ['--server', server, '--target', target, '--name', name];
  if (config.TUNNEL_USERNAME) {
    args.push('--username', config.TUNNEL_USERNAME);
    if (config.TUNNEL_PASSWORD_FILE) args.push('--password-file', file('TUNNEL_PASSWORD_FILE'));
  } else {
    if (config.TUNNEL_PASSWORD_FILE) throw new Error('TUNNEL_PASSWORD_FILE 需要 TUNNEL_USERNAME');
    args.push('--token-file', file('TUNNEL_TOKEN_FILE'));
  }
  if (config.TUNNEL_ACCESS_BASIC_USERNAME || config.TUNNEL_ACCESS_BASIC_PASSWORD_FILE) {
    args.push('--access-basic-username', required('TUNNEL_ACCESS_BASIC_USERNAME'), '--access-basic-password-file', file('TUNNEL_ACCESS_BASIC_PASSWORD_FILE'));
  }
  if (config.TUNNEL_ACCESS_BEARER_TOKEN_FILE) args.push('--access-bearer-token-file', file('TUNNEL_ACCESS_BEARER_TOKEN_FILE'));
  if (!config.TUNNEL_ACCESS_BASIC_USERNAME && !config.TUNNEL_ACCESS_BEARER_TOKEN_FILE) throw new Error('请配置 Basic 或 Bearer 访问认证后再公开应用');
  for (const [key, flag] of [['TUNNEL_CA_CERT', '--ca-cert'], ['TUNNEL_LOCAL_CA_CERT', '--local-ca-cert']]) {
    if (config[key]) args.push(flag, file(key));
  }
  if (config.TUNNEL_HTTP_SSE !== undefined) {
    if (!['auto', 'off'].includes(config.TUNNEL_HTTP_SSE)) throw new Error('TUNNEL_HTTP_SSE 必须为 auto 或 off');
    args.push('--http-sse', config.TUNNEL_HTTP_SSE);
  }
  for (const [key, flag, max] of [
    ['TUNNEL_SSE_MAX_STREAMS', '--sse-max-streams', 8],
    ['TUNNEL_SSE_IDLE_TIMEOUT', '--sse-idle-timeout', Number.MAX_SAFE_INTEGER],
    ['TUNNEL_SSE_BACKPRESSURE_TIMEOUT', '--sse-backpressure-timeout', Number.MAX_SAFE_INTEGER],
  ]) {
    if (config[key] === undefined) continue;
    if (!/^[1-9][0-9]*$/.test(config[key]) || !Number.isSafeInteger(Number(config[key])) || Number(config[key]) > max) throw new Error(`${key} 超出有效范围`);
    args.push(flag, config[key]);
  }
  const configuredBin = config.TUNNEL_CLIENT_BIN;
  const bundledBin = path.join(root, '.local/tunnel', process.platform === 'win32' ? 'tunnel-client.exe' : 'tunnel-client');
  const bin = configuredBin ? ((configuredBin.includes('/') || configuredBin.includes('\\')) ? path.resolve(root, configuredBin) : configuredBin)
    : fs.existsSync(bundledBin) ? bundledBin : 'tunnel-client';
  // The Rust client loads .env automatically. Run in an isolated directory and remove
  // inherited tunnel options so only the validated arguments control this connection.
  const healthPath = config.TUNNEL_HEALTH_PATH || '/';
  const healthURL = new URL(healthPath, local);
  if (!healthPath.startsWith('/') || healthPath.startsWith('//') || healthURL.origin !== local.origin || healthURL.username || healthURL.password) throw new Error('TUNNEL_HEALTH_PATH 必须是同源绝对路径');
  let appCommand;
  if (config.TUNNEL_APP_COMMAND_JSON) {
    try { appCommand = JSON.parse(config.TUNNEL_APP_COMMAND_JSON); } catch { throw new Error('TUNNEL_APP_COMMAND_JSON 必须是 JSON 字符串数组'); }
    if (!Array.isArray(appCommand) || !appCommand.length || appCommand.some(arg => typeof arg !== 'string' || arg.includes('\u0000')) || !appCommand[0]) throw new Error('TUNNEL_APP_COMMAND_JSON 必须是非空命令参数数组');
  }
  const childEnv = Object.fromEntries(Object.entries(env).filter(([key]) => !key.startsWith('TUNNEL_')));
  return { root, bin, args, target, name, healthURL, appCommand, env: childEnv, cwd: path.join(root, '.local/tunnel/runtime') };
}

export function verifyClient(plan) {
  const result = spawnSync(plan.bin, ['--help'], { env: plan.env, encoding: 'utf8', timeout: 10_000 });
  if (result.error || result.status !== 0) throw new Error('无法运行 tunnel-client；请配置 TUNNEL_CLIENT_BIN 或安装到 .local/tunnel/tunnel-client');
  for (const flag of ['--access-basic-username', '--access-basic-password-file', '--access-bearer-token-file', ...plan.args.filter(arg => ['--http-sse', '--sse-max-streams', '--sse-idle-timeout', '--sse-backpressure-timeout'].includes(arg))]) {
    if (!result.stdout.includes(flag)) throw new Error(`tunnel-client 版本过旧，不支持 ${flag}，请更新客户端或移除不支持的可选参数`);
  }
}

async function isReady(healthURL) {
  try {
    const response = await fetch(healthURL, { signal: AbortSignal.timeout(1500), redirect: 'error' });
    const ok = response.ok;
    await response.body?.cancel();
    return ok;
  } catch { return false; }
}

export async function runTunnel(root, argv = process.argv.slice(2)) {
  if (argv.includes('--help')) {
    console.log('node scripts/tunnel.mjs [--check | --start]\n配置：.env.tunnel；示例：tunnel.env.example\n--start 通过 TUNNEL_APP_COMMAND_JSON 启动应用，不经过 shell');
    return;
  }
  if (argv.some(arg => !['--check', '--start'].includes(arg))) throw new Error('只支持 --check、--start 或 --help');
  const plan = tunnelPlan(root);
  verifyClient(plan);
  if (argv.includes('--check')) {
    console.log(`客户端和配置检查通过；本地服务${await isReady(plan.healthURL) ? '可用' : '尚未就绪'}。未连接公网。`);
    return;
  }
  fs.mkdirSync(plan.cwd, { recursive: true, mode: 0o700 });
  let app, tunnel;
  let stopped = false;
  const stop = () => {
    stopped = true;
    tunnel?.kill('SIGTERM');
    if (app?.pid) {
      try { process.kill(-app.pid, 'SIGTERM'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    }
  };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  try {
    if (argv.includes('--start')) {
      if (process.platform === 'win32') throw new Error('--start 目前仅支持 macOS/Linux；Windows 请先启动应用，再单独运行 tunnel');
      if (!plan.appCommand) throw new Error('--start 需要配置 TUNNEL_APP_COMMAND_JSON');
      if (await isReady(plan.healthURL)) throw new Error('本地应用已运行，请使用 pnpm tunnel；不会重启已有进程');
      app = spawn(plan.appCommand[0], plan.appCommand.slice(1), { cwd: root, detached: true, env: { ...process.env, PORT: new URL(plan.target).port || (new URL(plan.target).protocol === 'https:' ? '443' : '80') }, stdio: ['ignore', 'inherit', 'inherit'] });
      let exited = false;
      app.once('error', () => { exited = true; });
      app.once('close', () => { exited = true; tunnel?.kill('SIGTERM'); });
      const deadline = Date.now() + 60_000;
      while (!await isReady(plan.healthURL)) {
        if (stopped || exited || Date.now() >= deadline) throw new Error('应用启动失败、超时或已取消');
        await new Promise(resolve => setTimeout(resolve, 250));
      }
    } else if (!await isReady(plan.healthURL)) throw new Error('应用尚未就绪，请先 pnpm start，或使用 pnpm start:tunnel');
    if (stopped) return;
    console.log(`正在为本地应用建立隧道：${plan.name}。退出仅停止本命令创建的进程。`);
    tunnel = spawn(plan.bin, plan.args, { cwd: plan.cwd, env: plan.env, stdio: 'inherit' });
    await new Promise((resolve, reject) => {
      tunnel.once('error', () => reject(new Error('tunnel-client 启动失败')));
      tunnel.once('close', code => code === 0 || stopped ? resolve() : reject(new Error(`tunnel-client 退出（${code}）`)));
    });
  } finally {
    stop();
    process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  runTunnel(root).catch(error => { console.error(error.message); process.exitCode = 1; });
}
