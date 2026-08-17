import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev-прокси: /api уходит в Ktor-сервер анализатора (порт 8080).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    // 5173 занят LikeC4 dev-сервером — UI живёт на 5174.
    port: 5174,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
