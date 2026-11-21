<script setup lang="ts">
import { reactive, ref } from 'vue';
import { createTunnelFetch, TunnelAuthError } from './tunnel';

const props = defineProps<{ baseUrl?: string }>();
const login = reactive({ type: 'bearer' as 'basic' | 'bearer', username: '', password: '', token: '' });
const result = ref('');
const loading = ref(false);
const request = createTunnelFetch(props.baseUrl ?? `${window.location.origin}/`, () =>
  login.type === 'bearer'
    ? { type: 'bearer', token: login.token }
    : { type: 'basic', username: login.username, password: login.password },
);

async function checkHealth() {
  loading.value = true;
  result.value = '';
  try {
    const response = await request('/api/health');
    if (!response.ok) throw new Error(`请求失败：HTTP ${response.status}`);
    result.value = JSON.stringify(await response.json(), null, 2);
  } catch (error) {
    result.value = error instanceof TunnelAuthError
      ? '访问凭据不正确，请重新输入。'
      : error instanceof Error ? error.message : '请求失败';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <form @submit.prevent="checkHealth">
    <label>认证方式
      <select v-model="login.type">
        <option value="bearer">Bearer</option>
        <option value="basic">Basic</option>
      </select>
    </label>
    <label v-if="login.type === 'bearer'">Token
      <input v-model="login.token" type="password" autocomplete="off" required />
    </label>
    <template v-else>
      <label>用户名 <input v-model="login.username" autocomplete="username" required /></label>
      <label>密码 <input v-model="login.password" type="password" autocomplete="current-password" required /></label>
    </template>
    <button :disabled="loading">{{ loading ? '请求中…' : '检查连接' }}</button>
    <pre aria-live="polite">{{ result }}</pre>
  </form>
</template>
