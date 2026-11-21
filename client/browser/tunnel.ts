/** Framework-independent HTTP helper for the tunnel's public-domain access gate. */
export type TunnelAuth =
  | { type: 'bearer'; token: string }
  | { type: 'basic'; username: string; password: string };

export class TunnelAuthError extends Error {
  readonly status = 401;
  constructor() {
    super('Tunnel access authentication failed');
    this.name = 'TunnelAuthError';
  }
}

function authorization(auth: TunnelAuth): string {
  if (auth.type === 'bearer') {
    if (!/^[A-Za-z0-9._~+/-]+=*$/.test(auth.token)) {
      throw new TypeError('Invalid tunnel Bearer token');
    }
    return `Bearer ${auth.token}`;
  }
  if (auth.type !== 'basic' || !auth.username || !auth.password
    || auth.username.includes(':') || /[\u0000-\u001f\u007f-\u009f]/u.test(auth.username + auth.password)) {
    throw new TypeError('Invalid tunnel Basic credentials');
  }
  const bytes = new TextEncoder().encode(`${auth.username}:${auth.password}`);
  return `Basic ${btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''))}`;
}

/**
 * Credentials are obtained for each call, so login state can change without recreating the helper.
 * URLs must remain on baseURL's origin. Redirects fail rather than forwarding credentials.
 * CORS, initial page authentication and WebSocket authentication are not handled here.
 */
export function createTunnelFetch(baseURL: string, getAuth: () => TunnelAuth) {
  const base = new URL(baseURL);
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password) {
    throw new TypeError('Tunnel base URL must be HTTP(S) without embedded credentials');
  }
  return async (path: string, init: RequestInit = {}): Promise<Response> => {
    const url = new URL(path, base);
    if (url.origin !== base.origin || url.username || url.password) {
      throw new TypeError('Request URL must belong to the configured tunnel origin');
    }
    if (init.mode === 'no-cors') {
      throw new TypeError('Tunnel authentication cannot use no-cors mode');
    }
    const headers = new Headers(init.headers);
    if (headers.has('Authorization')) {
      throw new TypeError('Authorization is reserved for tunnel access authentication');
    }
    headers.set('Authorization', authorization(getAuth()));
    const response = await fetch(url, { ...init, headers, redirect: 'error' });
    if (response.status === 401) throw new TunnelAuthError();
    return response;
  };
}
