<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAo } from '../plugin'

/** Usage report from conversation_analytics (FR-GOV-13). */
const ao = useAo()
const { t } = useI18n()
const rows = ref<Array<Record<string, unknown>>>([])
const columns = computed(() => [
  { name: 'user_id', label: t('ao.analytics.user'), field: 'user_id', align: 'left' as const },
  { name: 'source', label: t('ao.analytics.source'), field: 'source', align: 'left' as const },
  { name: 'question', label: t('ao.analytics.question'), field: 'question', align: 'left' as const },
  { name: 'reasoning', label: t('ao.analytics.reasoning'), field: 'reasoning', align: 'left' as const, style: 'max-width: 320px; white-space: pre-wrap' },
  { name: 'sql_code', label: 'SQL', field: 'sql_code', align: 'left' as const, style: 'max-width: 320px; white-space: pre-wrap; font-family: monospace' },
  { name: 'question_timestamp', label: t('ao.analytics.time'), field: 'question_timestamp', align: 'left' as const, sortable: true },
])
onMounted(async () => {
  rows.value = (await ao.get<{ data: Array<Record<string, unknown>> }>('/analytics?limit=500')).data
})
</script>

<template>
  <div class="q-pa-md" data-testid="ao-analytics">
    <div class="text-h6">{{ t('ao.analytics.title') }}</div>
    <q-table flat bordered dense :rows="rows" :columns="columns" row-key="id" wrap-cells />
  </div>
</template>
