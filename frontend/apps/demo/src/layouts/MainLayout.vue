<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Dark } from 'quasar'

/** Left navigation for everybody (no user menu, no login, FR-FE-01). */
const { t, locale } = useI18n()
const drawer = ref(true)
const items = [
  { to: '/chat', icon: 'chat', key: 'chat' },
  { to: '/discovery', icon: 'search', key: 'discovery' },
  { to: '/terms', icon: 'menu_book', key: 'terms' },
  { to: '/analysis', icon: 'functions', key: 'analysis' },
  { to: '/exploration', icon: 'hub', key: 'exploration' },
  { to: '/data', icon: 'storage', key: 'data' },
  { to: '/analytics', icon: 'insights', key: 'analytics' },
  { to: '/ask', icon: 'auto_awesome', key: 'ask' },
  { to: '/explore', icon: 'bar_chart', key: 'explore' },
  { to: '/settings', icon: 'settings', key: 'settings' },
]
</script>

<template>
  <q-layout view="hHh Lpr lFf">
    <q-header elevated>
      <q-toolbar>
        <q-btn flat round dense icon="menu" @click="drawer = !drawer" />
        <q-toolbar-title>{{ t('app.title') }}</q-toolbar-title>
        <slot name="header-actions" />
        <q-btn-toggle v-model="locale" flat dense toggle-color="white" :options="[{ label: 'DE', value: 'de' }, { label: 'EN', value: 'en' }]" />
        <q-btn flat round dense :icon="Dark.isActive ? 'light_mode' : 'dark_mode'" :aria-label="t('app.dark')" @click="Dark.toggle()" />
      </q-toolbar>
    </q-header>
    <q-drawer v-model="drawer" show-if-above bordered :width="210">
      <q-list>
        <q-item v-for="i in items" :key="i.to" :to="i.to" clickable>
          <q-item-section avatar><q-icon :name="i.icon" /></q-item-section>
          <q-item-section>{{ i.key === 'ask' || i.key === 'explore' ? t(`app.${i.key}`) : t(`ao.nav.${i.key}`) }}</q-item-section>
        </q-item>
      </q-list>
    </q-drawer>
    <q-page-container>
      <q-page style="height: calc(100vh - 50px)">
        <router-view />
      </q-page>
    </q-page-container>
  </q-layout>
</template>
