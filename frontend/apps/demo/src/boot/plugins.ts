// SPDX-License-Identifier: Apache-2.0
import { defineBoot } from '#q-app'
import { createPinia } from 'pinia'
import { createQuasarDashboard } from '@jentsch/quasar-dashboard'
import '@jentsch/quasar-dashboard/style.css'
import { createQuasarOntology } from '@jentsch/quasar-ontology'
import { createQuasarDashboardNl } from '@jentsch/quasar-dashboard-nl'

/**
 * Both plugins share the same request options (NFR-QD-06a). quasar-dashboard uses the host's Pinia
 * (its stores back QdChartRenderer, explore and dashboards), so the host installs it first.
 */
export default defineBoot(({ app }) => {
  app.use(createPinia())
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? ''
  app.use(createQuasarDashboard({ baseUrl }))
  app.use(createQuasarOntology({ baseUrl, apiPrefix: import.meta.env.VITE_AO_API_PREFIX ?? '/api' }))
  app.use(createQuasarDashboardNl({ baseUrl, apiPrefix: `${import.meta.env.VITE_AO_API_PREFIX ?? '/api'}/nl-chart` }))
})
