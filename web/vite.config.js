import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：/api -> analytics-server 平台应用（8091）
// 边界（指导书 V2.0 §18.4 / §31 第 6 条）：本前端是**分析平台**前端，只连平台应用；
// 商城演示与商品后台页面已迁出本前端，因此代理不再指向 mall-simulator(8090)。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8091',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    // ECharts 已改为按需注册（BaseChart 只 use 柱/折线/漏斗 + 网格/提示/图例 + Canvas 渲染器），
    // 整包引入约 1 MB 的 chunk 降到约 512 kB（gzip 约 172 kB），这是该功能集的下限。
    // 继续按 echarts 内部模块边界拆分会产生 echarts-charts <-> echarts-core 循环 chunk，
    // 因此这里只把阈值上调到 600 kB 并保留说明，不用“拆出循环依赖”的方式换取警告消失。
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        // ECharts 被多个懒加载分析页共用，单独成 chunk，避免并入某个页面 chunk
        manualChunks: {
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers']
        }
      }
    }
  }
})