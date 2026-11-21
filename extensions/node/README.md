# Node 项目级 Tunnel 启动扩展

让 Vue、React、Node 等项目通过自身的 npm/pnpm 脚本启动 `tunnel-client`。只添加启动脚本、本地配置及 package scripts，**不修改业务源码，不在 Vue 中注入凭据或覆盖 Fetch**。

依赖 Node >= 22.13 和支持 `--access-basic-username` / `--access-bearer-token-file` 的 tunnel-client。此扩展可通过 npm 从 GitHub 安装；目前未发布到 npm registry，不会自动安装二进制或部署服务器。`--start` 的进程组清理支持 macOS/Linux；Windows 请自行启动应用后使用单独的 `tunnel` 命令。

## 推荐：通过 npm 安装

在业务项目根目录执行：

```sh
npm install -D git+https://github.com/ly-wo/wss-tunnel.git
npx wss-tunnel init
```

pnpm 对应 `pnpm add -D git+https://github.com/ly-wo/wss-tunnel.git` 和 `pnpm exec wss-tunnel init`。需要固定版本时，在 Git 地址末尾加 `#<commit-sha>`。

初始化命令会添加 `tunnel: wss-tunnel` / `start:tunnel: wss-tunnel --start` 两个 package scripts、创建权限为 600 的 `.env.tunnel` 模板，并追加 Git 忽略规则。已有配置不覆盖、已有业务 scripts 保留；同名命令有冲突时先报错且不修改文件。命令操作当前工作目录，不修改 node_modules 内的包或业务源码。

安装后仍需按下文准备 Rust 客户端与凭据文件，编辑 `.env.tunnel`；之后运行：

```sh
npm run tunnel -- --check
npm run tunnel
# 需要启动应用时（已配置 TUNNEL_APP_COMMAND_JSON）：
npm run start:tunnel
```

安装无需 npm registry 账号，因为来源是公开 GitHub 仓库。包元数据名为 `@ly-wo/wss-tunnel`，版本独立于 Rust 二进制；当前未发布到 npm registry，不能使用 `npm install @ly-wo/wss-tunnel`。包无运行时 npm 依赖、无安装钩子，不包含本机凭据或原生二进制。底层 `tunnel-client` 仍是必要运行条件。

## 可选：手工复制到自己的项目

从本仓库复制以下文件：

| 源文件 | 项目内位置 |
| --- | --- |
| `extensions/node/tunnel.mjs` | `scripts/tunnel.mjs` |
| `extensions/node/tunnel.env.example` | `tunnel.env.example` |

把 [package-scripts.json](./package-scripts.json) 中两个条目合并到已有 `package.json` 的 scripts，保留已有内容：

```json
{
  "scripts": {
    "tunnel": "node scripts/tunnel.mjs",
    "start:tunnel": "node scripts/tunnel.mjs --start"
  }
}
```

将 [gitignore.example](./gitignore.example) 的规则追加到项目 `.gitignore`。然后在该项目根目录执行：

```sh
mkdir -p .local/tunnel
cp tunnel.env.example .env.tunnel
chmod 700 .local/tunnel
chmod 600 .env.tunnel
```

将客户端放到 `.local/tunnel/tunnel-client`，或在配置里设置 `TUNNEL_CLIENT_BIN`。macOS/Linux 二进制需要可执行权限。旧版 Release 可能没有访问认证参数，启动器会检查并拒绝旧版；可在本仓库使用 `cargo build --release --locked -p tunnel-client` 构建，再复制 `target/release/tunnel-client`。不要把本机二进制提交到业务仓库。

编辑 `.env.tunnel`：将 `connect.example.com`、`your-account`、`my-app` 和本地端口替换为自己的值。用编辑器在 `.local/tunnel/access-password` 写入公网访问密码，仅保存密码本身，末尾换行允许；设置文件权限为 600。此扩展不会生成或打印密码。

## 启动已有应用的隧道

先用项目原来的方式启动本地服务，再执行：

```sh
pnpm tunnel --check
pnpm tunnel
# npm 项目对应 npm run tunnel -- --check / npm run tunnel
```

`--check` 验证配置和客户端版本，并报告本地健康状态，不连接公网。本地服务未就绪时仍会报告“配置检查通过、本地服务尚未就绪”；真正启动 tunnel 时必须健康检查通过。

客户端输出公网 URL。使用 Basic 时，浏览器访问该 URL 后弹出账号密码框，输入访问用户名和访问密码；服务器连接账号密码与访问密码相互独立。

