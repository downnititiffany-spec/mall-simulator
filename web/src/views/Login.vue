<template>
  <div class="login-page">
    <div class="login-card chart-box">
      <div class="login-title">电商用户行为分析系统</div>
      <div class="login-sub">请登录后进入运营看板</div>
      <div class="field">
        <label for="username">用户名</label>
        <input id="username" v-model.trim="username" type="text" placeholder="请输入用户名"
               :disabled="loading" @keyup.enter="onSubmit" />
      </div>
      <div class="field">
        <label for="password">密码</label>
        <input id="password" v-model.trim="password" type="password" placeholder="请输入密码"
               :disabled="loading" @keyup.enter="onSubmit" />
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
import { useRouter } from 'vue-router'
import api from '../api'

const router = useRouter()
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
    router.push('/')
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
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card {
  width: 360px;
  padding: 32px 28px 28px;
  margin: 0;
}
.login-title { font-size: 18px; font-weight: 700; color: #111827; text-align: center; }
.login-sub { font-size: 12px; color: #6b7280; text-align: center; margin: 6px 0 22px; }
.field { margin-bottom: 14px; }
.field label { display: block; font-size: 13px; color: #374151; margin-bottom: 6px; }
.field input {
  width: 100%; height: 38px; padding: 0 10px; border: 1px solid #d1d5db; border-radius: 6px;
  font-size: 14px; color: #111827; background: #fff; outline: none;
}
.field input:focus { border-color: #3b82f6; }
.login-error { color: #dc2626; font-size: 13px; margin: 2px 0 12px; }
.login-btn {
  width: 100%; height: 40px; border: none; border-radius: 6px; background: #3b82f6; color: #fff;
  font-size: 15px; font-weight: 600; cursor: pointer;
}
.login-btn:hover { background: #2563eb; }
.login-btn:disabled { opacity: .6; cursor: default; }
</style>