/// <reference types="vitest/config" />
import path from 'node:path'
import babel from '@rolldown/plugin-babel'
import tailwindcss from '@tailwindcss/vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 다른 프로젝트와 포트가 겹치면 ARCHE_WEB_PORT·ARCHE_API_URL로 바꾼다 (예: 5174 → http://localhost:8081)
const webPort = Number(process.env.ARCHE_WEB_PORT ?? 5173)
const apiUrl = process.env.ARCHE_API_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), babel({ presets: [reactCompilerPreset()] }), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
  },
  server: {
    port: webPort,
    strictPort: true,
    proxy: {
      '/api': apiUrl,
      '/actuator': apiUrl,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
