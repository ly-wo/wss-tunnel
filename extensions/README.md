# 扩展能力

本目录提供独立接入方式，不要求修改业务页面、请求封装或后端接口。推荐通过 `npm install -D git+https://github.com/ly-wo/wss-tunnel.git` 安装，再运行 `npx wss-tunnel init`。

| 扩展 | 用途 | 接入范围 |
| --- | --- | --- |
| [Node 项目级启动](./node/README.md) | 项目一条命令启动 tunnel，或先启动应用再建立 tunnel；支持 Basic/Bearer 访问认证 | Vue、React、Node 以及已有本地 HTTP 服务的项目 |

需要在浏览器代码里调用受保护 API 时，使用独立的 [createTunnelFetch](../client/browser/README.md)。它是请求工具，与本目录的进程启动集成不同。
