<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { QdChartRenderer, getVizPlugin, type ChartParams, type QueryResult } from '@jentsch/quasar-dashboard'
import { useNlClient, type NlEvent } from './plugin'

/**
 * FR-NLC-16: question → live steps → preview (QdChartRenderer, params mode) → save / open in the
 * chart builder / discard; follow-up refinement as a diff of the current params (FR-NLC-12).
 */
const props = defineProps<{ initialParams?: Record<string, unknown> | null; datasetId?: number | null; dashboardId?: number | null }>()
const emit = defineEmits<{
  (e: 'saved', chart: Record<string, unknown>): void
  (e: 'open-builder', payload: { datasetId: number; vizType: string; params: Record<string, unknown> }): void
}>()
const { t } = useI18n()
const nl = useNlClient()

interface Suggestion {
  mode: 'semantic' | 'sql'
  dataset_id: number | null
  viz_type: string | null
  params: Record<string, unknown>
  slice_name?: string
  description?: string
  explanation?: string
  assumptions?: string[]
  alternatives?: Array<{ viz_type: string; reason: string }>
  warnings?: string[]
  results?: QueryResult[]
  sql?: string
}

const question = ref('')
const followUp = ref('')
const datasets = ref<Array<Record<string, unknown>>>([])
const dataset = ref<number | null>(props.datasetId ?? null)
const steps = ref<Array<{ node: string; label: string; done: boolean; thought?: string }>>([])
const suggestion = ref<Suggestion | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)
const showSql = ref(false)
const saveName = ref('')
const saveDescription = ref('')

/** FR-NLC-13: defaults of the plugin added on the client, validated against nothing but the plugin. */
const previewParams = computed<ChartParams | null>(() => {
  const s = suggestion.value
  if (!s?.viz_type) return null
  const plugin = getVizPlugin(s.viz_type)
  const defaults = plugin ? (plugin.defaults() as Record<string, unknown>) : {}
  return { ...defaults, ...s.params, viz_type: s.viz_type, datasource: s.dataset_id ? `${s.dataset_id}__table` : s.params.datasource } as unknown as ChartParams
})

async function ask(text: string, current?: Record<string, unknown> | null) {
  if (!text.trim() || busy.value) return
  busy.value = true
  error.value = null
  steps.value = []
  const previous = suggestion.value
  try {
    await nl.suggest(
      {
        question: text,
        ...(dataset.value ? { dataset_id: dataset.value } : current && previous?.dataset_id ? { dataset_id: previous.dataset_id } : {}),
        ...(current ? { current_params: current } : {}),
        ...(props.dashboardId ? { dashboard_id: props.dashboardId } : {}),
      },
      (e: NlEvent) => {
        if (e.type === 'step') {
          if (e.phase === 'start') steps.value.push({ node: String(e.node), label: String(e.label ?? e.node), done: false })
          else {
            const s = [...steps.value].reverse().find((x) => x.node === e.node && !x.done)
            if (s) {
              s.done = true
              if (e.thought) s.thought = String(e.thought)
            }
          }
        } else if (e.type === 'chart_suggestion') {
          suggestion.value = e as unknown as Suggestion
          saveName.value = String(e.slice_name ?? text).slice(0, 250)
          saveDescription.value = String(e.description ?? '')
        } else if (e.type === 'error') error.value = String(e.message)
      },
    )
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    void ask(question.value, props.initialParams ?? null)
  }
}

function variant(vizType: string) {
  if (suggestion.value) void ask(`${question.value} — als ${vizType}`, { ...suggestion.value.params, viz_type: vizType })
}

async function save() {
  const s = suggestion.value
  if (!s || s.mode !== 'semantic' || !s.dataset_id || !s.viz_type || !previewParams.value) return
  const chart = await nl.saveChart({
    slice_name: saveName.value,
    description: saveDescription.value || null,
    viz_type: s.viz_type,
    datasource_id: s.dataset_id,
    datasource_type: 'table',
    params: previewParams.value,
    query_context: (s as unknown as { query_context?: unknown }).query_context ?? null,
    ...(props.dashboardId ? { dashboards: [props.dashboardId] } : {}),
  })
  emit('saved', chart)
}

function openBuilder() {
  const s = suggestion.value
  if (s?.dataset_id && s.viz_type) emit('open-builder', { datasetId: s.dataset_id, vizType: s.viz_type, params: s.params })
}

onMounted(async () => {
  try {
    datasets.value = await nl.datasets()
  } catch {
    datasets.value = []
  }
})
</script>

