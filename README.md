# Rust WSS Tunnel

独立 Rust 实现的自托管 HTTP(S) / WebSocket 隧道，使用 Cargo workspace 管理，运行时不依赖 Node.js 或 Tunnelmole。

```text
client/       客户端源码、环境配置、启动脚本
server/       服务端源码、Dockerfile、Compose、密钥初始化
protocol.rs   两端共用的消息协议与 HTTP 头处理
Cargo.lock    锁定依赖
```

本机客户端通过 WS 或 WSS 连接服务器，获取一个公网子域名；默认使用 WSS 和 HTTPS。Traefik 负责 TLS 与域名路由，服务容器加入现有桥接网络，不新增宿主机端口。

> 账号管理、管理后台、可配置名额和详细审计从 `0.2.0` 提供；旧 `0.1.0` 镜像不包含这些能力。二进制及镜像是否已生成以对应 Release 流程结果为准；发布完成前可使用下文的源码构建命令。

## 服务端部署

需要 Docker Compose、Traefik v3（启用 Docker provider）和现有 Docker 网络。默认拉取已发布的服务端镜像，无需在服务器编译 Rust。使用与服务端相同版本的客户端。

```bash
cd server
./init.sh
# 编辑 .env，然后配置基础域名和泛域名 DNS
docker compose config --quiet
# 私有镜像需先 docker login ghcr.io（账号需有 read:packages 权限）
docker compose pull
docker compose up -d
```

默认镜像为 `ghcr.io/ly-wo/wss-tunnel:0.2.3`，可通过 `.env` 的 `TUNNEL_IMAGE` 更改。源码构建需保留完整项目，在 `server` 目录执行 `docker compose -f compose.yaml -f compose.build.yaml up -d --build`。

`.env` 示例：

```dotenv
TUNNEL_DOMAIN=tunnel.example.com
CONNECT_DOMAIN=connect.tunnel.example.com
TRAEFIK_NETWORK=traefik
TRAEFIK_ENTRYPOINT=websecure
TRAEFIK_CERTRESOLVER=letsencrypt
```

`TUNNEL_DOMAIN` 不带 `*.`。域名必须小写；`CONNECT_DOMAIN` 用于隧道接入。证书 resolver 必须已经配置 DNS-01，HTTP-01 无法签发泛域名证书。若已有证书由 Traefik 加载，删除 Compose 的 `tls.certresolver` 与 `tls.domains` 标签，保留 `tls: true`。

当前用户的域名可设置为 `TUNNEL_DOMAIN=demo.wlzxj.com`、`CONNECT_DOMAIN=connect.demo.wlzxj.com`。解析 `demo.wlzxj.com` 与 `*.demo.wlzxj.com` 到服务器，网络、入口和 resolver 名称按现有配置填写。

服务端只有一个容器内部端口 8080；默认公网连接和访问都经 Traefik 的 443。80 入口可按下文额外启用。多套实例共用同一 Traefik 时，需要更换 Compose labels 中的 `rust-tunnel*` router/service 名称，避免冲突。

## 账号、Token、子域名与审计

在 `server` 目录启用当前源码版本：

```bash
./init.sh
# 编辑 .env 中的域名、Traefik 网络和证书配置
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

访问 `https://connect.你的基础域名/`（本项目域名示例为 `https://connect.demo.wlzxj.com/`），注册、登录后管理 Token、子域名和审计记录。用户名为 3–32 位字母、数字、下划线或连字符，不区分大小写；密码为 12–128 字节。首次初始化默认允许注册，可在 `.env` 设置 `TUNNEL_ALLOW_REGISTRATION=false` 作为初始值。数据库建立后以管理后台的持久配置为准，修改环境变量不会覆盖它；关闭注册不影响已有账号登录。

**一个账号一个连接 Token。** 首次登录后点击创建，明文仅本次返回；重置将立即使旧 Token 无法建立新连接，已有旧 Token 隧道通常在 20 秒内断开，发送阻塞时可能再增加最多 10 秒。账号密码建立的连接不受 Token 重置影响。连接 Token 不允许管理账号；网页登录会话独立于连接 Token，12 小时到期，仅保存在页面内存中，刷新需重新登录。退出网页不会断开已有隧道。

客户端重新编译后可任选一种方式：

