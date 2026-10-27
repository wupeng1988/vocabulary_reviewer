import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 端口可由 dev.sh 通过环境变量指定：
//   FRONTEND_PORT - 前端自身监听端口（默认 5173）
//   BACKEND_PORT  - 后端 Spring Boot 端口（默认 8080），/api、/audio、/h2-console 代理到这里
const frontendPort = parseInt(process.env.FRONTEND_PORT, 10) || 5173
const backendPort = parseInt(process.env.BACKEND_PORT, 10) || 8080
const backendTarget = `http://localhost:${backendPort}`
// 只有端口是显式指定时（dev.sh 启动）才 strictPort：占用就报错退出，
// 不静默顺延到下一个端口——否则「明明指定了 5190」实际跑在 5191，
// 而 dev.sh 打印的访问地址还是 5190，最容易踩坑。
// 直接 npm run dev（没给端口）时保持 vite 原行为：占用就自动顺延。
const strictPort = !!process.env.FRONTEND_PORT

export default defineConfig({
  plugins: [vue()],
  server: {
    port: frontendPort,
    strictPort,
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true
      },
      '/h2-console': {
        target: backendTarget,
        changeOrigin: true
      },
      '/audio': {
        target: backendTarget,
        changeOrigin: true
      }
    }
  }
})
