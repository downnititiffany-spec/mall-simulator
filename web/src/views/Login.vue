<template>
  <div class="login-page">
    <div class="login-left">
      <div class="login-brand">
        <div class="brand-mark">EB</div>
        <div>
          <div class="login-brand-name">电商用户行为分析系统</div>
          <div class="login-brand-sub">E-Commerce User Behavior Analytics</div>
        </div>
      </div>
      <div class="login-points">
        <div class="login-point"><b>数据采集</b><span>行为埋点 → 消息队列 → Spark 分层仓库（ODS/DWD/DWS/ADS）</span></div>
        <div class="login-point"><b>指标口径</b><span>快照制指标管理，口径版本可追溯，前端不做任何推算</span></div>
        <div class="login-point"><b>分析看板</b><span>运营大盘 / 用户行为 / 商品 / 用户分层 / 销售</span></div>
        <div class="login-point"><b>智能问数</b><span>受控 Text-to-SQL 与证据包，结论可回溯到 SQL</span></div>
      </div>
      <div class="login-foot">V2.2 · 阶段7 看板</div>
    </div>

    <div class="login-right">
      <div class="login-card chart-box">
        <div class="login-title">电商用户行为分析系统</div>
        <div class="login-sub">请登录后进入运营看板</div>
        <form @submit.prevent="onSubmit">
          <div class="field">
            <label for="username">用户名</label>
            <input id="username" v-model.trim="username" type="text" autocomplete="username"
                   placeholder="请输入用户名" :disabled="loading" />
          </div>
          <div class="field">
            <label for="password">密码</label>
            <input id="password" v-model.trim="password" type="password" autocomplete="current-password"
                   placeholder="请输入密码" :disabled="loading" />
          </div>
          <div v-if="error" class="login-error" role="alert">{{ error }}</div>
          <button class="login-btn" type="submit" :disabled="loading">
            {{ loading ? '登录中…' : '登 录' }}
          </button>
        </form>
      </div>
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
  display: grid;
  grid-template-columns: 1.05fr .95fr;
  background: var(--bg-app);
}
/* 左：品牌栏（纯 CSS 渐变，无图片素材、无外网依赖） */
.login-left {
  position: relative;
  overflow: hidden;
  padding: 56px 56px 40px;
  color: #fff;
  background: linear-gradient(160deg, #172554 0%, #1E3A8A 45%, #1E40AF 100%);
  display: flex;
  flex-direction: column;
}
.login-left::after {
  content: '';
  position: absolute;
  right: -80px; bottom: -80px;
  width: 320px; height: 320px;
  border-radius: 50%;
  background: rgba(251, 191, 36, .10);
}
.login-brand { display: flex; align-items: center; gap: 14px; position: relative; }
.brand-mark {
  width: 44px; height: 44px; flex-shrink: 0;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, #60A5FA, #D97706);
  display: grid; place-items: center;
  font-weight: 800; font-size: 19px; color: #fff;
  box-shadow: 0 4px 14px rgba(0, 0, 0, .22);
}
.login-brand-name { font-size: 20px; font-weight: 700; letter-spacing: .01em; }
.login-brand-sub { font-size: 12px; color: var(--brand-200); margin-top: 4px; }
.login-points { margin-top: 44px; display: flex; flex-direction: column; gap: 18px; position: relative; }
.login-point { border-left: 2px solid rgba(255, 255, 255, .25); padding-left: 14px; }
.login-point b { display: block; font-size: 14px; font-weight: 600; color: #FBBF24; margin-bottom: 3px; }
.login-point span { font-size: 12.5px; color: rgba(219, 234, 254, .85); line-height: 1.7; }
.login-foot { margin-top: auto; font-size: 12px; color: rgba(191, 219, 254, .6); position: relative; }

/* 右：表单 */
.login-right { display: grid; place-items: center; padding: 40px 24px; }
.login-card { width: 380px; padding: 32px 30px 26px; margin: 0; box-shadow: var(--shadow-lg); }
.login-title { font-size: 19px; font-weight: 700; color: var(--text-primary); }
.login-sub { font-size: 12.5px; color: var(--text-secondary); margin: 5px 0 22px; }
.field { margin-bottom: 14px; }
.field label { display: block; font-size: var(--fs-base); color: var(--text-secondary); margin-bottom: 6px; }
.field input {
  width: 100%; height: 38px; padding: 0 10px;
  border: 1px solid var(--border); border-radius: var(--radius);
  font-size: var(--fs-md); color: var(--text-primary); background: #fff; outline: none;
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.field input:focus {
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, .15);
}
.login-error { color: var(--danger); font-size: var(--fs-base); margin: 2px 0 12px; }
/* 主按钮统一使用品牌锚点色（原为 #3b82f6，与全站 #1E40AF 不一致） */
.login-btn {
  width: 100%; height: 40px; border: none; border-radius: var(--radius);
  background: var(--brand-800); color: #fff;
  font-size: 15px; font-weight: 600; cursor: pointer;
  transition: filter var(--dur-fast) var(--ease);
}
.login-btn:hover:not(:disabled) { filter: brightness(1.08); }
.login-btn:disabled { opacity: .6; cursor: default; }

@media (max-width: 900px) {
  .login-page { grid-template-columns: 1fr; }
  .login-left { display: none; }
  .login-right { padding: 60px 24px; }
}
</style>
