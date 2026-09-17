<template>
  <div ref="el" :style="{ height: height + 'px', width: '100%' }"></div>
</template>

<script setup>
import * as echarts from 'echarts/core'
import { BarChart, LineChart, FunnelChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'

// ECharts 按需注册（指导书 §18.4）：只引入实际用到的图表与组件，避免整包引入造成大 chunk 告警
echarts.use([
  BarChart,
  LineChart,
  FunnelChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer
])

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: Number, default: 300 }
})

// 设计系统统一色板（Data-Dense Dashboard）：Navy 主/蓝次/琥珀强调/绿成功/灰
const PALETTE = ['#1E40AF', '#3B82F6', '#D97706', '#059669', '#64748B', '#7C3AED']
const FONT = "'Fira Sans','PingFang SC','Microsoft YaHei',sans-serif"

const el = ref(null)
let chart = null

const enrich = (opt) => ({
  color: opt.color || PALETTE,
  textStyle: { fontFamily: FONT },
  ...opt
})

const render = () => {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(enrich(props.option), true)
}
const resize = () => chart && chart.resize()

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart && chart.dispose()
})
watch(() => props.option, render, { deep: true })
// 高度可能由业务行数动态决定（如商品热度榜）。DOM 高度更新后必须主动通知 ECharts，
// 否则实例仍保留初始化时的画布尺寸，直到浏览器窗口发生 resize。
watch(() => props.height, () => nextTick(resize))
</script>