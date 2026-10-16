# Rust 服务端

在本目录执行：

```bash
./init.sh
# 编辑 .env，确认 Traefik 网络、入口与 DNS 证书 resolver
# 私有镜像需先 docker login ghcr.io（账号需有 read:packages 权限）
docker compose pull
docker compose up -d
```

容器加入现有 Traefik bridge 网络，端口 8080 只在 Docker 网络内使用。默认 Traefik 443 同时承载 WSS 隧道和公网 HTTPS/WSS 访问。

需要额外开放 HTTP/WS 时：

```bash
docker compose -f compose.yaml -f compose.http.yaml up -d --pull always
```

HTTP entryPoint 默认 `web`，可通过 `TRAEFIK_HTTP_ENTRYPOINT` 更改；现有 Traefik 的全局 HTTPS 跳转需针对这些域名取消或豁免。WS 链路不加密。`TUNNEL_PUBLIC_SCHEME=http` 只修改客户端展示的 URL，入口仍由 Traefik 配置决定。

默认使用 `ghcr.io/ly-wo/wss-tunnel:0.2.0`，可通过 `.env` 中的 `TUNNEL_IMAGE` 指定其他版本。客户端应使用对应版本的 Release 产物。升级时修改镜像版本，再执行 `docker compose pull && docker compose up -d`。

从源码构建时保留完整项目，构建上下文是上一级目录：

```bash
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

`compose.build.yaml` 使用独立的本地镜像名 `wss-tunnel:local`，可通过 `TUNNEL_BUILD_IMAGE` 调整。`compose.image.yaml` 保留以兼容原有部署命令。完整说明见 [项目 README](../README.md)。

## 账号管理（当前源码）

使用 `compose.build.yaml` 构建新版本后，打开连接域名根路径进入管理页面；正式 `0.1.0` 镜像尚无账号功能。每个账号只有一个连接 Token，默认最多保留 10 个子域名（后台可调整）。离线后保留归属，页面提供状态、删除离线域名与个人审计记录。

Compose 已添加 `tunnel_data:/data` 持久卷；重建容器保留数据。`TUNNEL_ALLOW_REGISTRATION=false` 设置首次初始化的注册开关；后续通过后台修改，旧共享 Token 默认关闭。完整连接方法、审计范围和数据备份说明见[项目 README](../README.md#账号token子域名与审计)。


## 管理员后台

源码构建后执行以下命令，终端会隐藏输入密码：

```bash
docker compose exec tunnel tunnel-server --bootstrap-admin admin --bootstrap-only
```

打开 `https://你的连接域名/_tunnel/admin`。后台可修改默认/个人名额、注册开关、审计保留期及认证频率，查看用户 IP、客户端与域名状态，并筛选详细审计。普通注册不会产生管理员。

真实 IP：在 `.env` 的 `TUNNEL_TRUSTED_PROXIES` 设置实际 Traefik 容器 IP/CIDR，重新创建容器；留空时只记录直连 IP，不信任转发头。不要把示例地址直接用于生产。详见[管理后台说明](../README.md#管理后台当前源码)。
