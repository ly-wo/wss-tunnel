# Android 验证记录

2026-10-16，Cursor 实施，Codex 独立复核。基于 Rust v0.2.0，未修改服务端接口或部署配置。

## 已通过

- `cargo fmt --all --check`、`cargo clippy --workspace --locked -- -D warnings`、`cargo test --workspace --locked`。
- 真实 CLI 账号回归：注册、登录、Token 隔离与重置、默认10个域名名额、审计及持久化。
- 共享 Rust 转发核心的16种接入/目标协议组合；HTTP(S)、WebSocket、证书拒绝和断线清理。
- Android `testDebugUnitTest`：7项通过；`assembleDebug` 成功。
- Android 16/API36 模拟器 `connectedDebugAndroidTest`：10项通过，0失败、0跳过。覆盖三页导航、错误提示、JNI加载、真实账号 API、Token随机域名转发、密码指定域名转发及在线/离线状态、失败与停止、旧代回调过滤。
- ARM64与x86_64原生库交叉编译成功；交付ARM64 Debug APK通过签名校验、ZIP 16KB对齐；APK内三种原生库的LOAD段均为16KB对齐。
- 独立安装启动，查看浅色/暗色实际页面。完整安装包与校验文件位于本任务输出目录。

## 验证边界

设备联调使用本机测试服务器、`adb reverse`与仅Debug开启的本地HTTP/WS，不使用生产账号或公网DNS。Android端生产WSS、真机长期后台、OEM省电策略、生产签名和商店审核尚未验证。共享Rust核心的WSS/HTTPS能力已另经Traefik回归。

本记录中的测试结果来自本机验证；远端构建状态以 GitHub Actions 对应提交的运行结果为准。APK是开发测试安装包。