- **连接服务器**：设置 `TUNNEL_USERNAME`，缺省由客户端在终端隐藏输入服务器登录密码；无人值守可配置 `TUNNEL_PASSWORD_FILE`。也可不设置 username，改用 `TUNNEL_TOKEN_FILE`。
- **访问公网应用**：配置 Basic 用户名和密码文件，或配置 `TUNNEL_ACCESS_BEARER_TOKEN_FILE`。两者同时配置时满足任一种即可。启动器要求至少配置一种访问认证。
- Basic 可用于首次加载网页；Bearer-only 适用于能发送 Authorization 的调用方，不能依靠尚未加载的 Vue 代码解决首次页面认证。

## 一条命令启动应用和隧道

在 `.env.tunnel` 配置应用启动命令和健康地址：

```dotenv
TUNNEL_TARGET=http://127.0.0.1:3000
TUNNEL_HEALTH_PATH=/health
TUNNEL_APP_COMMAND_JSON=["node","server.mjs"]
```

执行 `pnpm start:tunnel`：启动器运行该参数数组中的程序，等待健康接口返回 2xx，最多等待 60 秒，然后启动 tunnel。它不会通过 shell 解释命令，不支持 `&&`、管道或变量展开；需要复杂流程时自行编写启动脚本，并以 `node scripts/start.mjs` 等直接命令调用。

启动器将目标端口作为 `PORT` 传给应用；应用若不读取 PORT，需要在命令参数中明确指定相同端口。

纯 Vue/Vite 展示项目可先执行 `pnpm build`，再配置：

```dotenv
TUNNEL_TARGET=http://127.0.0.1:3000
TUNNEL_HEALTH_PATH=/
TUNNEL_APP_COMMAND_JSON=["node","node_modules/vite/bin/vite.js","preview","--host","127.0.0.1","--port","3000","--strictPort"]
```

这是静态构建预览，不包含业务 API，也不替代正式应用服务器。应用已有 Node 后端时，请使用后端自身的启动命令。

## 配置与退出行为

- `.env.tunnel` 位于业务项目根目录；`TUNNEL_PROJECT_CONFIG` 可指定另一配置文件。进程环境变量优先于配置文件；文件内不展开 `${VAR}`。
- `TUNNEL_CLIENT_BIN`、所有凭据/CA 文件路径均相对于业务项目根目录；客户端默认优先使用 `.local/tunnel/tunnel-client`，再查 PATH。Windows 本地文件名为 `tunnel-client.exe`。
- 本地目标仅支持回环 HTTP(S) 根地址，默认 `http://127.0.0.1:3000`。健康路径默认 `/`，必须为同源绝对路径、无副作用、无需认证且返回 2xx。健康检查使用 Node 的 TLS 信任配置；`TUNNEL_LOCAL_CA_CERT` 仅传给 Rust 客户端，自签名本地 HTTPS 的健康检查还需配置 Node 的信任 CA，例如启动时设置 `NODE_EXTRA_CA_CERTS`。
- `--start` 发现目标已健康运行时拒绝接管，提示使用 `pnpm tunnel`。退出时仅向本命令创建的客户端、应用进程组发送 SIGTERM，不停止既有应用。应用关闭会停止 tunnel，tunnel 关闭也会停止本次创建的应用。应用应正确响应 SIGTERM，不支持守护进程化后脱离进程组的程序。
- 客户端运行在 `.local/tunnel/runtime`，不自动读取业务项目的 `.env`；从环境中移除 `TUNNEL_*` 后使用已校验的显式参数，避免残留配置改变认证方式。不要在该 runtime 目录另建 `.env`。
- 访问凭据仅以文件路径传给客户端；不要把真实密码写进 `TUNNEL_APP_COMMAND_JSON`、源码、提交记录或截图。

## 能力边界

此扩展只负责编排现有客户端，不修改隧道协议或应用安全策略。当前 HTTP 正文 5 MiB 限制、完整缓冲及超时仍然存在，SSE 无法直接实时流式传输；应用的 Host/Origin 检查也不会自动放宽。开发服务的独立 HMR 端口不会自动转发。

因此，“应用就绪、隧道连接成功”不等于所有业务接口、实时事件和大文件已验收。需要这些能力时，应在隧道/网关层单独适配，不通过覆盖前端 Fetch 或取消业务安全检查来绕过。

## 验证

在 wss-tunnel 仓库根目录执行：

```sh
npm test
node --check extensions/node/tunnel.mjs
```

测试覆盖 Basic/Bearer 参数隔离、凭据不出现在参数中、健康地址和启动命令校验、旧客户端拒绝、启动失败传递、保留已有应用以及清理本次创建的进程。测试使用本地 HTTP fixture 和模拟客户端，不代表远端部署或公网认证验收。
