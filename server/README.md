# Rust 服务端

在本目录执行：

```bash
./init.sh
# 编辑 .env，确认 Traefik 网络、入口与 DNS 证书 resolver
# 私有镜像需先 docker login ghcr.io（账号需有 read:packages 权限）
docker compose pull
docker compose up -d
```

容器加入现有 Traefik bridge 网络，端口 8080 只在 Docker 网络内使用。默认使用 `ghcr.io/ly-wo/wss-tunnel:0.1.0`，可通过 `.env` 中的 `TUNNEL_IMAGE` 指定其他版本。客户端应使用对应版本的 Release 产物。升级时修改镜像版本，再执行 `docker compose pull && docker compose up -d`。

从源码构建时保留完整项目，构建上下文是上一级目录：

```bash
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

`compose.build.yaml` 使用独立的本地镜像名 `wss-tunnel:local`，可通过 `TUNNEL_BUILD_IMAGE` 调整。`compose.image.yaml` 保留以兼容原有部署命令。完整说明见 [项目 README](../README.md)。
