<template>
  <div>
    <div class="page-title">商品管理（admin）</div>
    <div class="chart-box">
      <div class="chart-title">新建商品
        <span style="float:right;font-size:12px"><button style="font-size:12px" @click="loadAll">刷新</button></span>
      </div>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <input v-model="form.name" placeholder="商品名称" style="padding:4px" />
        <select v-model="form.categoryId" style="padding:4px">
          <option v-for="c in categories" :key="c.categoryId" :value="c.categoryId">{{ c.categoryName }}</option>
        </select>
        <input v-model.number="form.price" placeholder="售价" type="number" step="0.01" style="padding:4px;width:90px" />
        <input v-model.number="form.cost" placeholder="成本" type="number" step="0.01" style="padding:4px;width:90px" />
        <button style="font-size:12px;background:#16a34a" @click="createProduct" :disabled="busy">创建</button>
      </div>
    </div>
    <div class="table-box">
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">ID</th><th>名称</th><th>分类</th><th>售价</th><th>成本</th>
          <th>库存(可用/预留)</th><th>状态</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="p in products" :key="p.productId" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ p.productId }}</td>
            <td>{{ p.productName }}</td>
            <td>{{ categoryName(p.categoryId) }}</td>
            <td>¥{{ Number(p.price).toFixed(2) }}</td>
            <td>¥{{ Number(p.cost).toFixed(2) }}</td>
            <td>{{ p.availableQty }}/{{ p.reservedQty }}</td>
            <td><span :style="{ color: p.status === 'on_sale' ? '#16a34a' : '#dc2626', fontWeight: 600 }">{{ p.status === 'on_sale' ? '在售' : '下架' }}</span></td>
            <td style="white-space:nowrap">
              <button style="font-size:12px" @click="adjustPrice(p)">改价</button>
              <button style="font-size:12px" @click="adjustStock(p)">调库存</button>
              <button v-if="p.status === 'on_sale'" style="font-size:12px;background:#dc2626" @click="toggle(p, 'off_sale')">下架</button>
              <button v-else style="font-size:12px;background:#16a34a" @click="toggle(p, 'on_sale')">上架</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const products = ref([])
const categories = ref([])
const busy = ref(false)
const form = ref({ name: '', categoryId: null, price: 10, cost: 5 })

const flash = (msg, err) => { alert((err ? '操作失败：' : '') + msg) }

async function loadAll() {
  products.value = await api.get('/admin/products') || []
  if (!categories.value.length) {
    categories.value = await api.get('/mall/products', { categoryId: undefined }).catch(() => [])
    // 分类列表：从商品响应取不到分类名时退化为已知 8 类
    if (!categories.value.length) {
      categories.value = [1, 2, 3, 4, 5, 6, 7, 8].map(id => ({ categoryId: id, categoryName: '分类' + id }))
    }
  }
}
const categoryName = (id) => (categories.value.find(c => Number(c.categoryId) === Number(id)) || {}).categoryName || String(id)

async function createProduct() {
  if (!form.value.name || !form.value.categoryId) { flash('名称与分类必填', true); return }
  busy.value = true
  try {
    const r = await api.post('/admin/products', { name: form.value.name, categoryId: Number(form.value.categoryId), brandId: 1, price: form.value.price, cost: form.value.cost })
    flash(`商品创建成功 ID=${r.productId}`)
    form.value = { name: '', categoryId: null, price: 10, cost: 5 }
    await loadAll()
  } catch (e) { flash(e.message || '', true) } finally { busy.value = false }
}
async function adjustPrice(p) {
  const v = prompt(`调整「${p.productName}」售价（当前 ¥${Number(p.price).toFixed(2)}）`, p.price)
  if (!v || isNaN(v)) return
  busy.value = true
  try { await api.post(`/admin/products/${p.productId}/price`, { price: Number(v) }); flash('改价成功'); await loadAll() }
  catch (e) { flash(e.message || '', true) } finally { busy.value = false }
}
async function adjustStock(p) {
  const v = prompt(`设置「${p.productName}」可用库存（当前 ${p.availableQty}，输入目标值）`, p.availableQty)
  if (!v || isNaN(v)) return
  busy.value = true
  try { await api.post(`/admin/products/${p.productId}/stock`, { quantity: Number(v), changeType: 'adjust' }); flash('库存调整成功'); await loadAll() }
  catch (e) { flash(e.message || '', true) } finally { busy.value = false }
}
async function toggle(p, status) {
  busy.value = true
  try { await api.post(`/admin/products/${p.productId}/status`, { status }); flash(status === 'on_sale' ? '已上架' : '已下架'); await loadAll() }
  catch (e) { flash(e.message || '', true) } finally { busy.value = false }
}
onMounted(loadAll)
</script>