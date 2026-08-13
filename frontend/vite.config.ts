import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: {
      // the backend is a separate ktor process (./gradlew run); only the API
      // is proxied here, the UI is served by vite itself. The target is the
      // backend's server.port in config.jsonc (default 8080) — update it if
      // you change that.
      '/api': 'http://localhost:8080',
    },
  },
})
