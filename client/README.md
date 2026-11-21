# Rust 客户端

在项目根目录运行 `make release`，然后在本目录复制 `.env.example` 为 `.env`，填写服务器地址和密钥文件路径。服务器地址支持 `ws://`、`wss://`、`http://`、`https://`；后两者分别以 WS、WSS 建立隧道。

```bash
./run.sh 3000             # 本机 http://127.0.0.1:3000，随机子域名
./run.sh 3000 --name demo # 指定子域名
./run.sh --target https://localhost:8443
./run.sh --target ws://localhost:3000
./run.sh --target wss://localhost:8443 --local-ca-cert ./local-ca.pem
```

`--target` 与端口二选一，也可使用 `TUNNEL_TARGET` 环境变量。目标同时接收普通 HTTP(S) 请求和 WebSocket 升级请求。`--ca-cert` 配置隧道服务器 CA，`--local-ca-cert` 配置本机服务 CA，两者独立。

完整说明见 [项目 README](../README.md)。

## 账号密码连接（当前源码）

先在服务端连接域名的网页注册账号，再使用重新构建的客户端：

```bash
tunnel-client 3000 --server wss://connect.example.com --username alice --name demo
```

密码以隐藏输入方式读取；无人值守可加 `--password-file ./password`（文件权限建议 600）。账号模式优先于 `.env` 中的 Token 文件。也可在网页创建唯一连接 Token，继续使用 `--token-file ./token`。重置 Token 会使旧 Token 失效。

每个账号最多保留 10 个子域名，包括离线名称。省略 `--name` 会申请新的随机名称；日常使用建议指定已保留的名称。

## 公网域名访问认证

新版客户端可在转发到本地服务之前校验访问者的 `Authorization`。这与 `--username` / `--token-file` 的隧道连接认证相互独立；不配置以下参数时保持原有公开访问行为。适用于该客户端所连接域名的全部 HTTP 路径和 WebSocket 握手，无需升级服务端。

```bash
# 文件只存密码/Token 本文，不加 Basic/Bearer 前缀；建议 chmod 600。
# 浏览器访问会收到 Basic 登录提示。
tunnel-client --target http://127.0.0.1:5199 \
  --server https://connect.wlzxj.com --name delivery --username lywowl \
  --access-basic-username visitor --access-basic-password-file ./access-password

# API 客户端通过 Authorization: Bearer <token> 访问。
tunnel-client --target http://127.0.0.1:5199 \
  --server https://connect.wlzxj.com --name delivery --username lywowl \
  --access-bearer-token-file ./access-token
```

环境变量对应 `TUNNEL_ACCESS_BASIC_USERNAME`、`TUNNEL_ACCESS_BASIC_PASSWORD_FILE`、`TUNNEL_ACCESS_BEARER_TOKEN_FILE`。Basic 用户名和密码文件必须一起配置，密码不能为空；Bearer 文件须为非空合法 token。文件末尾的换行会被移除，启动时读取一次，修改后需重启客户端。

可同时设置两种方式，任一种认证通过即可访问。缺少、错误或重复的 Authorization 返回 401，不连接本地服务；HTTP 响应携带 `WWW-Authenticate` 和 `Cache-Control: no-store`。认证通过后移除 Authorization，避免域名访问凭据泄露给本地服务，因此不能用同一个 Authorization 同时传递后端业务凭据。未启用访问认证时，原有业务 Authorization 仍照常透传。

公网访问与隧道连接都应使用 HTTPS/WSS。此功能由客户端执行，不是服务端账号/域名 ACL；更换为未配置访问认证的客户端会恢复公开访问。现有服务端会保留 WebSocket 拒绝的 401 状态，但不转发其认证挑战头；浏览器 WebSocket API 不能自行设置 Bearer 请求头，需使用能够发送 Authorization 的客户端，Basic 的浏览器缓存行为取决于浏览器。此功能不修改 HMR 端口或正文大小限制。
