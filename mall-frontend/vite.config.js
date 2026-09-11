import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 边界（指导书 V2.0 §18.4 / §31 第 6 条）：
// 本前端是**模拟商城**前端，只连 mall-simulator（8090），只包含商城演示、商品后台、数据生成器；
// 分析类页面（运营/行为/商品分析/销售/分群/流水线/AI/决策）全部属于分析平台前端 web/（8091）。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist'
  }
})
