import { resolve } from 'node:path'
import vue from '@vitejs/plugin-vue'
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'

const shared = resolve('src/shared')

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    resolve: { alias: { '@shared': shared } },
    build: { rollupOptions: { input: resolve('src/main/index.ts') } }
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    resolve: { alias: { '@shared': shared } },
    build: { rollupOptions: { input: resolve('src/preload/index.ts') } }
  },
  renderer: {
    root: resolve('src/renderer'),
    plugins: [vue()],
    resolve: { alias: { '@renderer': resolve('src/renderer/src'), '@shared': shared } },
    build: {
      rollupOptions: { input: resolve('src/renderer/index.html') }
    }
  }
})
