# Vue / React / 原生前端调用隧道 API

将 [tunnel.ts](./tunnel.ts) 复制到前端项目，例如 `src/lib/tunnel.ts`，直接导入。无运行时依赖，适用于支持 Fetch、Headers、URL、TextEncoder 的现代浏览器。无需安装 npm 包，也不依赖 Vue。

```ts
import { createTunnelFetch, TunnelAuthError } from '@/lib/tunnel';

// loginState 来自当前登录表单或内存状态，不要把秘密放入 VITE_* 或源码。
const loginState = { token: '' };
const request = createTunnelFetch(`${window.location.origin}/`, () => ({
  type: 'bearer',
  token: loginState.token,
}));

// 用户输入凭据后调用；每次请求读取最新 token，无需重建 request。
async function health() {
  try {
    const response = await request('/api/health');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    if (error instanceof TunnelAuthError) {
      // 清理登录状态，并在界面提示用户重新登录。
      loginState.token = '';
    }
    throw error;
  }
}
```

Basic 的 getter 改为：

```ts
() => ({ type: 'basic', username: loginState.username, password: loginState.password })
```

完整 Vue 3 示例见 [TunnelHealth.vue](./TunnelHealth.vue)，将它和 `tunnel.ts` 放在同一目录后导入组件即可。默认请求当前页面同源的 `/api/health`；`base-url` 属性可指定已有同源反向代理的地址。示例凭据只存组件内存。

## 方法行为

- `createTunnelFetch(baseURL, getAuth)` 返回 `(path, init?) => Promise<Response>`。baseURL 必须是绝对 HTTP(S) 地址；公网应使用 HTTPS。
- 遵循 URL 解析规则：`https://example.com/api/` 配合 `items` 得到 `/api/items`；`/items` 则从域名根目录开始。目录型 baseURL 要保留末尾 `/`。
- 支持 Fetch 的 method、body、普通 headers、signal 等参数；不会修改传入的 headers。每次调用都从 getter 读取凭据。
- Basic 使用 UTF-8 编码，支持中文用户名和密码；用户名不能含冒号，密码可含冒号。
- 401 抛出 `TunnelAuthError`（`status === 401`）；其他 HTTP 状态仍返回 Response，由调用方检查 `response.ok`。网络错误和 AbortError 原样传播。
- 拒绝不同源地址、URL 内嵌凭据、已有 Authorization 和 `no-cors`；强制 `redirect: 'error'`，所有重定向均失败，避免凭据转发到意外地址。

## 使用边界

这只是 API 请求方法，不是登录页面、会话系统或 CORS 代理：

- 若 Vue 页面本身也受隧道保护，需要先通过浏览器 Basic 提示完成首次页面认证，JS 才能加载。Bearer-only 保护下，不能靠页面里的代码解决首次加载问题。
- 当前 tunnel-client 也会校验 OPTIONS，而浏览器跨域预检不携带 Authorization。因此直接跨域调用不受支持；使用同源部署/同源反向代理。传入其他域名的 baseURL 不会自动解决 CORS。
- 隧道会移除认证用的 Authorization，无法同时用这个头携带后端业务 Token。
- 原生浏览器 WebSocket 不支持自定义 Authorization，此方法仅用于 HTTP Fetch。
- 此方法不改变既有 5 MiB 正文限制与 HMR 端口配置。

## 测试

Node >= 22.18；真实隧道测试还需要 Rust 构建产物：

```bash
node --experimental-strip-types --test tests/browser_fetch.test.mjs
cargo build --locked -p tunnel-client -p tunnel-server
python3 tests/access_auth.py --browser
```

单元测试覆盖认证、参数透传、凭据更新、401、网络错误/取消和凭据保护。集成测试使用 Node 原生 Fetch 调用真实 Rust 客户端/服务端，验证 Basic、Bearer、两者并存、401、凭据移除和重定向拒绝。测试路由器仅用于本地子域名映射；这些测试不代表浏览器 UI 或跨域 CORS 验收。