```bash
# 账号密码：运行时隐藏输入密码，不把密码放在命令行里
cargo run --release --locked -p tunnel-client -- 3000 \
  --server wss://connect.demo.wlzxj.com --username alice --name demo

# Token：把页面创建的 Token 写入权限为 600 的文件
cargo run --release --locked -p tunnel-client -- 3000 \
  --server wss://connect.demo.wlzxj.com --token-file ./token --name demo
```

无人值守可使用 `--username alice --password-file ./password`，或 `TUNNEL_USERNAME` / `TUNNEL_PASSWORD_FILE`。显式账号模式优先于 `.env` 的 `TUNNEL_TOKEN_FILE`。密码连接默认要求 WSS；`--allow-insecure-auth` 只供本机明文测试。

每个账号默认最多保留 **10 个子域名**（管理后台可调整全局默认或单账号名额），在线与离线合计；断开不会释放归属，其他账号不能接管。省略 `--name` 每次申请一个随机名称，也占用名额；达到上限后复用已保留名称或删除离线名称。在线名称禁止删除。页面显示域名、在线/离线状态、最近连接时间，点击刷新获取最新状态；重启服务器后状态根据实际连接重新计算。在线表示客户端隧道已连接，不代表本机业务服务已通过健康检查。

审计记录注册、密码登录成功/失败、Token 创建/重置、Token 认证、隧道连接/断开、子域名申请拒绝和删除。用户只能查看自己的最近 100 条记录；数据库默认保留 90 天（后台可调整），启动及新增事件时清理过期记录。记录不包含密码、完整 Token 或业务请求正文；这不是逐条业务 HTTP 请求的访问日志。异常退出无法写入断开事件，但重启后域名不会误显示为在线。

数据保存在 Compose 命名卷 `tunnel_data` 的 `/data/auth.sqlite3`；密码使用 Argon2id 哈希，连接 Token 和登录会话只存 SHA-256 摘要。重建容器保留账号、名称归属和审计；不要用 `docker compose down -v` 删除数据卷。备份应使用 SQLite 在线备份或停服后备份整个数据卷（包含 WAL），不能仅复制正在写入的主数据库。当前仅支持单服务实例部署。

旧共享 Token 仅保留作迁移兼容，新 Compose 默认 `TUNNEL_ENABLE_LEGACY_TOKEN=false`。若临时开启，它不属于任何账号，不受账号的 10 个域名上限约束；仍不能抢占账号已保留的名称。原生进程显式提供 `--token-file` 时默认启用旧方式，可传 `--enable-legacy-token false` 关闭。

账号认证有独立的基础保护：默认全服务每分钟最多 10 次注册尝试、60 次密码登录尝试（后台可调整），最多同时进行 2 个密码哈希操作，超限返回 429。其他业务限流仍可在 Traefik 配置。

API 位于连接域名下的 `/_tunnel/api/`：`POST register`、`POST login`，登录后以返回的 `session` 作为 Bearer 凭证调用 `GET me`、`POST token`（创建/重置）、`GET domains`、`DELETE domains/{name}`、`GET audit`、`POST logout`。POST 使用 JSON；Token 创建/重置传 `{}`。会话与连接 Token 均不接受放在 URL 中。

验证入口：`cargo build --workspace --locked && python3 -u tests/accounts.py`。该测试使用临时数据库和本机端口，覆盖实际客户端连接、Token 失效、账号隔离、并发名额竞争、重启持久化与审计；`tests/e2e.py` 另外验证真实 Traefik HTTPS/WSS 账号连接。


## 管理后台（当前源码）

入口为 `https://connect.demo.wlzxj.com/_tunnel/admin`，与个人账号页面分开。先从源码构建服务；正式 `0.1.0` 镜像尚未包含这项功能。

在 `server/` 目录显式创建首个管理员，终端会隐藏输入密码：

```bash
docker compose -f compose.yaml -f compose.build.yaml up -d --build
docker compose exec tunnel tunnel-server --bootstrap-admin admin --bootstrap-only
```

普通注册账号不会自动成为管理员。若指定用户名已经存在，必须输入该账号原密码才可提升；已有管理员时禁止再次初始化。自动化可使用 `--bootstrap-password-file /容器内密码文件`，不要将密码放入命令行。此命令需要连接域名和基础域名配置，Compose 已通过环境变量提供。

后台包含：

