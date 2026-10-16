import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Same-origin API calls in dev: the browser talks to Vite, Vite forwards /api to Spring Boot.
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
        // Keep the browser's Host header (localhost:5173). The backend builds the Google redirect URI and the
        // post-sign-in redirect from it, so both must point back at this dev server, not at :8080.
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
  },
})
