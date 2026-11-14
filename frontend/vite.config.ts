import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 로컬 개발 시 /api 요청을 백엔드(8080)로 넘겨 CORS 설정 없이 호출한다
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
