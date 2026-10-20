/// <reference types="vitest/config" />

import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
  plugins: [react()],
  server: {
    port: 3000,
    host: true,
    proxy: {
      '/user-service': env.USER_SERVICE_PROXY_TARGET || 'http://localhost:8081',
      // When Vite runs inside WSL and Micronaut runs from Windows/IntelliJ,
      // localhost points at different network namespaces. Override this with
      // PAYMENT_SERVICE_PROXY_TARGET when the WSL host address changes.
      '/payment-service': env.PAYMENT_SERVICE_PROXY_TARGET || 'http://localhost:8082',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
  }
})
