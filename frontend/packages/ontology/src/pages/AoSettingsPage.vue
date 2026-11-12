<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAo } from '../plugin'

/**
 * Settings (FR-FE-02 /settings/*). Destructive actions (reset, import, delete connection) keep the
 * confirmation dialogs of the original — without permissions they are the only protection.
 */
const props = defineProps<{ section?: string }>()
const emit = defineEmits<{ (e: 'section', s: string): void }>()
const ao = useAo()
const { t } = useI18n()
type Row = Record<string, unknown>
const SECTIONS = ['connections', 'compilation', 'agent', 'semanticInput', 'pii', 'tags', 'rules', 'zones', 'groups', 'importExport'] as const
const section = ref<string>(props.section ?? 'connections')
const message = ref<string | null>(null)
const error = ref<string | null>(null)

// connections
const connections = ref<Row[]>([])
const envSource = ref(false)
const conn = ref({ database_name: '', url: '', username: '', password: '', schemas: '', table_allow_regex: '', table_deny_regex: '' })
const testedSchemas = ref<string[]>([])
// compilation / agent / pii
const status = ref<Row>({})
const flags = ref<Record<string, boolean>>({})
const timeout = ref(30)
// semantic input
const acronyms = ref<Row[]>([])
const prompts = ref<Row[]>([])
const newAcronym = ref({ name: '', description: '' })
const newPrompt = ref('')
// governance
const tags = ref<Row[]>([])
const rules = ref<Row[]>([])
const zones = ref<Row[]>([])
const groups = ref<Row[]>([])
const newName = ref('')
const zoneForm = ref({ name: '', color: '#1976d2', items: '' })
const importFile = ref<File | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

async function run<T>(fn: () => Promise<T>, ok?: string): Promise<T | undefined> {
  error.value = null
  message.value = null
  try {
    const r = await fn()
    if (ok) message.value = ok
    return r
  } catch (e) {
    error.value = (e as Error).message
    return undefined
  }
}

async function load() {
  try {
    await loadSection()
  } catch (e) {
    // shown in the page instead of an unhandled rejection (e.g. backend not reachable)
    error.value = (e as Error).message
  }
}

async function loadSection() {
  switch (section.value) {
    case 'connections':
      connections.value = (await ao.get<{ data: Row[] }>('/connections')).data
      envSource.value = await ao.get<boolean>('/connections/source')
      break
    case 'compilation':
      status.value = await ao.get<Row>('/semantic-compilation/status')
      for (const k of ['semantic-compilation', 'distinct-value-probing']) flags.value[k] = (await ao.get<{ enabled: boolean }>(`/configurations/${k}`)).enabled
      break
    case 'agent':
      flags.value.visualization = (await ao.get<{ enabled: boolean }>('/configurations/visualization')).enabled
      timeout.value = (await ao.get<{ seconds: number }>('/configurations/sql-query-timeout')).seconds
      break
    case 'pii':
      flags.value['pii-detection'] = (await ao.get<{ enabled: boolean }>('/configurations/pii-detection')).enabled
      break
    case 'semanticInput':
      acronyms.value = await ao.get<Row[]>('/acronyms')
      prompts.value = await ao.get<Row[]>('/custom-prompts')
      break
    case 'tags':
      tags.value = (await ao.get<{ data: Row[] }>('/tags?limit=500')).data
      break
    case 'rules':
      rules.value = (await ao.get<{ data: Row[] }>('/rules')).data
      break
    case 'zones':
      zones.value = (await ao.get<{ data: Row[] }>('/zones')).data
      break
    case 'groups':
      groups.value = (await ao.get<{ data: Row[] }>('/groups')).data
      break
  }
}

const connectionBody = () => ({
  ...conn.value,
  schemas: conn.value.schemas ? conn.value.schemas.split(',').map((s) => s.trim()).filter(Boolean) : [],
})

