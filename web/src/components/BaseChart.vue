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

const el = ref(null)
let chart = null

const render = () => {
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(props.option, true)
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