- **配置**：注册开关、每账号默认名额（初始 10，范围 0–1000）、审计保留天数（1–3650）、全站每分钟注册与密码尝试次数（1–10000）。保存立即生效并持久化。缩短保留期会清理超期记录。
- **用户**：搜索、分页，查看注册时间/IP、最近密码登录时间/IP、最近活动 IP/时间、客户端 User-Agent、认证方式和 Token 前缀；查看所属域名在线状态，设置个人名额或恢复全局默认，停用/启用账号。管理员不能停用自己。停用会立即撤销账号会话，阻止新连接，并主动断开该账号全部密码/Token 隧道及其已建立的 WebSocket 转发，终止等待中的 HTTP 请求；不再等待心跳检查。正在握手的隧道也会被撤销。其他账号不受影响。
- **审计**：按用户名、来源 IP、事件、结果及时间范围过滤，游标翻页。包含账号事件、管理操作及连接入口/API 请求的时间、请求 ID、来源 IP、直连 IP、认证方式、方法/路径、User-Agent；接口请求还记录 HTTP 状态、耗时和可用的失败原因。隧道生命周期与握手通过请求 ID 关联，配置和用户设置修改记录前后值。旧记录缺少 IP 时显示空值，不推测补齐。

降低名额不会删除已有域名，已持有名称仍可重连；新增名称受新上限约束，名额为 0 表示禁止新申请。离线域名仍占名额。禁用后重新启用不会恢复旧网页登录会话或已撤销的连接；客户端必须重新连接。

### Traefik 后的真实 IP

服务默认仅记录直接 TCP 连接的 IP，不采信转发头。在 `server/.env` 填写**实际 Traefik 容器的 IP/CIDR**，例如：

```dotenv
# 示例地址，必须换成实际代理地址；多个地址以逗号分隔
TUNNEL_TRUSTED_PROXIES=172.20.0.2/32
```

重新创建服务容器后生效。可用 `docker inspect <Traefik容器名>` 查看它在共享网络中的地址。优先固定代理地址并只信任该地址；不要信任所有来源，也不要把可运行不可信容器的整个桥接网络设为可信。Traefik 自身若位于 CDN/其他代理之后，也应正确限制它的可信转发来源。

仅当直接连接来源可信时解析 `X-Forwarded-For`，从右向左找到首个不可信跳点；格式非法或链路过长则回退到直连 IP。管理后台同时显示实际使用的来源 IP 和直连 IP，便于核对。IP 不代表已验证的用户身份，不自动推断地理位置、设备型号或真实姓名。

配置步骤（在公网服务器执行）：

```bash
# 找到 Traefik 在与 tunnel 共享的网络中的 IP
docker inspect <Traefik容器名> --format '{{range $name, $net := .NetworkSettings.Networks}}{{$name}}: {{$net.IPAddress}}{{println}}{{end}}'
# 将这个 IP 写入 server/.env 的 TUNNEL_TRUSTED_PROXIES，例如 172.20.0.2/32
cd server
docker compose up -d --force-recreate tunnel
```

修改环境变量后需要重新创建容器，单纯 restart 不会加载新的 Compose 环境变量。重新登录或连接后，检查新审计的来源是否为 `trusted_proxy`、直连 IP 是否为 Traefik；旧记录不会回填。Traefik 前面若还有 CDN/LB，需要在 Traefik 的 `forwardedHeaders.trustedIPs` 中信任实际上游，并在隧道服务中配置链路上的可信代理地址，不能开启无条件信任所有来源。

**本地服务的访问者 IP**：服务端对普通 HTTP 和 WebSocket 握手统一生成 `X-Forwarded-For`、`X-Real-IP` 和 `Forwarded`，使用与审计相同规则解析出的 IP，覆盖请求中原有的这三个头。`X-Forwarded-For` 为单个已解析地址，不保留原始代理链。未配置可信代理时，它们仍是直连 IP，不能凭转发头恢复来源。其他供应商专用 IP 头不属于本项目验证结果，本地应用应使用上述标准头。

本地应用的 TCP 对端仍是隧道客户端（常见为 `127.0.0.1` 或容器 IP）。需要在应用框架或本地反向代理中，仅信任隧道客户端的实际连接地址并启用转发头解析，才能让框架的请求 IP 显示访问者地址；仅查看 socket/`REMOTE_ADDR` 不会自动改变。不要让本地服务同时接受不可信来源伪造这些头。用户登录审计记录的是登录者 IP，业务请求转发的是该次访问者 IP，两者不一定属于同一人。

审计不保存认证头、密码、完整 Token、查询参数、请求正文或转发业务内容。它是存于本地 SQLite 的操作记录，不是防篡改日志或逐条业务流量日志。数据库访问者可修改记录；长期外部归档需另行接入。

