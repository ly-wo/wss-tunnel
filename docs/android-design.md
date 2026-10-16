# WSS Tunnel 安卓客户端设计

## 目标与范围

以现有 v0.2.0 Rust 客户端、服务端和账号 API 为基础，交付可安装的 Android 应用。当前采用“隧道客户端 + 个人账号管理”的方案：在手机发起隧道，把手机自身或指定局域网 HTTP(S)/WebSocket 服务映射到已有公网服务器。管理员功能继续使用已实现的网页后台，应用内提供 HTTPS 入口。

用户已确认范围为“客户端＋账号管理：在手机上建立隧道，管理 Token 和子域名”。不提供 VPN、任意 TCP/UDP 转发、Root、开机自启、Google Play 上架或生产签名。公网服务端、数据库及既有线协议保持兼容。

## 页面与交互

采用 Kotlin + Jetpack Compose / Material 3 原生界面，中文优先。视觉延续网页的深青色主色（#187B73）、浅灰背景和白色圆角卡片，深色模式也保证可读性。主要触控目标至少 48dp，正常字号；系统边缘留白、键盘和大字体不遮挡主按钮。

底部三个入口：

| 页面 | 内容与主要动作 | 关键状态 |
| --- | --- | --- |
| 连接 | 接入地址、目标地址/端口、可选子域名、Token/账号密码方式；开始/断开；公网 URL 复制及系统分享；运行时长和简短状态 | 未配置、连接中、已连接、正在停止、失败；以 Rust Ready 为在线依据 |
| 域名 | 已用/服务端返回的上限、域名列表、在线/离线、最近连接；刷新、复用已有名称、删除离线名称 | 未登录提示、加载、空列表、错误、名额已满；不能硬编码 10 |
| 账号 | 登录/注册、用户资料、Token 前缀及创建时间；创建/重置 Token、个人审计；管理员网页入口、退出 | 服务端注册开关、会话过期、无 Token、新 Token 一次性显示、普通/管理员 |

首次进入显示清晰的连接表单，默认接入示例 `https://connect.demo.wlzxj.com`，目标示例 `http://127.0.0.1:3000`，不预填任何凭证。地址可以更改；界面明确 127.0.0.1 是手机本身，不是电脑。

用户可先用 Token 建立连接，不强制网页登录。连接 Token 与管理会话分开处理；Token 登录不伪装成已登录账号。生成 Token 必须用户点击；重置前提示旧 Token 及其连接将失效。完整 Token 不出现在普通状态页，不自动复制。若选择“保存连接 Token”，仅加密保存到本机；不选择则仅驻留内存。

“复用域名”只将名称填入连接表单，不自动断开或发起连接。删除离线名称前二次确认；在线禁用按钮，服务器 409 仍作为最终裁决。名额降低不删除旧名称，允许重用；超额时给出复用或删除离线名称的指引。

活动连接期间禁止直接修改服务器/目标/凭证，先断开再编辑。退出账号不隐式撤销已有隧道，但清除管理会话和账号数据；连接仍可在通知中停止。切换接入服务器必须先停止连接，并清除旧会话、用户数据和关联秘密，防止发送到新地址。

## 架构与代码边界

```mermaid
flowchart LR
  subgraph Android[Android 应用]
    UI[Compose 页面] --> VM[ViewModel / StateFlow]
    VM --> API[账号 API Repository]
    VM --> FGS[前台连接服务]
    VM --> Store[配置与 Keystore 加密存储]
    FGS --> JNI[JNI 薄适配]
  end
  subgraph Shared[共享 Rust 客户端]
    JNI --> Core[可取消的异步连接核心]
    CLI[现有命令行入口] --> Core
    Core --> Protocol[现有 protocol.rs / HTTP 与 WebSocket 转发]
  end
  API -->|HTTPS| Server[现有 Rust 服务端 / Traefik]
  Core -->|WSS| Server
  Core -->|HTTP(S) / WS(S)| Local[手机或指定局域网服务]
```

- 在 `client` 中提取可复用 library 核心，CLI 只负责参数、文件、密码提示、信号和输出。保持原命令行参数、HTTP/WS 转发和错误码行为；不复制一套 Kotlin 隧道实现。
- 核心 API 接收结构化配置、停止信号、状态通道；不直接读取环境、控制台或调用进程退出。Ready、结束、错误只能由当前运行实例更新；停止应取消网络连接/转发子任务并释放名称。
- JNI 负责启动、停止与查询状态，后台线程运行 Tokio；JNI 不阻塞主线程，不暴露原始指针给 Kotlin，不让 panic 穿越 FFI，不泄漏凭证或 callback 引用。单进程最多一条活动隧道；旧实例结束不能覆写新实例状态。
- `android/` 为独立 Gradle 项目。UI、API、配置存储和服务各自分文件，不把网络/JSON/秘密处理集中于 Activity。
- 不修改服务端 API 或现有 workspace 版本。Android 首版 versionName 对齐 0.2.0，versionCode=1；包名 `com.wlzxj.tunnel`。

