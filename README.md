# Rust WSS Tunnel

独立 Rust 实现的自托管 HTTP 隧道，使用 Cargo workspace 管理，运行时不依赖 Node.js 或 Tunnelmole。

```text
client/       客户端源码、环境配置、启动脚本
server/       服务端源码、Dockerfile、Compose、密钥初始化
protocol.rs   两端共用的消息协议与 HTTP 头处理
Cargo.lock    锁定依赖
```

本机客户端通过 WSS 连接服务器，获取一个公网 HTTPS 子域名。Traefik 负责 TLS 与域名路由，服务容器加入现有桥接网络，不新增宿主机端口。

## 服务端部署

需要 Docker Compose、Traefik v3（启用 Docker provider）和现有 Docker 网络。复制完整项目到服务器，Compose 的构建上下文是项目根目录。

```bash
cd server
./init.sh
# 编辑 .env，然后配置基础域名和泛域名 DNS
docker compose config --quiet
docker compose up -d --build
```

`.env` 示例：

```dotenv
TUNNEL_DOMAIN=tunnel.example.com
CONNECT_DOMAIN=connect.tunnel.example.com
TRAEFIK_NETWORK=traefik
TRAEFIK_ENTRYPOINT=websecure
TRAEFIK_CERTRESOLVER=letsencrypt
```

`TUNNEL_DOMAIN` 不带 `*.`。域名必须小写；`CONNECT_DOMAIN` 用于 WSS 接入。证书 resolver 必须已经配置 DNS-01，HTTP-01 无法签发泛域名证书。若已有证书由 Traefik 加载，删除 Compose 的 `tls.certresolver` 与 `tls.domains` 标签，保留 `tls: true`。

当前用户的域名可设置为 `TUNNEL_DOMAIN=demo.wlzxj.com`、`CONNECT_DOMAIN=connect.demo.wlzxj.com`。解析 `demo.wlzxj.com` 与 `*.demo.wlzxj.com` 到服务器，网络、入口和 resolver 名称按现有配置填写。

服务端只有一个容器内部端口 8080；公网连接和访问都经 Traefik 的 443。80 仍由现有 Traefik 管理。多套实例共用同一 Traefik 时，需要更换 Compose labels 中的 `rust-tunnel*` router/service 名称，避免冲突。

## 客户端

本机构建需要 Rust 1.98+。编译后仅需单个可执行文件和配置、密钥，不需要 Rust 或 Docker 运行时。

```bash
# 项目根目录
cargo build --release --locked -p tunnel-client
cd client
cp .env.example .env
mkdir -p secrets
chmod 700 secrets
# 从服务器 secrets/token 安全复制密钥到本机 client/secrets/token
# 编辑 .env 的 WSS 服务地址与密钥路径
chmod 600 secrets/token
```

```dotenv
TUNNEL_ENDPOINT=wss://connect.tunnel.example.com
TUNNEL_TOKEN_FILE=./secrets/token
```

使用：

```bash
./run.sh 3000              # 随机名称
./run.sh 3000 --name demo  # https://demo.tunnel.example.com
```

也可以直接运行二进制：

```bash
tunnel-client 3000 --server wss://connect.tunnel.example.com --token-file ./token --name demo
```

名称规则：1–63 位小写字母、数字或连字符，首尾不能是连字符。名称占用时返回 HTTP 409，连接入口名称禁止使用；不指定则随机生成。名称在会话结束后释放，不提供永久预留。异常断网通过心跳清理，通常最长约 60–80 秒；网络发送阻塞时还可能增加最多 10 秒。客户端断线会退出并报错，重新执行命令即可连接，不会静默更换 URL。

默认验证服务器证书，私有 CA 可以通过 `--ca-cert ca.pem` 指定；没有跳过证书验证的开关。密钥只从文件读取，通过加密 WSS 请求头发送。

## 支持范围

- HTTP 方法、路径与查询参数、状态码、二进制请求/响应、多值响应头（包括 Set-Cookie）。
- HTTP 重定向原样返回浏览器，不由客户端后台跟随。
- 指定或随机子域名、认证、并发请求、重名保护、心跳、请求超时与断线清理。
- 本地目标固定为 `http://127.0.0.1:指定端口`。本地程序若限制 Host，请允许 127.0.0.1；不需要给本地服务配置公网证书。
- 每个请求与响应正文最多 5 MiB，采用整包缓存；本地请求超时 30 秒，公网等待最多 35 秒；每条隧道最多 32 个在途请求，服务器最多 128 条连接。
- 本版本不支持业务 WebSocket/HMR、SSE、流式下载或任意 TCP/UDP。WSS 仅用于隧道传输；业务协议升级返回 501。
- 访问 URL 默认公开，认证 Token 保护隧道建立，不代替业务服务的登录认证。

## 开发与验证

```bash
cargo build --workspace --locked
cargo fmt --all --check
# 针对真实 WSS/HTTPS 的集成验证，需要本机 Docker、openssl、curl、Python 3
python3 tests/e2e.py
```

两端是同一 workspace 的子包，通过根目录 `protocol.rs` 共享协议；开发或 Docker 构建需保留完整项目，运行时客户端和服务端二进制可独立分发。集成测试使用临时目录和独立 Traefik 容器，不修改生产服务或本机信任库。验证状态见 `VALIDATION.md`。

## Actions、产物和版本发布

已配置 CI、跨平台二进制、GHCR 多架构服务端镜像及 tag 发布。使用说明见 [构建与发布](.github/RELEASING.md)。

- [Actions](https://github.com/ly-wo/wss-tunnel/actions)：PR/main 检查、Linux x64 构建产物。
- [Releases](https://github.com/ly-wo/wss-tunnel/releases)：`vX.Y.Z` 发布 Linux/macOS/Windows 二进制和 SHA256 校验文件。
- 镜像：`ghcr.io/ly-wo/wss-tunnel:dev` 为 main 开发版；正式 tag 发布后提供固定版本与 `latest`。

下载二进制后可直接执行 `tunnel-client 3000 --server wss://connect.example.com --token-file ./token --name demo`，无需安装 Rust。省略 `--name` 自动分配随机子域名。
