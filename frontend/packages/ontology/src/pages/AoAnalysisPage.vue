<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAo } from '../plugin'

/** Custom analyses (SQL) and PQL analyses (FR-GOV-11). */
const ao = useAo()
const { t } = useI18n()
type Row = Record<string, unknown>
const mode = ref<'sql' | 'pql'>('sql')
const items = ref<Row[]>([])
const query = ref('')
const dialog = ref(false)
const form = ref<{ id?: string; name: string; description: string; body: string }>({ name: '', description: '', body: '' })
const error = ref<string | null>(null)

async function load() {
  items.value =
    mode.value === 'sql'
      ? (await ao.get<{ data: Row[] }>(`/custom-analyses?query=${encodeURIComponent(query.value)}`)).data
      : (await ao.get<{ data: Row[] }>('/pql-analyses')).data
}

function open(a?: Row) {
  error.value = null
  form.value = a
    ? { id: String(a.id), name: String(a.name), description: String(a.description ?? ''), body: String((mode.value === 'sql' ? a.sql : a.pql) ?? '') }
    : { name: '', description: '', body: '' }
  dialog.value = true
}

async function save() {
  const base = mode.value === 'sql' ? '/custom-analyses' : '/pql-analyses'
  const payload = mode.value === 'sql'
    ? { name: form.value.name, description: form.value.description, sql: form.value.body }
    : { name: form.value.name, description: form.value.description, pql: form.value.body }
  try {
    if (mode.value === 'sql') await ao.post('/custom-analyses/validate', payload)
    if (form.value.id) await ao.put(`${base}/${form.value.id}`, payload)
    else await ao.post(base, payload)
    dialog.value = false
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function remove(a: Row) {
  if (!window.confirm(String(a.name))) return
  await ao.del(`${mode.value === 'sql' ? '/custom-analyses' : '/pql-analyses'}/${a.id}`)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="q-pa-md" data-testid="ao-analysis">
    <div class="row items-center q-gutter-sm">
      <q-btn-toggle v-model="mode" no-caps :options="[{ label: t('ao.analysis.custom'), value: 'sql' }, { label: t('ao.analysis.pql'), value: 'pql' }]" @update:model-value="load" />
      <q-input v-if="mode === 'sql'" v-model="query" dense outlined debounce="300" :label="t('ao.common.search')" @update:model-value="load" />
      <q-space />
      <q-btn color="primary" icon="add" :label="t('ao.analysis.newAnalysis')" @click="open()" />
    </div>
    <q-list bordered separator class="q-mt-md">
      <q-item v-for="a in items" :key="String(a.id)">
        <q-item-section>
          <q-item-label>{{ a.name }}</q-item-label>
          <q-item-label caption>{{ a.description }}</q-item-label>
          <q-item-label caption><code>{{ mode === 'sql' ? a.sql : a.pql }}</code></q-item-label>
        </q-item-section>
        <q-item-section side>
          <div class="row no-wrap">
            <q-btn flat round dense icon="edit" @click="open(a)" />
            <q-btn flat round dense icon="delete" @click="remove(a)" />
          </div>
        </q-item-section>
      </q-item>
      <q-item v-if="!items.length"><q-item-section>{{ t('ao.common.empty') }}</q-item-section></q-item>
    </q-list>
    <q-dialog v-model="dialog">
      <q-card style="min-width: 560px">
        <q-card-section class="q-gutter-sm">
          <q-banner v-if="error" class="bg-negative text-white">{{ error }}</q-banner>
          <q-input v-model="form.name" dense outlined :label="t('ao.common.name')" />
          <q-input v-model="form.description" dense outlined autogrow :label="t('ao.common.description')" />
          <q-input v-model="form.body" type="textarea" outlined autogrow :label="mode === 'sql' ? 'SQL' : 'PQL'" input-style="font-family: monospace" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('ao.common.cancel')" v-close-popup />
          <q-btn color="primary" :label="t('ao.common.save')" @click="save" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>
