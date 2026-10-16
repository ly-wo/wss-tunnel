# Rust 客户端

在项目根目录运行 `cargo build --release --locked -p tunnel-client`，然后在本目录复制 `.env.example` 为 `.env`，填写 WSS 地址和密钥文件路径。

```bash
./run.sh 3000             # 随机子域名
./run.sh 3000 --name demo # 指定子域名
```

本机目标为 `http://127.0.0.1:3000`。完整说明见 [项目 README](../README.md)。
