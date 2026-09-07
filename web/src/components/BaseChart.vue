<template>
  <div ref="el" :style="{ height: height + 'px', width: '100%' }"></div>
</template>

<script setup>
import * as echarts from 'echarts'
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'

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
</script>