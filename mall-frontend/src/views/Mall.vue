<template>
  <div>
    <div class="page-title">商城演示</div>
    <div class="chart-box" style="margin-bottom:16px">
      <div class="chart-title">演示用户
        <span style="float:right;font-size:12px">当前用户：<b>{{ currentUser }}</b></span>
      </div>
      <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
        <select v-model="ageGroup" style="padding:4px">
          <option value="18-24">18-24 岁</option><option value="25-34">25-34 岁</option>
          <option value="35-44">35-44 岁</option><option value="45+">45 岁以上</option>
        </select>
        <select v-model="cityLevel" style="padding:4px">
          <option value="1">一线城市</option><option value="2">二线城市</option>
          <option value="3">三线及以下</option>
        </select>
        <select v-model="memberLevel" style="padding:4px">
          <option value="normal">普通</option><option value="silver">银卡</option>
          <option value="gold">金卡</option>
        </select>
        <button @click="createUser" :disabled="busy">{{ busy ? '提交中…' : '注册新用户' }}</button>
        <span v-if="users.length" style="margin-left:8px;color:#6b7280;font-size:12px">
          历史用户：
          <a v-for="u in users" :key="u.userId" href="javascript:void(0)" @click="switchUser(u.userId)"
             style="margin-right:8px;color:#2563eb">{{ u.userId }}</a>
        </span>
      </div>
      <div v-if="toast" style="margin-top:10px;padding:8px 12px;background:#ecfdf5;color:#047857;font-size:13px">{{ toast }}</div>
      <div v-if="error" style="margin-top:10px;padding:8px 12px;background:#fef2f2;color:#b91c1c;font-size:13px">{{ error }}</div>
    </div>

    <div class="chart-box" style="margin-bottom:16px">
      <div class="chart-title">商品（点击「+ 加购」加入购物车）</div>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">ID</th><th>商品名称</th><th>分类</th><th>价格</th><th>库存</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="p in products" :key="p.productId" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ p.productId }}</td>
            <td>{{ p.productName }}</td>
            <td>{{ p.categoryName || p.categoryId }}</td>
            <td>¥{{ price(p.price) }}</td>
            <td>{{ stockOf(p) }}</td>
            <td><button style="font-size:12px" @click="addCart(p)" :disabled="!currentUserId || busy">+ 加购</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div style="display:grid;grid-template-columns:1fr 1.4fr;gap:16px;align-items:start">
      <div class="chart-box">
        <div class="chart-title">购物车（userId={{ currentUserId || '—' }}）</div>
        <table v-if="cart.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <tbody>
            <tr v-for="c in cart" :key="c.id" style="border-top:1px solid #f3f4f6">
              <td style="padding:8px">{{ nameOf(c.productId) }} × {{ c.quantity }}</td>
              <td style="text-align:right"><b>¥{{ price(priceOf(c.productId) * c.quantity) }}</b></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty" style="padding:16px">购物车为空</div>
        <button v-if="cart.length" style="margin-top:12px;background:#16a34a" @click="checkout" :disabled="busy">下单</button>
      </div>

      <div class="chart-box">
        <div class="chart-title">订单（userId={{ currentUserId || '—' }}）</div>
        <table v-if="orders.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th style="padding:6px">订单号</th><th>金额</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="o in orders" :key="o.orderId" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ o.orderId }}</td>
              <td>¥{{ price(o.totalAmount) }}</td>
              <td><span :style="{ color: statusColor(o.status), fontWeight: 600 }">{{ o.status }}</span></td>
              <td style="white-space:nowrap">
                <button v-if="actionable(o.status)" style="font-size:12px;background:#16a34a" @click="pay(o)">支付</button>
                <button v-if="actionable(o.status)" style="font-size:12px;background:#dc2626" @click="cancel(o)">取消</button>
                <span v-for="r in o.refunds || []" :key="r.refundId" style="margin-left:6px">
                  <span style="color:#d97706;font-size:12px">退款{{ r.refundId }}({{ r.status }})</span>
                  <button v-if="r.status === 'REFUNDING'" style="font-size:12px" @click="completeRefund(r)">完成</button>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty" style="padding:16px">暂无订单</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const products = ref([])
