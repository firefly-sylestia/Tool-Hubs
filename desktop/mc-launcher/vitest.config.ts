import { resolve } from 'node:path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@renderer': resolve('src/renderer/src'),
      '@shared': resolve('src/shared')
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/renderer/src/**/*.spec.ts'],
    setupFiles: ['src/renderer/src/__tests__/setup.ts'],
    // Vuetify ships .css imports next to its components, which Node's ESM
    // loader cannot handle; inlining lets Vite transform them instead.
    server: { deps: { inline: ['vuetify'] } }
  }
})
