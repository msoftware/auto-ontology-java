// SPDX-License-Identifier: Apache-2.0
import { inject, type App, type InjectionKey, type Plugin } from 'vue'
import { I18nInjectionKey, type I18n } from 'vue-i18n'
import { createAoClient, type AoClient, type QuasarOntologyOptions } from './api'
import { messages } from './i18n'

export const AO_CLIENT: InjectionKey<AoClient> = Symbol('ao-client')

/** The API client inside library components. */
export function useAo(): AoClient {
  const c = inject(AO_CLIENT, null)
  if (!c) throw new Error('[@jentsch/quasar-ontology] install createQuasarOntology() first')
  return c
}

/**
 * Vue plugin of the Auto-Ontology UI (NFR-QD-07): provides the API client and merges the `ao`
 * messages into the host's vue-i18n instance. Same options as createQuasarDashboard (NFR-QD-06a).
 */
export function createQuasarOntology(options: QuasarOntologyOptions = {}): Plugin {
  return {
    install(app: App) {
      app.provide(AO_CLIENT, createAoClient(options))
      const symbol = (app as App & { __VUE_I18N_SYMBOL__?: symbol }).__VUE_I18N_SYMBOL__
      const i18n = app.runWithContext(
        () => (symbol ? inject<I18n | null>(symbol, null) : null) ?? inject<I18n | null>(I18nInjectionKey, null),
      )
      if (!i18n) throw new Error('[@jentsch/quasar-ontology] vue-i18n (legacy: false) must be installed first')
      i18n.global.mergeLocaleMessage('en', messages.en)
      i18n.global.mergeLocaleMessage('de', messages.de)
    },
  }
}
