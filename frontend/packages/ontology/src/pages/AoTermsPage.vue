<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAo } from '../plugin'

/** Glossary (FR-GOV-01/02): list, detail, edit, certify, column attributes, SQL attributes. */
const props = defineProps<{ termId?: string | null }>()
const ao = useAo()
const { t } = useI18n()

type Row = Record<string, unknown>
const query = ref('')
const terms = ref<Row[]>([])
const counts = ref<Record<string, { attrs: number; sql: number; tables: number }>>({})
const total = ref(0)
const selected = ref<Row | null>(null)
const attrs = ref<Row[]>([])
const sqlAttrs = ref<Row[]>([])
const edit = ref({ name: '', description: '' })
const error = ref<string | null>(null)
const sqlDialog = ref(false)
const sqlForm = ref<{ id?: string; name: string; description: string; expression: string }>({ name: '', description: '', expression: '' })
const sqlMessage = ref<string | null>(null)

async function load() {
  const r = await ao.get<{ terms: Row[]; total: number; column_attribute_counts: Row[]; sql_attribute_counts: Row[]; related_counts: Row[] }>(
    `/terms?limit=200&query=${encodeURIComponent(query.value)}`,
  )
  terms.value = r.terms
  total.value = r.total
  const c: Record<string, { attrs: number; sql: number; tables: number }> = {}
  for (const x of r.terms) c[String(x.id)] = { attrs: 0, sql: 0, tables: 0 }
  r.column_attribute_counts.forEach((x) => (c[String(x.term_id)]!.attrs = Number(x.count)))
  r.sql_attribute_counts.forEach((x) => (c[String(x.term_id)]!.sql = Number(x.count)))
  r.related_counts.forEach((x) => (c[String(x.term_id)]!.tables = Number(x.count)))
  counts.value = c
}

async function open(id: string) {
  error.value = null
  const d = await ao.get<{ data: Row }>(`/terms/${id}`)
  selected.value = d.data
  edit.value = { name: String(d.data.name ?? ''), description: String(d.data.description ?? '') }
  attrs.value = (await ao.get<{ data: Row[] }>(`/terms/${id}/column-attributes`)).data
  sqlAttrs.value = (await ao.get<{ data: Row[] }>(`/terms/${id}/sql-attributes`)).data
}

