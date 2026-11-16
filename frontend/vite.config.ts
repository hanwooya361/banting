import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 다른 프로젝트 기본 포트(5173)와 겹치지 않게 5180 고정. 사용 중이면 다른 포트로 넘어가지 않고 실패
    port: 5180,
    strictPort: true,
    // 로컬 개발 시 /api 요청을 백엔드(8090)로 넘겨 CORS 설정 없이 호출한다
    proxy: {
      '/api': 'http://localhost:8090',
    },
  },
})