<template>
  <div class="qdnl-prompt q-gutter-sm" data-testid="qdnl-prompt">
    <div class="row q-gutter-sm items-start">
      <q-input
        v-model="question"
        class="col"
        type="textarea"
        autogrow
        outlined
        :placeholder="t('qdnl.placeholder')"
        :hint="t('qdnl.hint')"
        data-testid="qdnl-question"
        @keydown="onKey"
      />
      <q-select
        v-model="dataset"
        style="min-width: 200px"
        outlined
        dense
        emit-value
        map-options
        clearable
        :label="t('qdnl.dataset')"
        :options="datasets.map((d) => ({ label: String(d.table_name), value: Number(d.id) }))"
      />
      <q-btn color="primary" icon="auto_awesome" :label="t('qdnl.ask')" :loading="busy" data-testid="qdnl-ask" @click="ask(question, initialParams ?? null)" />
    </div>
    <q-list v-if="steps.length" dense>
      <q-item v-for="(s, i) in steps" :key="i">
        <q-item-section avatar><q-spinner v-if="!s.done" size="16px" /><q-icon v-else name="check" color="positive" /></q-item-section>
        <q-item-section><q-item-label>{{ s.label }}</q-item-label><q-item-label v-if="s.thought" caption>{{ s.thought }}</q-item-label></q-item-section>
      </q-item>
    </q-list>
    <q-banner v-if="error" class="bg-negative text-white" data-testid="qdnl-error">{{ t('qdnl.error', { message: error }) }}</q-banner>
    <div v-if="!suggestion && !busy" class="text-grey">
      <slot name="empty">{{ t('qdnl.empty') }}</slot>
    </div>
    <q-card v-if="suggestion" flat bordered data-testid="qdnl-suggestion">
      <q-card-section style="height: 360px">
        <QdChartRenderer
          v-if="previewParams && suggestion.mode === 'semantic'"
          :params="previewParams"
          :title="saveName"
        />
        <QdChartRenderer
          v-else-if="previewParams && suggestion.results"
          :params="previewParams"
          :results="suggestion.results"
          status="success"
          :title="saveName"
        />
      </q-card-section>
      <q-card-section class="q-gutter-xs">
        <div v-if="suggestion.mode === 'sql'" class="text-warning">{{ t('qdnl.sqlMode') }}</div>
        <div v-if="suggestion.explanation"><b>{{ t('qdnl.explanation') }}:</b> {{ suggestion.explanation }}</div>
        <div v-if="suggestion.assumptions?.length"><b>{{ t('qdnl.assumptions') }}:</b> {{ suggestion.assumptions.join('; ') }}</div>
        <div v-if="suggestion.warnings?.length" class="text-orange"><b>{{ t('qdnl.warnings') }}:</b> {{ suggestion.warnings.join('; ') }}</div>
        <div v-if="suggestion.alternatives?.length">
          <b>{{ t('qdnl.alternatives') }}:</b>
          <q-chip v-for="a in suggestion.alternatives" :key="a.viz_type" clickable dense outline :title="a.reason" @click="variant(a.viz_type)">{{ a.viz_type }}</q-chip>
        </div>
        <div v-if="suggestion.sql">
          <q-btn flat dense no-caps size="sm" :label="t('qdnl.showSql')" @click="showSql = !showSql" />
          <pre v-if="showSql" style="white-space: pre-wrap">{{ suggestion.sql }}</pre>
        </div>
      </q-card-section>
      <q-card-section class="row q-gutter-sm items-center">
        <q-input v-model="followUp" class="col" dense outlined :placeholder="t('qdnl.followUp')" data-testid="qdnl-followup" @keydown.enter="ask(followUp, { ...suggestion.params, viz_type: suggestion.viz_type })" />
        <q-btn flat :label="t('qdnl.refine')" @click="ask(followUp, { ...suggestion.params, viz_type: suggestion.viz_type })" />
      </q-card-section>
      <q-card-actions v-if="suggestion.mode === 'semantic'" class="q-gutter-sm">
        <q-input v-model="saveName" dense outlined :label="t('qdnl.name')" maxlength="250" style="min-width: 260px" />
        <q-input v-model="saveDescription" dense outlined :label="t('qdnl.description')" />
        <q-btn color="primary" icon="save" :label="t('qdnl.save')" data-testid="qdnl-save" @click="save" />
        <q-btn flat icon="tune" :label="t('qdnl.openBuilder')" data-testid="qdnl-open-builder" @click="openBuilder" />
        <q-btn flat icon="close" :label="t('qdnl.discard')" @click="suggestion = null" />
        <slot name="actions" :suggestion="suggestion" />
      </q-card-actions>
    </q-card>
  </div>
</template>