async function testConnection() {
  const r = await run(() => ao.post<{ schemas: string[] }>('/connections/test', { connection: connectionBody() }))
  if (r) {
    testedSchemas.value = r.schemas
    message.value = t('ao.settings.testOk', { schemas: r.schemas.join(', ') })
  }
}
async function createConnection() {
  await run(() => ao.post('/connections', { connection: connectionBody() }))
  await load()
}
async function deleteConnection(c: Row) {
  if (!window.confirm(t('ao.settings.deleteConnection'))) return
  await run(() => ao.del(`/connections/${encodeURIComponent(String(c.database_name))}`))
  await load()
}
async function setFlag(name: string, enabled: boolean) {
  await run(() => ao.put(`/configurations/${name}`, { enabled }))
  await load()
}
async function saveTimeout() {
  await run(() => ao.put('/configurations/sql-query-timeout', { seconds: Number(timeout.value) }))
}
async function reset() {
  if (!window.confirm(t('ao.settings.resetConfirm'))) return
  await run(() => ao.post('/semantic-compilation/reset'))
  await load()
}
async function addAcronym() {
  await run(() => ao.post('/acronyms', newAcronym.value))
  newAcronym.value = { name: '', description: '' }
  await load()
}
async function addPrompt() {
  await run(() => ao.post('/custom-prompts', { content: newPrompt.value }))
  newPrompt.value = ''
  await load()
}
async function createSimple(path: string) {
  await run(() => ao.post(path, { name: newName.value }))
  newName.value = ''
  await load()
}
async function renameSimple(path: string, r: Row) {
  const name = window.prompt(t('ao.common.rename'), String(r.name))
  if (name) await run(() => ao.patch(`${path}/${r.id}`, { name }))
  await load()
}
async function removeSimple(path: string, r: Row, query = '') {
  if (!window.confirm(String(r.name))) return
  await run(() => ao.del(`${path}/${r.id}${query}`))
  await load()
}
async function createZone() {
  await run(() => ao.post('/zones', { name: zoneForm.value.name, color: zoneForm.value.color, items: zoneForm.value.items.split(',').map((s) => s.trim()).filter(Boolean) }))
  await load()
}
async function toggleZone(z: Row) {
  await run(() => ao.patch(`/zones/${z.id}/status`, { enabled: !z.enabled }))
  await load()
}
async function exportModel() {
  const blob = await run(() => ao.download('/model/export', { databases: [], format: 'auto_ontology', include_ontology: false }))
  if (!blob) return
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'auto-ontology-model.yaml'
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
async function importModel() {
  if (!importFile.value || !window.confirm(t('ao.settings.importConfirm'))) return
  const form = new FormData()
  form.append('file', importFile.value)
  await run(() => ao.upload('/model/import?replace=true', form), 'OK')
}

watch(section, (s) => {
  emit('section', s)
  void load()
})
onMounted(() => {
  void load()
  poll = setInterval(() => section.value === 'compilation' && void load(), 3000)
})
onUnmounted(() => poll && clearInterval(poll))
</script>

<template>
  <div class="row no-wrap" style="height: 100%" data-testid="ao-settings">
    <q-list class="col-auto" style="width: 220px" dense>
      <q-item v-for="s in SECTIONS" :key="s" clickable :active="section === s" @click="section = s">
        <q-item-section>{{ t(`ao.settings.${s}`) }}</q-item-section>
      </q-item>
    </q-list>
    <div class="col q-pa-md q-gutter-sm">
      <q-banner v-if="error" class="bg-negative text-white">{{ error }}</q-banner>
      <q-banner v-if="message" class="bg-positive text-white">{{ message }}</q-banner>

      <template v-if="section === 'connections'">
        <q-banner v-if="envSource" dense class="bg-info text-white">{{ t('ao.settings.envSource') }}</q-banner>
        <q-list bordered separator>
          <q-item v-for="c in connections" :key="String(c.database_name)">
            <q-item-section>
              <q-item-label>{{ c.database_name }}</q-item-label>
              <q-item-label caption>{{ (c.connection as Row)?.url }}</q-item-label>
            </q-item-section>
            <q-item-section side><q-btn flat round icon="delete" @click="deleteConnection(c)" /></q-item-section>
          </q-item>
        </q-list>
        <q-card flat bordered>
          <q-card-section class="q-gutter-sm">
            <q-input v-model="conn.database_name" dense outlined :label="t('ao.common.name')" />
            <q-input v-model="conn.url" dense outlined :label="t('ao.settings.connectionUrl')" placeholder="jdbc:postgresql://host:5432/db" />
            <div class="row q-gutter-sm">
              <q-input v-model="conn.username" class="col" dense outlined :label="t('ao.settings.username')" />
              <q-input v-model="conn.password" class="col" dense outlined type="password" :label="t('ao.settings.password')" />
            </div>
            <q-select v-if="testedSchemas.length" :model-value="conn.schemas ? conn.schemas.split(',') : []" multiple dense outlined :options="testedSchemas" :label="t('ao.settings.schemas')" @update:model-value="(v: string[]) => (conn.schemas = v.join(','))" />
            <div class="row q-gutter-sm">
              <q-input v-model="conn.table_allow_regex" class="col" dense outlined :label="t('ao.settings.allowRegex')" />
              <q-input v-model="conn.table_deny_regex" class="col" dense outlined :label="t('ao.settings.denyRegex')" />
            </div>
          </q-card-section>
          <q-card-actions>
            <q-btn flat :label="t('ao.settings.test')" @click="testConnection" />
            <q-btn color="primary" :label="t('ao.common.add')" :disable="!conn.url" @click="createConnection" />
          </q-card-actions>
        </q-card>
      </template>

      <template v-else-if="section === 'compilation'">
        <q-toggle :model-value="flags['semantic-compilation']" :label="t('ao.settings.enableCompilation')" @update:model-value="(v: boolean) => setFlag('semantic-compilation', v)" />
        <div class="row q-gutter-md">
          <q-card flat bordered class="col"><q-card-section><div class="text-caption">{{ t('ao.settings.isRunning') }}</div><div class="text-h6">{{ status.running ? t('ao.common.yes') : t('ao.common.no') }}</div></q-card-section></q-card>
          <q-card flat bordered class="col"><q-card-section><div class="text-caption">{{ t('ao.settings.failed') }}</div><div class="text-h6">{{ status.last_failure_at ?? '—' }}</div></q-card-section></q-card>
          <q-card flat bordered class="col"><q-card-section><div class="text-caption">{{ t('ao.settings.isReady') }}</div><div class="text-h6" data-testid="ao-ready">{{ status.calculated ? t('ao.common.yes') : t('ao.common.no') }}</div><div class="text-caption">{{ t('ao.settings.lastSuccess', { at: status.last_success_at ?? '—' }) }}</div></q-card-section></q-card>
        </div>
        <q-toggle :model-value="flags['distinct-value-probing']" :label="t('ao.settings.distinct')" @update:model-value="(v: boolean) => setFlag('distinct-value-probing', v)" />
        <div><q-btn color="negative" icon="restart_alt" :label="t('ao.settings.reset')" @click="reset" /></div>
      </template>

      <template v-else-if="section === 'agent'">
        <q-toggle :model-value="flags.visualization" :label="t('ao.settings.visualize')" @update:model-value="(v: boolean) => setFlag('visualization', v)" />
        <div class="row items-center q-gutter-sm">
          <q-input v-model.number="timeout" type="number" dense outlined :label="t('ao.settings.timeout')" style="width: 220px" />
          <q-btn color="primary" :label="t('ao.common.save')" @click="saveTimeout" />
        </div>
      </template>

      <template v-else-if="section === 'pii'">
        <q-toggle :model-value="flags['pii-detection']" :label="t('ao.settings.piiEnable')" @update:model-value="(v: boolean) => setFlag('pii-detection', v)" />
      </template>

      <template v-else-if="section === 'semanticInput'">
        <div class="text-subtitle1">{{ t('ao.settings.acronyms') }}</div>
        <q-list bordered separator>
          <q-item v-for="a in acronyms" :key="String(a.id)">
            <q-item-section><q-item-label>{{ a.name }}</q-item-label><q-item-label caption>{{ a.description }}</q-item-label></q-item-section>
            <q-item-section side><q-btn flat round icon="delete" @click="removeSimple('/acronyms', a)" /></q-item-section>
          </q-item>
        </q-list>
        <div class="row q-gutter-sm">
          <q-input v-model="newAcronym.name" dense outlined :label="t('ao.common.name')" />
          <q-input v-model="newAcronym.description" class="col" dense outlined :label="t('ao.common.description')" />
          <q-btn color="primary" :label="t('ao.common.add')" :disable="!newAcronym.name" @click="addAcronym" />
        </div>
        <div class="text-subtitle1 q-mt-md">{{ t('ao.settings.prompts') }}</div>
        <q-list bordered separator>
          <q-item v-for="p in prompts" :key="String(p.id)">
            <q-item-section style="white-space: pre-wrap">{{ p.content }}</q-item-section>
            <q-item-section side><q-btn flat round icon="delete" @click="removeSimple('/custom-prompts', p)" /></q-item-section>
          </q-item>
        </q-list>
        <q-input v-model="newPrompt" type="textarea" autogrow dense outlined :label="t('ao.settings.prompt')" />
        <q-btn color="primary" :label="t('ao.common.add')" :disable="!newPrompt" @click="addPrompt" />
      </template>

      <template v-else-if="section === 'tags' || section === 'groups'">
        <q-list bordered separator>
          <q-item v-for="r in section === 'tags' ? tags : groups" :key="String(r.id)">
            <q-item-section>
              <q-item-label>{{ r.name }}</q-item-label>
              <q-item-label v-if="section === 'tags'" caption>{{ r.target_count }}</q-item-label>
            </q-item-section>
            <q-item-section side>
              <div class="row no-wrap">
                <q-btn flat round dense icon="edit" @click="renameSimple(section === 'tags' ? '/tags' : '/groups', r)" />
                <q-btn flat round dense icon="delete" @click="removeSimple(section === 'tags' ? '/tags' : '/groups', r)" />
              </div>
            </q-item-section>
          </q-item>
        </q-list>
        <div class="row q-gutter-sm">
          <q-input v-model="newName" dense outlined :label="t('ao.common.name')" />
          <q-btn color="primary" :label="t('ao.common.add')" :disable="!newName" @click="createSimple(section === 'tags' ? '/tags' : '/groups')" />
        </div>
      </template>

      <template v-else-if="section === 'rules'">
        <q-list bordered separator>
          <q-item v-for="r in rules" :key="String(r.id)">
            <q-item-section>
              <q-item-label>{{ r.name }}</q-item-label>
              <q-item-label caption>„{{ r.search_term }}“ → {{ (r.tags as Row[]).map((x) => x.name).join(', ') }}</q-item-label>
            </q-item-section>
            <q-item-section side>
              <div class="row no-wrap">
                <q-btn flat round dense icon="edit" @click="renameSimple('/rules', r)" />
                <q-btn flat round dense icon="delete" @click="removeSimple('/rules', r)" />
              </div>
            </q-item-section>
          </q-item>
          <q-item v-if="!rules.length"><q-item-section>{{ t('ao.common.empty') }}</q-item-section></q-item>
        </q-list>
      </template>

      <template v-else-if="section === 'zones'">
        <q-list bordered separator>
          <q-item v-for="z in zones" :key="String(z.id)">
            <q-item-section avatar><q-icon name="circle" :style="{ color: String(z.color ?? '#999') }" /></q-item-section>
            <q-item-section>
              <q-item-label>{{ z.name }}</q-item-label>
              <q-item-label caption>{{ (z.items as Row[]).map((i) => i.name).join(', ') }}</q-item-label>
            </q-item-section>
            <q-item-section side>
              <div class="row no-wrap items-center">
                <q-toggle :model-value="Boolean(z.enabled)" @update:model-value="toggleZone(z)" />
                <q-btn flat round dense icon="delete" @click="removeSimple('/zones', z)" />
              </div>
            </q-item-section>
          </q-item>
        </q-list>
        <div class="row q-gutter-sm">
          <q-input v-model="zoneForm.name" dense outlined :label="t('ao.common.name')" />
          <q-input v-model="zoneForm.color" dense outlined :label="t('ao.settings.color')" style="width: 120px" />
          <q-input v-model="zoneForm.items" class="col" dense outlined :label="t('ao.settings.items')" />
          <q-btn color="primary" :label="t('ao.common.add')" :disable="!zoneForm.name" @click="createZone" />
        </div>
      </template>

      <template v-else-if="section === 'importExport'">
        <q-btn color="primary" icon="download" :label="t('ao.settings.export')" @click="exportModel" />
        <div class="row q-gutter-sm items-center">
          <q-file v-model="importFile" dense outlined accept=".yaml,.yml" :label="t('ao.settings.import')" style="width: 320px" />
          <q-btn color="negative" icon="upload" :label="t('ao.common.upload')" :disable="!importFile" @click="importModel" />
        </div>
      </template>
    </div>
  </div>
</template>