## 连接生命周期

只在用户点击开始后启动前台服务；连接建立过程也应能取消。持续显示系统通知（目标、公网地址/连接状态与停止按钮），不含凭证。Activity 重建或退到后台不会创建第二条连接。

前台服务使用 `specialUse`，声明用途为用户发起、可随时停止的本地服务反向隧道。不得借用不适用的服务类型或宣称无限保活。Android 13+ 处理通知权限；拒绝时界面解释通知可见性的影响，始终提供页内停止操作。系统杀死后不自动重连，恢复为已断开；不注册开机启动，不申请电池白名单。

与现有 CLI 一致：认证失败、名额冲突、网络断开都结束本次连接并展示可理解的重试操作，不循环创建随机子域名。网络切换不保证保活，由真实连接状态决定。首版不提供无人值守自动重连。

## 接口契约（以现有代码为准）

请求同一接入 origin 的 `/_tunnel/api/*`，管理 API 使用 `Authorization: Bearer ws_session_…`，隧道使用 Basic 或连接 Token `wt_…`。所有管理写操作为 JSON，不自动跟随跨域重定向。

| 方法与路径 | 输入/结果 | 应用行为 |
| --- | --- | --- |
| GET config | registration, domain_limit | 注册入口开关；仅初始展示，实际名额以账号数据为准 |
| POST register | username/password → username | 注册后提示登录，不自动赋予权限 |
| POST login | username/password → session, expires_at, username | 会话只在内存，不把密码落盘 |
| GET me | username, token_prefix, token_created_at, domain_limit, is_admin, audit_retention_days | 用户信息与管理员网页入口 |
| POST token | `{}` → token, prefix | 一次性显示，保存/复制均由用户操作 |
| GET domains | domains[] {name,domain,status,created_at,last_connected_at}, limit | 在线/离线及真实名额 |
| DELETE domains/{name} | 204；在线/冲突 409 | 刷新列表；不能删除在线域名 |
| GET audit | events[] {id,action,outcome,domain,created_at}, retention_days | 个人最近 100 条；不虚构分页接口 |
| POST logout | `{}` → 204 | 清理本机会话；网络失败时仍允许本地退出并说明远端会话可能未撤销 |

401 清理管理登录状态但不误删连接 Token；403 显示权限/注册关闭；409 显示占用或名额提示；429 提示稍后重试，不反复自动发起。请求超时、TLS 错误和解析错误可重试且不残留加载状态。

隧道与公网业务 WebSocket 继续复用 `protocol.rs`：32 并发、5 MiB HTTP body、8 MiB 消息、20 秒心跳及现有断线判定。头字段保持多值及 Base64 编码，不能丢失 Set-Cookie。转发路径不能改变目标 origin，禁用自动重定向。

## 安全与持久化

- 正式接入只允许 HTTPS/WSS，使用正常证书验证。密码不能经公网明文传输；不提供“忽略证书错误”。本地 HTTP(S) 目标与公网接入区分。
- 测试可提供仅 Debug 且默认关闭的本地调试模式，仅放行 localhost、127.0.0.1、10.0.2.2；Release 无此入口。不要全局关闭 TLS 校验。
- 普通配置可存 DataStore/SharedPreferences；自愿保存的连接 Token 用 Android Keystore AES-GCM 加密，随机 IV，密钥不可导出；失效时清空密文并重新输入，不降级明文。密码、会话不保存；禁止备份秘密，禁用相关自动备份。
- 状态、通知、日志、审计、崩溃信息不能包含 Authorization 或完整秘密。Token 显示页可用 FLAG_SECURE；复制须用户触发并标记敏感剪贴板（系统支持时）。
- 不新增后端表或迁移。动态名额、权限和域名归属仍由服务端裁决。

## 构建与验证

构建建议固定 AGP 8.13.2 / Gradle 8.13 / JDK 17、compile/target SDK 36、min SDK 26，Kotlin 与 Compose 版本选匹配的稳定版并锁定。NDK 28.2.13676358，Rust target aarch64-linux-android；首版至少产出 arm64-v8a 可安装 Debug APK，并提供 x86_64 的构建选择。共享库需兼容 16 KiB 页大小；最终 APK 也需通过对齐检查。

使用 Gradle wrapper（包含 jar、distribution 校验）、可重复的 Rust JNI 构建脚本、`.gitignore` 排除 SDK 路径、构建目录、JNI 产物、签名秘密。增加独立 Android Actions 工作流，检查并上传 Debug APK；不更改现有正式发布链路，不配置生产签名。

验收覆盖：地址/名称校验，管理会话与连接 Token 隔离，凭证更换清理，动态名额、在线删除禁用、HTTP 错误处理；Rust CLI 回归；Android Debug APK 构建、真实 JNI 加载；模拟器连接本地测试服务后从公网入口转发请求、后台保持与通知停止。真实手机的 OEM 杀进程、长时间待机和商店审核不冒充已验证。

## 官方参考

- [AGP 8.13 兼容性](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk)
