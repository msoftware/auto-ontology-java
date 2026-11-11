// SPDX-License-Identifier: Apache-2.0
import { defineBoot } from '#q-app'
import { createI18n } from 'vue-i18n'

/** The host's vue-i18n instance; both libraries merge their messages into it. */
export const i18n = createI18n({
  legacy: false,
  locale: navigator.language.startsWith('de') ? 'de' : 'en',
  fallbackLocale: 'en',
  messages: {
    en: { app: { title: 'Auto-Ontology', language: 'Language', dark: 'Dark mode', ask: 'Chart from question', explore: 'Chart builder', adjust: 'Adjust with a question' } },
    de: { app: { title: 'Auto-Ontology', language: 'Sprache', dark: 'Dunkelmodus', ask: 'Chart per Frage', explore: 'Chart-Builder', adjust: 'Mit Frage anpassen' } },
  },
})

export default defineBoot(({ app }) => {
  app.use(i18n)
})
