<template>
  <div>
    <div class="page-head">
      <div>
        <h1>商城演示</h1>
        <div class="page-desc">注册用户 → 浏览商品 → 加购 → 下单 → 支付 / 退款，为分析平台产生可复现的行为数据</div>
      </div>
      <div class="page-actions">
        <span class="badge badge-brand">当前用户：{{ currentUser }}</span>
      </div>
    </div>

    <!-- 演示用户 -->
    <div class="card">
      <div class="card-head">
        <div class="card-title">演示用户</div>
        <span class="badge badge-neutral badge-mono">userId {{ currentUserId || '—' }}</span>
      </div>
      <div class="row-wrap">
        <label class="field">
          <select class="select" v-model="ageGroup">
            <option value="18-24">18-24 岁</option><option value="25-34">25-34 岁</option>
            <option value="35-44">35-44 岁</option><option value="45+">45 岁以上</option>
          </select>
        </label>
        <label class="field">
          <select class="select" v-model="cityLevel">
            <option value="1">一线城市</option><option value="2">二线城市</option>
            <option value="3">三线及以下</option>
          </select>
        </label>
        <label class="field">
          <select class="select" v-model="memberLevel">
            <option value="normal">普通</option><option value="silver">银卡</option>
            <option value="gold">金卡</option>
          </select>
        </label>
        <button class="btn btn-primary" @click="createUser" :disabled="busy">{{ busy ? '提交中…' : '注册新用户' }}</button>
        <span class="sep"></span>
        <span v-if="users.length" class="row-wrap text-sm muted">
          历史用户：
          <a v-for="u in users" :key="u.userId" href="javascript:void(0)" @click="switchUser(u.userId)"
             class="user-chip">{{ u.userId }}</a>
        </span>
      </div>
      <div v-if="toast" class="alert alert-success mt-3">{{ toast }}</div>
      <div v-if="error" class="alert alert-danger mt-3">{{ error }}</div>
    </div>

    <!-- 商品 -->
    <div class="card">
      <div class="card-head">
        <div class="card-title">商品（点击「+ 加购」加入购物车）</div>
        <span class="badge badge-neutral badge-mono">{{ products.length }} 件</span>
      </div>
      <div class="product-grid">
        <article v-for="p in products" :key="p.productId" class="product-card">
          <div class="product-thumb"><span>{{ (p.productName || '商').slice(0, 1) }}</span></div>
          <div class="product-body">
            <div class="product-name" :title="p.productName">{{ p.productName }}</div>
            <div class="product-meta">
              <span class="badge badge-neutral">{{ p.categoryName || p.categoryId }}</span>
              <span class="text-xs muted">库存 {{ stockOf(p) }}</span>
            </div>
            <div class="product-foot">
              <span class="product-price">¥{{ price(p.price) }}</span>
              <button class="btn btn-sm btn-primary" @click="addCart(p)" :disabled="!currentUserId || busy">+ 加购</button>
            </div>
          </div>
        </article>
        <div v-if="!products.length" class="el-empty">暂无商品或接口未返回数据</div>
      </div>
    </div>

    <div class="mall-cols">
      <!-- 购物车 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">购物车</div>
          <span class="badge badge-brand badge-mono">{{ cart.length }} 项</span>
        </div>
        <ul v-if="cart.length" class="line-list">
          <li v-for="c in cart" :key="c.id" class="line-item">
            <span class="line-name">{{ nameOf(c.productId) }}</span>
            <span class="line-qty">× {{ c.quantity }}</span>
            <b class="line-amount">¥{{ price(priceOf(c.productId) * c.quantity) }}</b>
          </li>
        </ul>
        <div v-else class="el-empty">购物车为空</div>
        <button v-if="cart.length" class="btn btn-success btn-block mt-3" @click="checkout" :disabled="busy">下 单</button>
      </div>

      <!-- 订单 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">订单</div>
          <span class="badge badge-neutral badge-mono">{{ orders.length }} 单</span>
        </div>
        <ul v-if="orders.length" class="order-list">
          <li v-for="o in orders" :key="o.orderId" class="order-item">
            <div class="order-top">
              <span class="mono order-no">{{ o.orderId }}</span>
              <b class="order-amount">¥{{ price(o.totalAmount) }}</b>
              <span class="order-status" :style="{ color: statusColor(o.status) }">{{ o.status }}</span>
            </div>
            <div class="order-actions">
              <button class="btn btn-sm btn-success" v-if="actionable(o.status)" @click="pay(o)">支付</button>
              <button class="btn btn-sm btn-danger" v-if="actionable(o.status)" @click="cancel(o)">取消</button>
              <span v-for="r in o.refunds || []" :key="r.refundId" class="row refund-row">
                <span class="badge badge-warning">退款 {{ r.refundId }}（{{ r.status }}）</span>
                <button class="btn btn-sm" v-if="r.status === 'REFUNDING'" @click="completeRefund(r)">完成退款</button>
              </span>
            </div>
          </li>
        </ul>
        <div v-else class="el-empty">暂无订单</div>
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
const statusColor = (s) => ({ CREATED: '#d97706', PENDING_PAYMENT: '#d97706', PAID: 'var(--success)', CANCELLED: 'var(--gray-500)', REFUNDING: '#d97706', REFUNDED: 'var(--gray-500)', COMPLETED: 'var(--gray-800)' }[s] || 'var(--gray-800)')
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

