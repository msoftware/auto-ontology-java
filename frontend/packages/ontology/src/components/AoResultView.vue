<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { QdChartRenderer } from '@jentsch/quasar-dashboard'
import { chartParams, rowsToQueryResult, toCsv, type ChartBlock } from '../results'

const props = defineProps<{
  rows: Array<Record<string, unknown>> | null
  charts?: ChartBlock[]
  sql?: string | null
}>()
const { t } = useI18n()
const tab = ref<'chart' | 'table'>(props.charts?.length ? 'chart' : 'table')
// charts arrive after the rows in a live run: show the chart as soon as the first one is there
watch(
  () => props.charts?.length ?? 0,
  (n, before) => {
    if (n > 0 && !before) tab.value = 'chart'
  },
)
const slide = ref('0')
const columns = computed(() =>
  Object.keys(props.rows?.[0] ?? {}).map((c) => ({ name: c, label: c, field: c, align: 'left' as const, sortable: true })),
)
const result = computed(() => rowsToQueryResult(props.rows ?? [], props.sql ?? undefined))

function downloadCsv() {
  const blob = new Blob([toCsv(props.rows ?? [])], { type: 'text/csv' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'result.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div v-if="rows && rows.length" class="ao-result q-mt-sm" data-testid="ao-result">
    <div class="row items-center q-gutter-sm">
      <q-btn-toggle
        v-if="charts?.length"
        v-model="tab"
        dense
        no-caps
        unelevated
        toggle-color="primary"
        :options="[
          { label: 'Chart', value: 'chart', icon: 'insights' },
          { label: t('ao.chat.rows', { n: rows.length }), value: 'table', icon: 'table_rows' },
        ]"
      />
      <span v-else class="text-caption">{{ t('ao.chat.rows', { n: rows.length }) }}</span>
      <q-space />
      <q-btn flat dense icon="download" :label="t('ao.chat.csv')" data-testid="ao-result-csv" @click="downloadCsv" />
    </div>
    <div v-if="tab === 'chart' && charts?.length">
      <q-carousel v-if="charts.length > 1" v-model="slide" animated arrows navigation swipeable height="340px">
        <q-carousel-slide v-for="(c, i) in charts" :key="i" :name="String(i)">
          <QdChartRenderer :params="chartParams(c)" :results="[result]" status="success" style="height: 300px" />
        </q-carousel-slide>
      </q-carousel>
      <div v-else style="height: 320px">
        <QdChartRenderer :params="chartParams(charts[0]!)" :results="[result]" status="success" />
      </div>
      <div class="text-caption text-grey">{{ t('ao.chat.sqlOnlyView') }}</div>
    </div>
    <q-table
      v-else
      dense
      flat
      bordered
      :rows="rows"
      :columns="columns"
      :rows-per-page-options="[10, 25, 100, 0]"
      row-key="__index"
    />
  </div>
</template>
