<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { Notify } from 'quasar'
import { useI18n } from 'vue-i18n'
import { QdNlChartPrompt } from '@jentsch/quasar-dashboard-nl'
import { openInBuilder } from '../open-in-builder'

/** /ask (FR-NLC-21): chart from a question; "open in builder" fills the explore store (FR-NLC-17). */
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const current = parseParams(route.query.params)

function parseParams(raw: unknown): Record<string, unknown> | null {
  if (!raw) return null
  try {
    const v: unknown = JSON.parse(String(raw))
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

async function open(p: { datasetId: number; vizType: string; params: Record<string, unknown> }) {
  await openInBuilder(p)
  await router.push('/explore')
}
</script>

<template>
  <div class="q-pa-md">
    <QdNlChartPrompt
      :initial-params="current"
      @saved="() => Notify.create({ type: 'positive', message: t('qdnl.saved') })"
      @open-builder="open"
    />
  </div>
</template>
