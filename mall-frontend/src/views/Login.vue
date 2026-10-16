<template>
  <div class="login-page">
    <div class="login-card chart-box">
      <div class="login-title">模拟商城（mall-simulator）</div>
      <div class="login-sub">该账号来自商城库 mall_simulator，与分析平台账号相互独立</div>
      <div class="field">
        <label for="username">用户名</label>
        <input id="username" v-model.trim="username" type="text" placeholder="请输入用户名" @keyup.enter="onSubmit" />
      </div>
      <div class="field">
        <label for="password">密码</label>
        <input id="password" v-model.trim="password" type="password" placeholder="请输入密码" @keyup.enter="onSubmit" />
      </div>
      <div v-if="error" class="login-error">{{ error }}</div>
      <button class="login-btn" :disabled="loading" @click="onSubmit">
        {{ loading ? '登录中…' : '登 录' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '../api'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const onSubmit = async () => {
  if (loading.value) return
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await api.login(username.value, password.value)
    router.push(route.query.redirect || '/mall')
  } catch (e) {
    error.value =
      (e && e.response && e.response.data && e.response.data.message) ||
      (e && e.message) ||
      '登录失败，请检查用户名或密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 与分析平台登录页保持同一套视觉：品牌渐变背景 + 白卡 + 品牌锚点主按钮 */
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: linear-gradient(160deg, #172554 0%, #1E3A8A 45%, #1E40AF 100%);
}
.login-card {
  width: 380px;
  padding: 32px 30px 26px;
  margin: 0;
  border: none;
  box-shadow: var(--shadow-lg);
}
.login-title { font-size: 19px; font-weight: 700; color: var(--text-primary); text-align: center; }
.login-sub { font-size: 12px; color: var(--text-secondary); text-align: center; margin: 6px 0 22px; line-height: 1.7; }
.field { margin-bottom: 14px; }
.field label { display: block; font-size: var(--fs-base); color: var(--text-secondary); margin-bottom: 6px; }
.field input {
  width: 100%; height: 38px; padding: 0 10px;
  border: 1px solid var(--border); border-radius: var(--radius);
  font-size: var(--fs-md); color: var(--text-primary); background: #fff; outline: none;
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.field input:focus { border-color: var(--brand-500); box-shadow: 0 0 0 3px rgba(59, 130, 246, .15); }
.login-error { color: var(--danger); font-size: var(--fs-base); margin: 2px 0 12px; }
.login-btn {
  width: 100%; height: 40px; border: none; border-radius: var(--radius);
  background: var(--brand-800); color: #fff;
  font-size: 15px; font-weight: 600; cursor: pointer;
  transition: filter var(--dur-fast) var(--ease);
}
.login-btn:hover { filter: brightness(1.08); }
.login-btn:disabled { opacity: .6; cursor: default; }
</style>
