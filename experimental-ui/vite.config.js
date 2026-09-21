import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Bundled into Android assets as file:///android_asset/experimental/
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: '../app/src/main/assets/experimental',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
    cssCodeSplit: false,
  },
})