const cart = ref([])
const orders = ref([])
const users = ref([])
const currentUserId = ref(String(localStorage.getItem('mall_demo_user') || ''))
const ageGroup = ref('25-34')
const cityLevel = ref('2')
const memberLevel = ref('normal')
const busy = ref(false)
const toast = ref('')
const error = ref('')

const price = (v) => Number(v || 0).toFixed(2)
const nameOf = (pid) => (products.value.find(p => p.productId === pid) || {}).productName || `商品 ${pid}`
const priceOf = (pid) => Number((products.value.find(p => p.productId === pid) || {}).price || 0)
const stockOf = (p) => (p.stock ?? p.stockQuantity ?? '—')
const currentUser = computed(() => currentUserId.value || '未选择（注册新用户）')
const statusColor = (s) => ({ CREATED: '#d97706', PENDING_PAYMENT: '#d97706', PAID: '#16a34a', CANCELLED: '#6b7280', REFUNDING: '#d97706', REFUNDED: '#6b7280', COMPLETED: '#111827' }[s] || '#111827')
const actionable = (s) => s === 'CREATED' || s === 'PENDING_PAYMENT'

const flash = (msg, isErr = false) => {
  toast.value = isErr ? '' : msg
  error.value = isErr ? msg : ''
  setTimeout(() => { toast.value = ''; error.value = '' }, 4000)
}

async function loadAll() {
  const [pl, cl, ol] = await Promise.all([
    api.get('/mall/products'),
    currentUserId.value ? api.get('/mall/cart/items', { userId: currentUserId.value }).catch(() => []) : Promise.resolve([]),
    currentUserId.value ? api.get('/mall/orders', { userId: currentUserId.value }).catch(() => []) : Promise.resolve([]),
  ])
  products.value = pl || []
  cart.value = cl || []
  orders.value = ol || []
}
async function createUser() {
  busy.value = true
  try {
    const r = await api.post('/mall/users', { ageGroup: ageGroup.value, cityLevel: cityLevel.value, memberLevel: memberLevel.value })
    currentUserId.value = r.userId
    localStorage.setItem('mall_demo_user', String(r.userId))
    users.value = [...new Set([...users.value, r.userId])]
    flash(`用户 ${r.userId} 注册成功`)
    await loadAll()
  } catch (e) { flash(e.message || '注册失败', true) } finally { busy.value = false }
}
function switchUser(id) {
  currentUserId.value = id
  localStorage.setItem('mall_demo_user', String(id))
  flash(`切换到用户 ${id}`)
  loadAll()
}
async function addCart(p) {
  busy.value = true
  try {
    await api.post('/mall/cart/items', { userId: currentUserId.value, productId: p.productId, quantity: 1 })
    flash(`已加入购物车：${p.productName}`)
    await loadAll()
  } catch (e) { flash(e.message || '加购失败', true) } finally { busy.value = false }
}
async function checkout() {
  if (!cart.value.length) return
  busy.value = true
  try {
    const items = cart.value.map(c => ({ productId: c.productId, quantity: c.quantity }))
    const r = await api.post('/mall/orders', { userId: currentUserId.value, items })
    flash(`订单 ${r.orderId} 已创建`)
    await loadAll()
  } catch (e) { flash(e.message || '下单失败', true) } finally { busy.value = false }
}
async function pay(o) {
  busy.value = true
  try {
    await api.post(`/mall/orders/${o.orderId}/pay`, { userId: currentUserId.value })
    flash(`订单 ${o.orderId} 支付成功`)
    await loadAll()
  } catch (e) { flash(e.message || '支付失败', true) } finally { busy.value = false }
}
async function cancel(o) {
  busy.value = true
  try {
    await api.post(`/mall/orders/${o.orderId}/cancel`, { userId: currentUserId.value, reason: '演示取消' })
    flash(`订单 ${o.orderId} 已取消`)
    await loadAll()
  } catch (e) { flash(e.message || '取消失败', true) } finally { busy.value = false }
}
async function completeRefund(r) {
  busy.value = true
  try {
    await api.post(`/mall/refunds/${r.refundId}/complete`, { userId: currentUserId.value })
    flash(`退款 ${r.refundId} 已完成`)
    await loadAll()
  } catch (e) { flash(e.message || '退款失败', true) } finally { busy.value = false }
}
onMounted(() => { users.value = localStorage.getItem('mall_demo_users') ? JSON.parse(localStorage.getItem('mall_demo_users')) : []; loadAll() })
</script>