async function patch(body: Row) {
  if (!selected.value) return
  try {
    await ao.patch(`/terms/${selected.value.id}`, body)
    await open(String(selected.value.id))
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}

/** Runs an action on the selected term and shows a failure in the page. */
async function act(fn: () => Promise<unknown>) {
  error.value = null
  try {
    await fn()
    if (selected.value) await open(String(selected.value.id))
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function patchAttr(a: Row, body: Row) {
  await act(() => ao.patch(`/terms/${selected.value!.id}/column-attributes/${a.id}`, body))
}

function newSql() {
  sqlForm.value = { name: '', description: '', expression: '' }
  sqlMessage.value = null
  sqlDialog.value = true
}

function editSql(a: Row) {
  sqlForm.value = { id: String(a.id), name: String(a.name), description: String(a.description ?? ''), expression: String(a.expression) }
  sqlMessage.value = null
  sqlDialog.value = true
}

async function validateSql() {
  try {
    await ao.post('/sql-attributes/validate', { expression: sqlForm.value.expression, term_id: selected.value?.id })
    sqlMessage.value = t('ao.terms.valid')
  } catch (e) {
    sqlMessage.value = (e as Error).message
  }
}

async function suggest() {
  if (!sqlForm.value.id) return
  try {
    const r = await ao.get<{ data: { description_suggestion: string } }>(`/sql-attributes/${sqlForm.value.id}/description-suggestion`)
    sqlForm.value.description = r.data.description_suggestion
  } catch (e) {
    sqlMessage.value = (e as Error).message
  }
}

async function saveSql() {
  try {
    if (sqlForm.value.id) {
      await ao.put(`/sql-attributes/${sqlForm.value.id}`, sqlForm.value)
    } else {
      await ao.post('/sql-attributes', { ...sqlForm.value, term_id: selected.value?.id, source: 'manual' })
    }
    sqlDialog.value = false
    await open(String(selected.value!.id))
  } catch (e) {
    sqlMessage.value = (e as Error).message
  }
}

async function certifySql(a: Row) {
  await act(() => ao.patch(`/sql-attributes/${a.id}`, { certified: !a.certified }))
}

async function deleteSql(a: Row) {
  if (!window.confirm(String(a.name))) return
  await act(() => ao.del(`/sql-attributes/${a.id}`))
}

const certColor = (c: unknown) => (c === 'certified' ? 'positive' : c === 'partial' ? 'warning' : 'grey')

onMounted(async () => {
  await load()
  if (props.termId) await open(props.termId)
})
watch(() => props.termId, (id) => id && open(id))
</script>

<template>
  <div class="row q-col-gutter-md q-pa-md" data-testid="ao-terms">
    <div class="col-12 col-md-4">
      <q-input v-model="query" dense outlined clearable :label="t('ao.common.search')" debounce="300" @update:model-value="load" />
      <q-list bordered separator class="q-mt-sm">
        <q-item v-for="term in terms" :key="String(term.id)" clickable :active="selected?.id === term.id" @click="open(String(term.id))">
          <q-item-section>
            <q-item-label>{{ term.name }}</q-item-label>
            <q-item-label caption lines="2">{{ term.description }}</q-item-label>
            <q-item-label caption>
              {{ t('ao.terms.counts', { tables: counts[String(term.id)]?.tables ?? 0, attrs: counts[String(term.id)]?.attrs ?? 0, sql: counts[String(term.id)]?.sql ?? 0 }) }}
            </q-item-label>
          </q-item-section>
          <q-item-section side>
            <q-badge :color="certColor(term.certification)">{{ t(`ao.terms.certification.${String(term.certification)}`) }}</q-badge>
          </q-item-section>
        </q-item>
        <q-item v-if="!terms.length"><q-item-section>{{ t('ao.common.empty') }}</q-item-section></q-item>
      </q-list>
    </div>
    <div v-if="selected" class="col-12 col-md-8">
      <q-banner v-if="error" class="bg-negative text-white q-mb-sm">{{ error }}</q-banner>
      <q-card flat bordered>
        <q-card-section class="q-gutter-sm">
          <div class="row items-center">
            <q-input v-model="edit.name" class="col" dense outlined :label="t('ao.common.name')" />
            <q-badge class="q-ml-sm" :color="certColor(selected.certification)">{{ t(`ao.terms.certification.${String(selected.certification)}`) }}</q-badge>
          </div>
          <q-input v-model="edit.description" type="textarea" autogrow dense outlined :label="t('ao.common.description')" />
          <div class="row q-gutter-md items-center">
            <q-btn color="primary" :label="t('ao.common.save')" @click="patch({ name: edit.name, description: edit.description })" />
            <q-toggle :model-value="Boolean(selected.name_certified)" :label="t('ao.terms.nameCertified')" @update:model-value="(v: boolean) => patch({ name_certified: v })" />
            <q-toggle :model-value="Boolean(selected.description_certified)" :label="t('ao.terms.descriptionCertified')" @update:model-value="(v: boolean) => patch({ description_certified: v })" />
          </div>
          <div>
            <span class="text-caption">{{ t('ao.terms.synonyms') }}:</span>
            <q-chip v-for="s in (selected.synonyms as string[]) ?? []" :key="s" dense>{{ s }}</q-chip>
          </div>
          <div>
            <span class="text-caption">{{ t('ao.terms.tables') }}:</span>
            <q-chip v-for="tb in (selected.tables as Row[]) ?? []" :key="String(tb.id)" dense icon="table_chart">{{ tb.schema_name }}.{{ tb.name }}</q-chip>
          </div>
          <div v-if="(selected.related_terms as Row[])?.length">
            <span class="text-caption">{{ t('ao.terms.relatedTerms') }}:</span>
            <q-chip v-for="r in selected.related_terms as Row[]" :key="String(r.id)" dense clickable @click="open(String(r.id))">{{ r.name }}</q-chip>
          </div>
        </q-card-section>
      </q-card>
      <div class="text-h6 q-mt-md">{{ t('ao.terms.columnAttributes') }}</div>
      <q-list bordered separator>
        <q-item v-for="a in attrs" :key="String(a.id)">
          <q-item-section>
            <q-item-label>{{ a.name }} <span class="text-caption">({{ a.datatype }})</span></q-item-label>
            <q-item-label caption>{{ a.description }}</q-item-label>
            <q-item-label caption>
              {{ t('ao.terms.primaryColumn') }}: {{ (a.primary_column as Row)?.table_name }}.{{ (a.primary_column as Row)?.column_name }}
              <template v-if="(a.referenced_columns as Row[])?.length">
                · {{ t('ao.terms.referencedBy') }}: {{ (a.referenced_columns as Row[]).map((c) => `${c.table_name}.${c.column_name}`).join(', ') }}
              </template>
            </q-item-label>
            <q-item-label v-if="(a.sample_values as string[])?.length" caption>{{ t('ao.terms.samples') }}: {{ (a.sample_values as string[]).join(', ') }}</q-item-label>
          </q-item-section>
          <q-item-section side>
            <q-toggle :model-value="Boolean(a.certified)" :label="t('ao.common.certified')" @update:model-value="(v: boolean) => patchAttr(a, { certified: v })" />
          </q-item-section>
        </q-item>
      </q-list>
      <div class="row items-center q-mt-md">
        <div class="text-h6">{{ t('ao.terms.sqlAttributes') }}</div>
        <q-space />
        <q-btn flat icon="add" :label="t('ao.terms.newSqlAttribute')" @click="newSql" />
      </div>
      <q-list bordered separator>
        <q-item v-for="a in sqlAttrs" :key="String(a.id)">
          <q-item-section>
            <q-item-label>{{ a.name }} <q-badge outline>{{ a.source }}</q-badge></q-item-label>
            <q-item-label caption>{{ a.description }}</q-item-label>
            <q-item-label caption><code>{{ a.expression }}</code></q-item-label>
          </q-item-section>
          <q-item-section side>
            <div class="row no-wrap items-center">
              <q-toggle :model-value="Boolean(a.certified)" @update:model-value="certifySql(a)" />
              <q-btn flat round dense icon="edit" @click="editSql(a)" />
              <q-btn flat round dense icon="delete" @click="deleteSql(a)" />
            </div>
          </q-item-section>
        </q-item>
      </q-list>
    </div>
    <q-dialog v-model="sqlDialog">
      <q-card style="min-width: 520px">
        <q-card-section class="q-gutter-sm">
          <q-input v-model="sqlForm.name" dense outlined :label="t('ao.common.name')" />
          <q-input v-model="sqlForm.expression" type="textarea" autogrow dense outlined :label="t('ao.terms.expression')" input-style="font-family: monospace" />
          <q-input v-model="sqlForm.description" type="textarea" autogrow dense outlined :label="t('ao.common.description')" />
          <div v-if="sqlMessage" class="text-caption">{{ sqlMessage }}</div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn v-if="sqlForm.id" flat :label="t('ao.terms.suggest')" @click="suggest" />
          <q-btn flat :label="t('ao.terms.validate')" @click="validateSql" />
          <q-btn flat :label="t('ao.common.cancel')" v-close-popup />
          <q-btn color="primary" :label="t('ao.common.save')" @click="saveSql" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>
