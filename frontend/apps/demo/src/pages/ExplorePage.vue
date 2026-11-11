<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { QdChartBuilder, useExploreStore } from '@jentsch/quasar-dashboard'
import { QdNlChartPrompt } from '@jentsch/quasar-dashboard-nl'
import { openInBuilder } from '../open-in-builder'

/** Chart builder with the slot integration "adjust with a question" (FR-NLC-18). */
const { t } = useI18n()
const explore = useExploreStore()
const dialog = ref(false)
const current = ref<Record<string, unknown> | null>(null)

function adjust() {
  current.value = { ...(explore.params as unknown as Record<string, unknown>) }
  dialog.value = true
}

async function apply(p: { datasetId: number; vizType: string; params: Record<string, unknown> }) {
  dialog.value = false
  await openInBuilder(p)
}
</script>

<template>
  <div class="q-pa-md" style="height: 100%">
    <QdChartBuilder>
      <template #header-actions>
        <q-btn flat icon="auto_awesome" :label="t('app.adjust')" data-testid="qdnl-adjust" @click="adjust" />
      </template>
    </QdChartBuilder>
    <q-dialog v-model="dialog" full-width>
      <q-card class="q-pa-md">
        <QdNlChartPrompt :initial-params="current" @open-builder="apply" />
      </q-card>
    </q-dialog>
  </div>
</template>