验证：`make admin-test` 覆盖管理员隔离、旧库迁移、动态名额、停用撤销、可信代理与伪造 IP、审计筛选及持久化。`make revoke-test PYTHON=<已安装 tests/requirements-protocols.txt 的 Python>` 验证同账号密码/Token 隧道、已有 WebSocket 两端、在途 HTTP 在 2 秒断言窗口内终止，其他账号保持可用，重新启用只允许新连接。
`make real-ip-test PYTHON=<已安装 tests/requirements-protocols.txt 的 Python>` 使用隔离的 Traefik 容器和启用证书校验的 HTTPS/WSS，检查可信/不可信代理下，用户和审计 IP、HTTP 转发头、WebSocket 握手头一致，并检查伪造 IP 头被覆盖。Docker Desktop 下测到的入口 IP 可能来自虚拟机/NAT，此测试不能代替公网来源 IP 验证。

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

名称规则：1–63 位小写字母、数字或连字符，首尾不能是连字符。名称占用时返回 HTTP 409，连接入口名称禁止使用；不指定则随机生成。账号连接的名称在会话结束后保留为离线状态，归属不变；账号默认最多保留 10 个名称（可由管理员调整），在线与离线合计。已离线名称可在管理页面删除。异常断网通过心跳清理，通常最长约 60–80 秒；网络发送阻塞时还可能增加最多 10 秒。客户端断线会退出并报错，重新执行命令即可连接，不会静默更换 URL。

### 服务器地址与本机目标

`--server` 接受 `ws://`、`wss://`、`http://`、`https://`，可指定端口。`http://` 是 `ws://` 的地址别名，`https://` 是 `wss://` 的地址别名：连接时仍使用 WebSocket Upgrade 建立双向隧道，不是 HTTP 轮询。地址可以省略路径或使用 `/_tunnel/ws`。

```bash
# HTTP 地址建立 WS 隧道；本机仍可使用端口简写
tunnel-client 3000 --server http://connect.example.com --token-file ./token

# HTTPS 本机服务，通过 WSS 隧道访问
tunnel-client --target https://localhost:8443 --server https://connect.example.com --token-file ./token --name demo

# WebSocket 本机服务
tunnel-client --target ws://127.0.0.1:3000 --server wss://connect.example.com --token-file ./token
```

`--target`（或 `TUNNEL_TARGET`）接受四种协议，与位置参数端口二选一。普通请求使用 HTTP(S)，收到 WebSocket Upgrade 时使用 WS(S)；`http`/`ws` 选择明文本机连接，`https`/`wss` 选择 TLS。本机服务自身需要实现对应协议。目标可带路径前缀，例如 `https://localhost:8443/base` 会把公网 `/chat?q=1` 转到 `/base/chat?q=1`。Host 使用目标主机，Origin 原样转发；本机服务若有 Host/Origin 校验，需要允许相应地址。

两段 TLS 分别校验证书：`--ca-cert ca.pem` 信任隧道服务器的私有 CA，`--local-ca-cert local-ca.pem` 信任本机 HTTPS/WSS 的私有 CA。没有跳过校验的开关。密钥只从文件读取并放入隧道请求头；使用 WS/HTTP 接入时链路未加密。

### 可选：Traefik 80 入口

默认 Compose 保留 HTTPS/WSS。需要同时启用 HTTP/WS 时，在 `server` 目录使用：

```bash
docker compose -f compose.yaml -f compose.http.yaml config --quiet
docker compose -f compose.yaml -f compose.http.yaml up -d --pull always
```

可设置 `TRAEFIK_HTTP_ENTRYPOINT=web`，容器仍使用同一 bridge 网络和内部 8080，不占用额外宿主机端口。Traefik 若配置了全局 HTTP→HTTPS 跳转，需要对这些域名取消或豁免该跳转；客户端不自动跟随 WebSocket 握手重定向。可以通过 80 建立 WS 隧道，同时通过 443 访问 HTTPS/WSS 服务。

服务端 `TUNNEL_PUBLIC_SCHEME` 默认为 `https`，仅控制客户端打印的公网 URL；需要展示 HTTP URL 时设为 `http`。实际开放哪些协议由 Traefik 入口决定。

## 支持范围

