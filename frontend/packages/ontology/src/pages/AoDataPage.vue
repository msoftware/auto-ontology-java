<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAo } from '../plugin'

/** Catalog tree Database → Schema → Table → Column with details (FR-GOV-04). */
const ao = useAo()
const { t } = useI18n()
type Row = Record<string, unknown>
interface Node {
  id: string
  label: string
  kind: 'database' | 'schema' | 'table'
  icon: string
  lazy?: boolean
  children?: Node[]
  raw: Row
}
const nodes = ref<Node[]>([])
const selected = ref<string | null>(null)
const current = ref<Node | null>(null)
const columns = ref<Row[]>([])
const description = ref('')
const owner = ref('')
const error = ref<string | null>(null)
const index = new Map<string, Node>()

async function load() {
  const r = await ao.get<{ data: Row[] }>('/datasources/dbs')
  nodes.value = r.data.map((d) => reg({ id: String(d.id), label: String(d.name), kind: 'database', icon: 'storage', lazy: true, raw: d }))
}

function reg(n: Node): Node {
  index.set(n.id, n)
  return n
}

async function lazy({ node, done }: { node: Node; done: (children: Node[]) => void }) {
  if (node.kind === 'database') {
    const r = await ao.get<{ schemas: Row[] }>(`/schemas/${node.id}`)
    done(r.schemas.map((s) => reg({ id: String(s.id), label: String(s.schema_name), kind: 'schema', icon: 'folder', lazy: true, raw: s })))
  } else if (node.kind === 'schema') {
    const r = await ao.get<{ data: Row[] }>(`/tables/${node.id}`)
    done(r.data.map((tb) => reg({ id: String(tb.id), label: String(tb.name), kind: 'table', icon: tb.table_type === 'base table' ? 'table_chart' : 'visibility', raw: tb })))
  } else done([])
}

async function select(id: string | null) {
  selected.value = id
  error.value = null
  const n = id ? (index.get(id) ?? null) : null
  current.value = n
  columns.value = []
  description.value = String(n?.raw.description ?? '')
  owner.value = String(n?.raw.owner_id ?? '')
  if (n?.kind === 'table') {
    const r = await ao.get<{ data: { columns: Row[] } }>(`/columns/${n.id}`)
    columns.value = r.data.columns
  }
}

async function save(body: Row) {
  if (!current.value) return
  try {
    const r = await ao.patch<Row>(`/nodes/${current.value.id}`, body)
    Object.assign(current.value.raw, r)
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function saveColumn(c: Row, body: Row) {
  try {
    await ao.patch(`/nodes/${c.id}`, body)
    await select(selected.value)
  } catch (e) {
    error.value = (e as Error).message
  }
}

onMounted(load)
</script>

<template>
  <div class="row q-col-gutter-md q-pa-md" data-testid="ao-data">
    <div class="col-12 col-md-4">
      <div class="text-h6">{{ t('ao.data.databases') }}</div>
      <q-tree
        :nodes="nodes"
        node-key="id"
        label-key="label"
        :selected="selected"
        no-transition
        @lazy-load="lazy"
        @update:selected="select"
      />
    </div>
    <div class="col-12 col-md-8">
      <div v-if="!current" class="text-grey">{{ t('ao.data.selectNode') }}</div>
      <template v-else>
        <q-banner v-if="error" class="bg-negative text-white q-mb-sm">{{ error }}</q-banner>
        <div class="text-h6">{{ current.label }}</div>
        <q-input v-model="description" type="textarea" autogrow outlined dense :label="t('ao.common.description')" />
        <div class="row q-gutter-md items-center q-mt-sm">
          <q-btn color="primary" :label="t('ao.common.save')" @click="save({ description })" />
          <q-toggle :model-value="Boolean(current.raw.description_certified)" :label="t('ao.common.certified')" @update:model-value="(v: boolean) => save({ description_certified: v })" />
          <q-input v-model="owner" dense outlined :label="t('ao.common.owner')" style="width: 200px" @change="save({ owner_id: owner || null })" />
        </div>
        <q-markup-table v-if="current.kind === 'table'" flat bordered dense class="q-mt-md">
          <thead>
            <tr>
              <th class="text-left">{{ t('ao.common.name') }}</th>
              <th class="text-left">{{ t('ao.data.dataType') }}</th>
              <th class="text-left">{{ t('ao.common.description') }}</th>
              <th class="text-left">{{ t('ao.data.samples') }}</th>
              <th class="text-left">{{ t('ao.common.tags') }}</th>
              <th>{{ t('ao.common.certified') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in columns" :key="String(c.id)">
              <td>{{ c.column_name }}</td>
              <td>{{ c.data_type }}<span v-if="c.format"> ({{ c.format }})</span></td>
              <td>
                <q-input :model-value="String(c.description ?? '')" dense borderless autogrow @change="(v: string) => saveColumn(c, { description: v })" />
              </td>
              <td class="text-caption">{{ (c.sample_values as string[]).join(', ') }}</td>
              <td><q-chip v-for="tg in c.tags as Row[]" :key="String(tg.id)" dense size="sm">{{ tg.name }}</q-chip></td>
              <td class="text-center">
                <q-toggle :model-value="Boolean(c.description_certified)" dense @update:model-value="(v: boolean) => saveColumn(c, { description_certified: v })" />
              </td>
            </tr>
          </tbody>
        </q-markup-table>
      </template>
    </div>
  </div>
</template>
