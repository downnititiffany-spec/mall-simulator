import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：/api -> analytics-server（mall-simulator 8090）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
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