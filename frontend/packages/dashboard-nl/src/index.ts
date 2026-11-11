// SPDX-License-Identifier: Apache-2.0
/** Public API of @jentsch/quasar-dashboard-nl (FR-NLC-15..19). The dashboard library never imports from here. */
export { createQuasarDashboardNl, createNlClient, useNlClient, parseSse, QDNL_CLIENT } from './plugin'
export type { QuasarDashboardNlOptions, NlClient, NlEvent } from './plugin'
export { messages as qdnlMessages } from './i18n'
export { default as QdNlChartPrompt } from './QdNlChartPrompt.vue'
