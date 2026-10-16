# 验证结果

2026-10-16，本地完成：

- Rust workspace 构建、格式检查、Clippy（警告视为错误）通过。
- Compose 参数展开与脚本语法校验通过。
- 服务端 Docker release 镜像构建通过。
- 本机 Rust 客户端与 Rust 服务端验证通过。
- Rust 服务端镜像与 Traefik v3.7.14 置于同一 Docker bridge 网络，服务端不发布宿主机端口，非 root / 只读容器模式下验证通过。
- 实际 WSS / HTTPS、受信测试 CA，启用完整证书验证：随机域名、指定域名、重名 409、无效密钥 401、非法名称拒绝、16 个并发请求路由隔离、多值 Cookie、二进制 POST、状态码、重定向保留、超限请求 413、断线释放与重用名称、本地服务关闭 502，全部通过。

测试使用临时网络和容器，结束后已清理。生产 DNS、ACME 证书签发和用户公网服务器部署尚未执行。本次未开展独立安全审计或长期负载测试。

复跑（先在项目根目录执行 `cargo build --workspace --locked`）：

```bash
python3 tests/e2e.py
# 用容器版服务端复跑
docker build -f server/Dockerfile -t rust-wss:test .
TUNNEL_TEST_IMAGE=rust-wss:test python3 tests/e2e.py
```

测试需要 Docker、openssl、curl、Python 3，占用本机 18300、18301、18443；非容器服务端模式另占用 18000。测试不会更改系统证书信任库。

## WS(S) / HTTP(S) 扩展验证

2026-10-16，本地完成，未部署公网：

- Rust 格式、Clippy（`-D warnings`）、2 个 URL 解析与目标路径测试通过。
- 最新服务端 Docker 镜像与 Traefik 在独立 bridge 网络内运行；四种服务器地址写法 × 四种本机目标协议共 16 种组合通过，每种均验证公网 HTTP、HTTPS、WS、WSS。
- WebSocket 主动推送、Unicode 文本、二进制、子协议、Ping/Pong、关闭码与原因、并发连接及隧道断线清理通过；2 MiB 控制字符文本往返通过。
- 隧道服务器和本机 HTTPS 的不受信证书均被拒绝。
- 使用同一容器镜像重跑 `tests/e2e.py`，原有 HTTP 回归全部通过。
- Makefile 帮助、客户端命令展开及可选 HTTP Compose 合并校验通过。未触发 tag 或发布 Action。

复跑协议测试：安装 `tests/requirements-protocols.txt`，然后执行 `make protocols-test PYTHON=<环境中的 python>`；设置 `TUNNEL_TEST_IMAGE=<服务端镜像>` 可验证容器服务端。测试占用本机 18580、18543、18800、18843；本机服务端模式另占用 18090。未验证具体框架的 HMR 配置或长期高负载。


## 管理后台与详细审计（2026-10-16，当前源码）

- `tests/admin.py`：旧库保留记录并升级、显式管理员初始化及重复保护、普通用户/连接 Token 无法管理、默认和个人名额动态生效、降低上限后重连、停用撤销会话及隧道、设置重启持久化、可信代理 IP 与伪造转发头、筛选/游标、修改前后值、敏感字段排除和保留期检查通过。包含 Compose 空可信代理环境值回归。
- `tests/accounts.py`：原账号、Token、并发名额及审计回归通过。Rust 测试、Clippy、格式检查、前端语法与 Compose 源码构建配置检查通过。
- 浏览器实际验证管理员登录、默认名额保存、用户详情、域名状态、审计过滤与修改前后值显示；使用临时本地数据库。
- 这些是本地验证，不代表已推送、发布或部署到公网服务器。正式 `0.1.0` 镜像仍不包含账号/管理后台功能。
- 最终镜像 `wss-tunnel:admin-test`（`sha256:2b59deb7d6f53ac5c9744ca05627ca7452b594a47db2d4fcab0ff58989f66594`）构建成功；真实 Docker bridge + Traefik HTTPS/WSS 回归通过。只读根目录、UID 10001、空代理环境、运行中容器 `exec` 初始化管理员、后台 API 与重启持久化检查通过。
