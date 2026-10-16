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