- HTTP 方法、路径与查询参数、状态码、二进制请求/响应、多值响应头（包括 Set-Cookie）。
- HTTP 重定向原样返回浏览器，不由客户端后台跟随。
- 指定或随机子域名、认证、并发请求、重名保护、心跳、请求超时与断线清理。
- 本机 HTTP/HTTPS 与 WebSocket/WSS；保留 `3000` 端口简写，等同于 `--target http://127.0.0.1:3000`。
- WebSocket 双向文本/二进制消息、子协议协商、关闭码与原因；Ping/Pong 在各段连接本地处理。HMR 所需的 WebSocket 通道已支持，具体开发框架的公网地址和 Origin 配置需单独确认。
- HTTP 请求/响应正文与单条 WebSocket 消息最多 5 MiB，均按完整正文或消息缓存；本地 HTTP 请求超时 30 秒，公网等待最多 35 秒；每条隧道的在途 HTTP 请求和活动 WebSocket 合计最多 32 个，服务器最多 128 条隧道。拥塞或慢消费者可能导致对应 WebSocket 关闭。
- 不支持 SSE、HTTP 流式下载或任意 TCP/UDP。客户端和服务端应一起升级，旧版本客户端不识别新增的 WebSocket 消息协议。
- 访问 URL 默认公开，认证 Token 保护隧道建立，不代替业务服务的登录认证。

## 开发与验证

```bash
make help
make build
make check
# 针对真实 WSS/HTTPS 的集成验证，需要本机 Docker、openssl、curl、Python 3
make e2e

# 四种服务器地址 × 四种本机目标协议，验证公网 HTTP(S) 与 WS(S)
python3 -m venv .venv
.venv/bin/pip install -r tests/requirements-protocols.txt
make protocols-test PYTHON=.venv/bin/python
# 使用已构建的服务端镜像验证 bridge 网络
TUNNEL_TEST_IMAGE=wss-tunnel:local make protocols-test PYTHON=.venv/bin/python
```

Makefile 也提供 `make release`、`make docker-build`、`make up`、`make up-image`、`make logs`、`make package`；`make client PORT=3000 NAME=demo` 或 `make client LOCAL_TARGET=https://localhost:8443` 可启动客户端。发布入口为 `make tag VERSION=0.2.3` 与 `make publish TAG=v0.2.3`，会实际触发对应 GitHub Action。

两端是同一 workspace 的子包，通过根目录 `protocol.rs` 共享协议；开发或 Docker 构建需保留完整项目，运行时客户端和服务端二进制可独立分发。集成测试使用临时目录和独立 Traefik 容器，不修改生产服务或本机信任库。验证状态见 `VALIDATION.md`。

## Actions、产物和版本发布

已配置 CI、跨平台二进制、GHCR 多架构服务端镜像及 tag 发布。使用说明见 [构建与发布](.github/RELEASING.md)。

