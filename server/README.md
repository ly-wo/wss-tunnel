# Rust 服务端

在本目录执行：

```bash
./init.sh
# 编辑 .env，确认 Traefik 网络、入口与 DNS 证书 resolver
docker compose up -d --build
```

容器加入现有 Traefik bridge 网络，端口 8080 只在 Docker 网络内使用。部署时保留完整项目，构建上下文是上一级目录。完整说明见 [项目 README](../README.md)。
