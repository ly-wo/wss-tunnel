import fs from 'node:fs';
import path from 'node:path';

/** Add project-level configuration only. Never overwrite credentials or existing commands. */
export function initProject(root) {
  const packageFile = path.join(root, 'package.json');
  if (!fs.existsSync(packageFile)) throw new Error('请在已有项目的根目录运行，当前目录缺少 package.json');
  const original = fs.readFileSync(packageFile, 'utf8');
  const pkg = JSON.parse(original);
  const commands = { tunnel: 'wss-tunnel', 'start:tunnel': 'wss-tunnel --start' };
  if (pkg.scripts !== undefined && (!pkg.scripts || typeof pkg.scripts !== 'object' || Array.isArray(pkg.scripts))) throw new Error('package.json 的 scripts 格式不正确');
  const scripts = { ...pkg.scripts };
  for (const [name, command] of Object.entries(commands)) {
    if (scripts[name] !== undefined && scripts[name] !== command) throw new Error(`scripts.${name} 已存在且不同；请先手动确认命令，初始化未修改文件`);
    scripts[name] = command;
  }
  const configFile = path.join(root, '.env.tunnel');
  const ignoreFile = path.join(root, '.gitignore');
  const ignore = fs.existsSync(ignoreFile) ? fs.readFileSync(ignoreFile, 'utf8') : '';
  const lines = new Set(ignore.split(/\r?\n/).map(line => line.trim()));
  const additions = ['.env.tunnel', '.local/tunnel/'].filter(rule => !lines.has(rule));
  if (additions.length) fs.writeFileSync(ignoreFile, ignore + (ignore && !ignore.endsWith('\n') ? '\n' : '') + additions.join('\n') + '\n');
  if (!fs.existsSync(configFile)) fs.writeFileSync(configFile, fs.readFileSync(new URL('./tunnel.env.example', import.meta.url)), { flag: 'wx', mode: 0o600 });
  if (JSON.stringify(pkg.scripts) !== JSON.stringify(scripts)) {
    pkg.scripts = scripts;
    const indent = original.match(/\n([\t ]+)"/)?.[1] ?? '  ';
    fs.writeFileSync(packageFile, JSON.stringify(pkg, null, indent) + '\n');
  }
}
