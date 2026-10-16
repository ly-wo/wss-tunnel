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
