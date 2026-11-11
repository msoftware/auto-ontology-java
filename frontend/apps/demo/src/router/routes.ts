// SPDX-License-Identifier: Apache-2.0
import type { RouteRecordRaw } from 'vue-router'

/** Host routes; the library pages are embedded here (the library knows no routes). */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: '/chat' },
      { path: 'chat', component: () => import('../pages/ChatPage.vue') },
      { path: 'discovery', component: () => import('../pages/LibPage.vue'), props: { page: 'discovery' } },
      { path: 'terms', component: () => import('../pages/TermsPage.vue') },
      { path: 'analysis', component: () => import('../pages/LibPage.vue'), props: { page: 'analysis' } },
      { path: 'exploration', component: () => import('../pages/LibPage.vue'), props: { page: 'exploration' } },
      { path: 'data', component: () => import('../pages/LibPage.vue'), props: { page: 'data' } },
      { path: 'analytics', component: () => import('../pages/LibPage.vue'), props: { page: 'analytics' } },
      { path: 'ask', component: () => import('../pages/AskPage.vue') },
      { path: 'explore', component: () => import('../pages/ExplorePage.vue') },
      { path: 'settings/:section?', component: () => import('../pages/SettingsPage.vue') },
    ],
  },
]
export default routes
