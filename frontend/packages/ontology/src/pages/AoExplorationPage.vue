<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useAo } from '../plugin'

use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer])

/** Data and semantic graph with detail cards, zoom and roam (FR-GOV-12; ECharts force layout). */
const ao = useAo()
const { t } = useI18n()
type Row = Record<string, unknown>
const view = ref<'data' | 'semantic'>('semantic')
const withColumns = ref(false)
const graph = ref<{ nodes: Row[]; links: Row[] }>({ nodes: [], links: [] })
const details = ref<Row | null>(null)
const zones = ref<Record<string, Row[]>>({})
const CATS = ['Database', 'Schema', 'Table', 'View', 'Column', 'Term']

async function load() {
  const path = view.value === 'semantic' ? '/exploration/semantic-graph' : `/exploration/graph?include_columns=${withColumns.value}`
  graph.value = (await ao.get<{ data: { nodes: Row[]; links: Row[] } }>(path)).data
  zones.value = (await ao.get<{ data: Record<string, Row[]> }>('/exploration/tables/zones')).data
}

const option = computed(() => ({
  tooltip: { formatter: (p: { data: Row }) => String(p.data.tooltip ?? p.data.name ?? '') },
  legend: [{ data: CATS }],
  series: [
    {
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      label: { show: true, position: 'right', fontSize: 10 },
      force: { repulsion: 160, edgeLength: 80 },
      categories: CATS.map((name) => ({ name })),
      data: graph.value.nodes.map((n) => ({
        id: String(n.id),
        name: String(n.name),
        category: Math.max(0, CATS.indexOf(String(n.type))),
        symbolSize: n.type === 'Term' ? 18 + Math.min(20, Number(n.column_attributes_count ?? 0)) : n.type === 'Column' ? 6 : 14,
        tooltip: `${n.type}: ${n.name}${zones.value[String(n.id)]?.length ? ` · ${zones.value[String(n.id)]!.map((z) => z.name).join(', ')}` : ''}`,
        raw: n,
      })),
      links: graph.value.links.map((l) => ({ source: String(l.source), target: String(l.target), label: { show: Boolean(l.label), formatter: String(l.label ?? '') } })),
    },
  ],
}))

async function click(event: unknown) {
  const p = event as { dataType?: string; data?: { raw?: Row } }
  if (p.dataType !== 'node' || !p.data?.raw) return
  const n = p.data.raw
  const kind = n.type === 'Term' ? 'terms' : n.type === 'Column' ? 'columns' : n.type === 'Table' || n.type === 'View' ? 'tables' : null
  details.value = kind ? (await ao.get<{ data: Row }>(`/exploration/${kind}/${n.id}/details`)).data : n
}

onMounted(load)
</script>

<template>
  <div class="column q-pa-md" style="height: 100%" data-testid="ao-exploration">
    <div class="row items-center q-gutter-sm">
      <q-btn-toggle v-model="view" no-caps :options="[{ label: t('ao.exploration.semanticGraph'), value: 'semantic' }, { label: t('ao.exploration.dataGraph'), value: 'data' }]" @update:model-value="load" />
      <q-toggle v-if="view === 'data'" v-model="withColumns" :label="t('ao.exploration.columns')" @update:model-value="load" />
      <q-btn flat icon="refresh" @click="load" />
    </div>
    <div class="row col">
      <div class="col" style="min-height: 500px">
        <VChart :option="option" autoresize style="height: 100%; min-height: 500px" @click="click" />
      </div>
      <q-card v-if="details" flat bordered class="col-4 q-ml-sm scroll" style="max-height: 600px">
        <q-card-section>
          <div class="row items-center">
            <div class="text-h6">{{ details.name }}</div>
            <q-space />
            <q-btn flat round dense icon="close" @click="details = null" />
          </div>
          <div class="text-caption">{{ details.description }}</div>
          <template v-for="(v, k) in details" :key="k">
            <div v-if="Array.isArray(v) && v.length" class="q-mt-sm">
              <div class="text-subtitle2">{{ k }}</div>
              <q-chip v-for="(x, i) in v as Row[]" :key="i" dense>{{ typeof x === 'object' ? (x.name ?? x.sql_full_query ?? JSON.stringify(x)) : x }}</q-chip>
            </div>
          </template>
        </q-card-section>
      </q-card>
    </div>
  </div>
</template>