- [Actions](https://github.com/ly-wo/wss-tunnel/actions)：PR/main 检查、Linux x64 构建产物。
- [Releases](https://github.com/ly-wo/wss-tunnel/releases)：`vX.Y.Z` 发布 Linux/macOS/Windows 二进制和 SHA256 校验文件。
- 镜像：`ghcr.io/ly-wo/wss-tunnel:dev` 为 main 开发版；正式 tag 发布后提供固定版本与 `latest`。

下载二进制后可直接执行 `tunnel-client 3000 --server wss://connect.example.com --token-file ./token --name demo`，无需安装 Rust。省略 `--name` 自动分配随机子域名。

## Android 客户端

`android/` 为独立 Gradle 工程（`com.wlzxj.tunnel`，versionName `0.2.3`）。隧道转发复用 `tunnel-client` 库与 `client/jni` 中的 `libtunnel_client_jni.so`，界面为 Jetpack Compose，账号 API 与桌面网页一致。

### 构建环境（通用）

| 组件 | 版本 / 说明 |
| --- | --- |
| JDK | 17（Temurin 等均可） |
| Android SDK | Platform **36**、Build-Tools **36.x**（`sdkmanager` 安装） |
| Android NDK | **28.2.13676358** |
| Rust | 仓库 `rust-toolchain.toml` 指定版本；`rustup target add aarch64-linux-android` |
| cargo-ndk | `cargo install cargo-ndk --version 4.1.2 --locked` |

将 SDK/NDK 加入环境变量（路径按本机安装位置替换）：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/sdk          # 部分工具亦认 ANDROID_SDK_ROOT
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

可选：单独预构建 JNI（输出到 `android/app/src/main/jniLibs/`）：

```bash
bash scripts/build-android-jni.sh
```

**Gradle 的 `preBuild` 会自动执行 `buildJni`**，一般只需在 `android/` 目录运行：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（默认 **arm64-v8a**）。

**x86_64 模拟器库：** 在同一 Gradle 命令上加 `-PincludeX86=true`（会设置 `BUILD_ANDROID_X86_64=1` 并编入 `x86_64` ABI）。切换 arm64 ↔ x86 构建时 Gradle 会按 `includeX86` 输入重新跑 JNI，避免误用 UP-TO-DATE 的旧库。

### 安装与运行

- Debug 包：`applicationId` 为 `com.wlzxj.tunnel.debug`，可直接 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- **Release 构建**默认要求 **HTTPS** 接入地址；校验服务器证书和域名。
- **Debug 构建**可在连接页勾选「允许本地 HTTP 接入（127.0.0.1 / 10.0.2.2）」：仅对回环/模拟器地址允许明文 HTTP/WS，用于本机或模拟器联调；HTTPS/WSS 仍校验证书，Release 不提供此开关。
- **账号与连接**：在账号页登录后，连接页默认「使用当前账号」，展示同一用户名与服务器；开始连接会先校验目标地址，再调用账号 `me` 校验会话，然后用本次登录密码走隧道 Basic 认证（密码仅保留在进程内存，不落盘）。仍可显式选择「连接 Token」或「手动账号密码」；账号页创建 Token 后「填入连接页」会切换到手动 Token 模式（不会被登录默认项覆盖）。**退出登录**、会话到期（`expires_at` 定时任务 + 回到前台时复查）或会话失效会清理内存绑定，并通过前台服务按归属 **仅停止当前账号关联的隧道**；**手动 Token / 手动密码**建立的隧道不会因退出账号而断开，仍可在通知栏停止。

### 仪器测试（fixture）

`connectedDebugAndroidTest` 中账号 API、真实隧道转发、Compose 三 Tab 等用例默认连本机 fixture：

| 参数 | 默认 |
| --- | --- |
| `tunnel_origin` | `http://127.0.0.1:19092`（连接/账号 API） |
| `tunnel_target` | `http://127.0.0.1:19093`（隧道后端，`/hello.txt` 返回固定正文） |

**真实依赖（非 mock）：**

1. 在开发机上运行与仓库协议兼容的隧道 **服务端 + 目标 HTTP**（监听上述端口；目标 `/hello.txt` 正文须包含 `Android JNI forwarding verified`）。
2. 模拟器/设备上：`adb reverse tcp:19092 tcp:19092` 与 `adb reverse tcp:19093 tcp:19093`，使应用内 `127.0.0.1` 指回主机。
3. Fixture 必须开放注册（测试会创建随机账号），且连接域名应与接入地址匹配，例如 `--connect-domain 127.0.0.1`。
4. 隧道 HTTP 断言经 **origin + `Host: <公网子域>`** 访问，不依赖 `*.tunnel.test` 公网 DNS。

```bash
cd android
adb reverse tcp:19092 tcp:19092
adb reverse tcp:19093 tcp:19093
./gradlew connectedDebugAndroidTest
# 多设备时：ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest
```

可在 `android/app/build.gradle.kts` 的 `testInstrumentationRunnerArguments` 或 Gradle 传参覆盖 `tunnel_origin` / `tunnel_target`。

**近期本机验证（仅供参考）：** API 36 模拟器上 **7/7 单元测试、10/10 设备测试通过，Debug APK 构建成功**（含 Compose 三 Tab、Token/密码+指定子域名联调、服务代际与 JNI 清理）。API 37 测试版模拟器上也验证过包含 Compose 交互的 9 项设备用例。详细边界见 [Android 验证记录](docs/android-validation.md)。

Android Actions：main 的相关源码变更、PR 和手动运行会构建 ARM64 Debug APK，执行单元测试，并上传 `wss-tunnel-android-debug`（APK + SHA256）及测试报告，保留14天。在 [Android Actions](https://github.com/ly-wo/wss-tunnel/actions/workflows/android.yml) 中打开成功的运行，从 Artifacts 下载。

后续 `vX.Y.Z` Release 也会构建安卓端，将 APK 和校验文件附到 Release，并加入总校验清单。当前提供 Debug 测试包，使用构建环境的调试签名；不同构建的签名可能不同，覆盖安装失败时需卸载旧包（会清除应用数据）。正式签名与商店发布尚未配置。