<style scoped>
/* 商品卡片网格（缩略图用纯 CSS 色块占位，不需要图片素材） */
.product-grid {
  display: grid;
  gap: var(--sp-4);
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
}
.product-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--bg-surface);
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease), border-color var(--dur) var(--ease);
}
.product-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--brand-200);
}
.product-thumb {
  height: 76px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, var(--brand-100), var(--brand-200));
  color: var(--brand-800);
  font-size: 26px;
  font-weight: 700;
}
/* 按序号循环换色，避免所有卡片同色 */
.product-card:nth-child(4n+2) .product-thumb { background: linear-gradient(135deg, #FEF3C7, #FDE68A); color: #92400E; }
.product-card:nth-child(4n+3) .product-thumb { background: linear-gradient(135deg, #D1FAE5, #A7F3D0); color: #046C4E; }
.product-card:nth-child(4n+4) .product-thumb { background: linear-gradient(135deg, #EDE9FE, #DDD6FE); color: #5B21B6; }
.product-body { padding: var(--sp-3); display: flex; flex-direction: column; gap: var(--sp-2); flex: 1; }
.product-name {
  font-size: var(--fs-base); font-weight: 600; color: var(--text-primary);
  line-height: 1.5; min-height: 2.6em;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.product-meta { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); }
.product-foot { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); margin-top: auto; }
.product-price {
  font-family: var(--font-mono); font-variant-numeric: tabular-nums;
  font-size: var(--fs-lg); font-weight: 700; color: var(--brand-800);
}

/* 历史用户小胶囊 */
.user-chip {
  display: inline-block;
  padding: 1px 9px;
  border: 1px solid var(--brand-200);
  border-radius: var(--radius-pill);
  background: var(--brand-50);
  color: var(--brand-800);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  text-decoration: none;
}
.user-chip:hover { background: var(--brand-100); }

/* 购物车 / 订单：两栏 */
.mall-cols { display: grid; gap: var(--sp-4); grid-template-columns: 1fr 1.4fr; align-items: start; }
@media (max-width: 900px) { .mall-cols { grid-template-columns: 1fr; } }

.line-list, .order-list { list-style: none; }
.line-item {
  display: flex; align-items: center; gap: var(--sp-3);
  padding: 9px 0; border-bottom: 1px solid var(--border);
}
.line-item:last-child { border-bottom: none; }
.line-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-base); }
.line-qty { color: var(--text-tertiary); font-size: var(--fs-sm); font-family: var(--font-mono); }
.line-amount {
  font-family: var(--font-mono); font-variant-numeric: tabular-nums;
  color: var(--text-primary); font-weight: 600;
}

.order-item {
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--border);
}
.order-item:last-child { border-bottom: none; }
.order-top { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.order-no { font-size: var(--fs-base); color: var(--text-secondary); }
.order-amount {
  font-family: var(--font-mono); font-variant-numeric: tabular-nums;
  color: var(--text-primary); margin-left: auto;
}
.order-status { font-size: var(--fs-sm); font-weight: 600; font-family: var(--font-mono); }
.order-actions { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-2); }
.refund-row { gap: var(--sp-2); }
</style>
