// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from '#q-app'
import { fileURLToPath } from 'node:url'

/** quasar-dashboard is consumed from its sources (like its own demo app does). */
const qdSrc = (path: string) =>
  fileURLToPath(new URL(`../../../../quasar-dashboard/packages/dashboard/src/${path}`, import.meta.url))

/** `AO_BACKEND=http://localhost:8080` (default) forwards /api to the Spring Boot app. */
const backend = process.env.AO_BACKEND ?? 'http://localhost:8080'
const apiPrefix = process.env.VITE_AO_API_PREFIX ?? '/api'

export default defineConfig(() => ({
  boot: ['i18n', 'plugins'],
  css: ['app.scss'],
  extras: ['roboto-font', 'material-icons'],
  build: {
    target: {},
    vueRouterMode: 'history',
    env: { clientPrefix: 'VITE_' },
    typescript: {
      strict: true,
      vueShim: false,
      extendTsConfig(tsConfig) {
        tsConfig.compilerOptions ??= {}
        tsConfig.compilerOptions.paths = {
          ...tsConfig.compilerOptions.paths,
          '@jentsch/quasar-dashboard': ['./../../../../../quasar-dashboard/packages/dashboard/src/index.ts'],
        }
      },
    },
    extendViteConf(viteConf) {
      const aliases = [
        { find: /^@jentsch\/quasar-dashboard$/, replacement: qdSrc('index.ts') },
        { find: /^@jentsch\/quasar-dashboard\/style\.css$/, replacement: qdSrc('styles/index.scss') },
      ]
      viteConf.resolve ??= {}
      const current = viteConf.resolve.alias
      viteConf.resolve.alias = [
        ...aliases,
        ...(Array.isArray(current)
          ? current
          : Object.entries(current ?? {}).map(([find, replacement]) => ({ find, replacement: String(replacement) }))),
      ]
      // one Vue / Quasar / ECharts instance for the app, both libraries and the dashboard sources
      viteConf.resolve.dedupe = ['vue', 'quasar', 'vue-i18n', 'pinia', 'echarts', 'vue-echarts', 'vue-router']
    },
  },
  devServer: {
    open: false,
    port: 9100,
    proxy: {
      [apiPrefix]: { target: backend, changeOrigin: true },
    },
  },
  framework: {
    config: { dark: 'auto' },
    plugins: ['Notify', 'Dialog', 'Dark'],
  },
}))
