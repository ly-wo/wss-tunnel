# 构建与发布

## 自动流程

| 事件 | 结果 |
| --- | --- |
| PR | 格式、Clippy、测试、Docker/WSS 集成验证；Linux x64 客户端/服务端构建产物 |
| 推送 main / 手动运行 CI（main） | 上述检查与产物；GHCR 发布多架构 `dev` 和 `sha-完整提交号` 镜像 |
| 推送 `vX.Y.Z` tag | 再次检查；五平台二进制；Release 附件及 SHA256SUMS；发布 `X.Y.Z`、`X.Y`、`latest`、`sha-完整提交号` 镜像 |

二进制平台：Linux x64 / ARM64（glibc 2.35+）、macOS Intel / Apple Silicon、Windows x64。每个压缩包包含客户端和服务端、配置示例与 README；Windows 使用 zip，其他平台使用 tar.gz。Actions 产物保留 14 天，正式 Release 附件长期保留。macOS/Windows 二进制未签名或公证。

镜像为服务端镜像 `ghcr.io/ly-wo/wss-tunnel`，支持 `linux/amd64`、`linux/arm64`，附带 SBOM 和构建 provenance。客户端仍运行在需要暴露端口的本机。

需要单独构建所有平台而不发布版本时，在 Actions 手动运行 **Build binaries**，保持 `all-platforms=true`。这只上传构建产物，不创建 tag 或 Release。

## 创建版本

先修改根目录 Cargo.toml 的 `[workspace.package].version`，运行 `cargo check --workspace` 更新 Cargo.lock 中两个包的版本，提交并推送到 main。tag 必须与 workspace 版本一致，目前只接受稳定版本 `vX.Y.Z`。

方式一：在 GitHub Actions 中选择 **Create version tag**，选择 `main` 分支，输入 `0.1.0` 这样的版本号。该流程创建 tag 并显式触发 Release；不会覆盖同名 tag。若创建 tag 后发布调度失败，可以手动选择该 tag 运行 Release。

方式二：本机手动创建：

```bash
git switch main
git pull --ff-only
git tag -a v0.1.0 -m 'Release v0.1.0'
git push origin v0.1.0
```

Release 会先完成全部平台构建和镜像发布，再创建草稿、上传并核对附件、发布 Release。失败时不会发布空的正式 Release；已发布 Release 禁止覆写。多架构镜像一旦推送即存在，即使后续 Release 发布失败，也不会自动删除镜像。

不要并行发布较旧版本与新版本：`latest` / `X.Y` 指向最后完成发布的版本，不按版本号比较。生产建议使用固定版本或镜像 digest。

## 使用镜像部署

在 server 目录配置好 .env 和 secrets/token 后：

```bash
# .env 设置 TUNNEL_IMAGE=ghcr.io/ly-wo/wss-tunnel:0.1.0
# 当前仓库为私有，拉取前需要具有 read:packages 权限的账号登录 GHCR
docker login ghcr.io
docker compose -f compose.yaml -f compose.image.yaml pull
docker compose -f compose.yaml -f compose.image.yaml up -d --no-build
```

首个版本未发布前可使用 `dev`；`latest` 仅由正式版本发布生成。源代码构建仍可使用原先的 `docker compose up -d --build`。

## 权限与维护

不需要额外配置发布 PAT：工作流使用 GITHUB_TOKEN，只有发布作业获得 packages:write / contents:write，创建 tag 作业另有 actions:write 用于显式调度。Fork PR 不执行镜像发布。

外部 Actions 固定到 commit SHA。Rust 版本由 rust-toolchain.toml 固定；更新编译器时同时更新 server/Dockerfile 和 Cargo.toml 的 rust-version。构建依赖由 Cargo.lock 锁定。

手动运行 **Release** 必须选择已有版本 tag；选择 main 会在版本校验阶段失败。更新既有发布请提升版本号，不要移动已发布 tag。
