<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAo } from '../plugin'

/** Global search with type and tag filters and hit counters; saves the search as a rule (FR-GOV-05/07). */
const ao = useAo()
const { t } = useI18n()
type Row = Record<string, unknown>
const TYPES = ['Database', 'Schema', 'Table', 'View', 'Column', 'CustomAnalysis', 'Term', 'ColumnAttribute', 'SqlAttribute', 'PqlAnalysis', 'Tag']
const term = ref('')
const objects = ref<string[]>([])
const tags = ref<string[]>([])
const description = ref(false)
const hits = ref<Row[]>([])
const counts = ref<Record<string, number>>({})
const allTags = ref<Row[]>([])
const error = ref<string | null>(null)
const ruleDialog = ref(false)
const ruleName = ref('')
const ruleTags = ref<string[]>([])

const body = () => ({
  search_term: term.value,
  text_match_option: 'contains',
  filters: {
    objects: objects.value.length ? objects.value : null,
    tags: tags.value.length ? tags.value : null,
    description: description.value,
  },
})

async function search() {
  error.value = null
  if (term.value.trim().length < 2) {
    hits.value = []
    counts.value = {}
    return
  }
  try {
    hits.value = (await ao.post<{ data: Row[] }>('/search/global-search', body())).data
    counts.value = (await ao.post<{ data: Record<string, number> }>('/search/global-search/count', body())).data
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function saveRule() {
  await ao.post('/rules', { ...body(), name: ruleName.value, tags: ruleTags.value.map((id) => ({ id })) })
  ruleDialog.value = false
  await search()
}

onMounted(async () => {
  allTags.value = (await ao.get<{ data: Row[] }>('/tags?limit=500')).data
})
</script>

<template>
  <div class="q-pa-md" data-testid="ao-discovery">
    <div class="row q-col-gutter-sm items-center">
      <q-input v-model="term" class="col-12 col-md-6" outlined dense debounce="300" :placeholder="t('ao.discovery.placeholder')" @update:model-value="search">
        <template #prepend><q-icon name="search" /></template>
      </q-input>
      <q-select v-model="objects" class="col-12 col-md-3" multiple dense outlined :options="TYPES" :label="t('ao.discovery.types')" @update:model-value="search" />
      <q-select
        v-model="tags"
        class="col-12 col-md-3"
        multiple
        dense
        outlined
        emit-value
        map-options
        :options="[{ label: t('ao.discovery.untagged'), value: '(blanks)' }, ...allTags.map((x) => ({ label: String(x.name), value: String(x.name) }))]"
        :label="t('ao.common.tags')"
        @update:model-value="search"
      />
    </div>
    <div class="row items-center q-mt-sm">
      <q-toggle v-model="description" :label="t('ao.discovery.searchDescription')" @update:model-value="search" />
      <q-chip v-for="(n, k) in counts" :key="k" dense outline>{{ k }}: {{ n }}</q-chip>
      <q-space />
      <q-btn v-if="hits.length" flat icon="rule" :label="t('ao.discovery.saveRule')" @click="ruleDialog = true" />
    </div>
    <q-banner v-if="error" class="bg-negative text-white">{{ error }}</q-banner>
    <div class="text-caption q-mt-sm">{{ t('ao.discovery.results', { n: hits.length }) }}</div>
    <q-list bordered separator>
      <q-item v-for="h in hits" :key="`${h.type}-${h.id}`">
        <q-item-section avatar><q-badge>{{ h.type }}</q-badge></q-item-section>
        <q-item-section>
          <q-item-label>{{ h.name }}</q-item-label>
          <q-item-label caption>{{ (h.breadcrumbs as Row[]).map((b) => b.name).join(' › ') }}</q-item-label>
          <q-item-label v-if="h.description" caption lines="2">{{ h.description }}</q-item-label>
          <q-item-label v-if="(h.synonyms as string[]).length" caption>≈ {{ (h.synonyms as string[]).join(', ') }}</q-item-label>
        </q-item-section>
      </q-item>
    </q-list>
    <q-dialog v-model="ruleDialog">
      <q-card style="min-width: 400px">
        <q-card-section class="q-gutter-sm">
          <q-input v-model="ruleName" dense outlined :label="t('ao.discovery.ruleName')" />
          <q-select v-model="ruleTags" multiple dense outlined emit-value map-options :options="allTags.map((x) => ({ label: String(x.name), value: String(x.id) }))" :label="t('ao.discovery.ruleTags')" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('ao.common.cancel')" v-close-popup />
          <q-btn color="primary" :disable="!ruleName || !ruleTags.length" :label="t('ao.common.save')" @click="saveRule" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>
