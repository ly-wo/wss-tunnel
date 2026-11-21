#!/usr/bin/env node
import path from 'node:path';
import { runTunnel } from './tunnel.mjs';
import { initProject } from './init.mjs';

const args = process.argv.slice(2);
const root = process.cwd();
try {
  if (args.length === 1 && args[0] === 'init') {
    initProject(root);
    console.log('项目启动配置已就绪。请编辑 .env.tunnel 并准备访问凭据文件，然后运行 npm run tunnel -- --check。');
  } else if (args.length === 1 && ['--help', '-h'].includes(args[0])) {
    console.log('wss-tunnel init       初始化当前项目（保留已有配置）\nwss-tunnel --check    检查客户端、配置和本地服务\nwss-tunnel            为已运行的应用建立隧道\nwss-tunnel --start    启动应用后建立隧道\n底层需要支持 Basic/Bearer 的 tunnel-client，可通过 TUNNEL_CLIENT_BIN 指定。');
  } else {
    await runTunnel(path.resolve(root), args);
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
