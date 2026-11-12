<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAo } from '../plugin'
import type { AoEvent } from '../api'
import AoResultView from '../components/AoResultView.vue'
import { entityLinks, newUuid, parseChartBlocks, type ChartBlock } from '../results'

/** Chat page (FR-FE-02 /chat): conversations, live steps, SQL, results, charts, cancel, reattach. */
const props = defineProps<{ focus?: string | null; suggestions?: string[] }>()
const emit = defineEmits<{ (e: 'focus', id: string): void; (e: 'entity', id: string): void }>()
const ao = useAo()
const { t } = useI18n()

interface Conversation {
  id: string
  title: string
  updated_at: string
}
interface Step {
  node: string
  label: string
  thought?: string
  done: boolean
}
interface Turn {
  question: string
  answer?: string
  sql?: string | null
  rows?: Array<Record<string, unknown>> | null
  charts?: ChartBlock[]
  steps: Step[]
  error?: string
  running?: boolean
}

const conversations = ref<Conversation[]>([])
const current = ref<string | null>(props.focus ?? null)
const turns = ref<Turn[]>([])
const question = ref('')
const ready = ref(true)
const showSql = ref<Record<number, boolean>>({})
let abort: AbortController | null = null
const running = computed(() => turns.value.some((x) => x.running))
const scroller = ref<HTMLElement | null>(null)

async function loadConversations() {
  conversations.value = await ao.get<Conversation[]>('/conversations')
}

async function openConversation(id: string) {
  current.value = id
  emit('focus', id)
  const c = await ao.get<{ messages: Array<Record<string, string | null>> }>(`/conversations/${id}`)
  const list: Turn[] = []
  for (const m of c.messages) {
    if (m.role === 'user') list.push({ question: m.content ?? '', steps: [] })
    else {
      const turn = list[list.length - 1]
      if (!turn) continue
      if (turn.answer === undefined) {
        turn.answer = m.content ?? ''
        turn.sql = m.sql_code ?? null
      } else {
        const blocks = parseChartBlocks(m.content)
        if (blocks.length) turn.charts = blocks
        if (m.sql_response) {
          try {
            turn.rows = JSON.parse(m.sql_response)
          } catch {
            turn.rows = null
          }
        }
      }
    }
  }
  turns.value = list
  // reattach to a run that is still active (watch replays from the start). The turn is taken from
  // `turns.value`: only the reactive proxy makes the replayed steps and the answer appear.
  const last = turns.value[turns.value.length - 1]
  if (last && last.answer === undefined) {
    last.running = true
    last.steps = []
    void consume((onEvent, signal) => ao.watch(`/chat/watch?conversation_id=${id}`, onEvent, signal), last)
  }
}

function handle(turn: Turn, e: AoEvent) {
  if (e.type === 'step') {
    const node = String(e.node)
    if (e.phase === 'start') turn.steps.push({ node, label: String(e.label ?? node), done: false })
    else {
      const s = [...turn.steps].reverse().find((x) => x.node === node && !x.done)
      if (s) {
        s.done = true
        if (e.thought) s.thought = String(e.thought)
      }
    }
  } else if (e.type === 'sql') turn.sql = String(e.sql)
  else if (e.type === 'result') {
    const a = e.answer as Record<string, unknown>
    turn.answer = String(a.response ?? '')
    turn.sql = (a.sql_code as string) || turn.sql || null
    turn.rows = (a.sql_response_from_db as Array<Record<string, unknown>>) ?? null
  } else if (e.type === 'charts') {
    turn.charts = (e.content as ChartBlock[]) ?? []
    if (!turn.rows) turn.rows = (e.sql_response as Array<Record<string, unknown>>) ?? null
  } else if (e.type === 'error') turn.error = String(e.message)
}

async function consume(
  run: (onEvent: (e: AoEvent) => void, signal: AbortSignal) => Promise<void>,
  turn: Turn,
) {
  // a stream that is still open belongs to another conversation: it must not write into this one
  abort?.abort()
  const controller = new AbortController()
  abort = controller
  try {
    await run((e) => {
      handle(turn, e)
      void nextTick(() => scroller.value?.scrollTo({ top: scroller.value.scrollHeight }))
    }, controller.signal)
  } catch (err) {
    if (!(err instanceof DOMException && err.name === 'AbortError')) turn.error = (err as Error).message
  } finally {
    turn.running = false
    // only the own controller: a newer stream may already have replaced it
    if (abort === controller) abort = null
    await loadConversations().catch(() => undefined)
  }
}

async function send(text?: string) {
  const q = (text ?? question.value).trim()
  if (!q || running.value) return
  question.value = ''
  if (!current.value) {
    current.value = newUuid()
    emit('focus', current.value)
  }
  const turn: Turn = { question: q, steps: [], running: true }
  turns.value.push(turn)
  const conversationId = current.value
  await consume((onEvent, signal) => ao.stream('/chat/completions', { question: q, conversation_id: conversationId }, onEvent, signal), turns.value[turns.value.length - 1]!)
}

async function stop() {
  if (current.value) await ao.post('/chat/cancel', { conversation_id: current.value })
  abort?.abort()
}

function newConversation() {
  current.value = null
  turns.value = []
}

async function rename(c: Conversation) {
  const title = window.prompt(t('ao.chat.renamePrompt'), c.title)
  if (title) {
    await ao.patch(`/conversations/${c.id}`, { title })
    await loadConversations()
  }
}

async function remove(c: Conversation) {
  if (!window.confirm(t('ao.chat.deleteConfirm'))) return
  await ao.del(`/conversations/${c.id}`)
  if (current.value === c.id) newConversation()
  await loadConversations()
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void send()
  }
}

onMounted(async () => {
  await loadConversations()
  try {
    const s = await ao.get<{ calculated: boolean }>('/semantic-compilation/status')
    ready.value = s.calculated
  } catch {
    ready.value = true
  }
  if (props.focus) await openConversation(props.focus)
})
watch(
  () => props.focus,
  (id) => {
    if (id && id !== current.value) void openConversation(id)
  },
)
</script>

<template>
  <div class="row no-wrap ao-chat" style="height: 100%" data-testid="ao-chat">
    <div class="col-auto q-pa-sm" style="width: 260px; border-right: 1px solid rgba(128, 128, 128, 0.3)">
      <q-btn class="full-width q-mb-sm" color="primary" icon="add" no-caps :label="t('ao.chat.newConversation')" @click="newConversation" />
      <q-list dense>
        <q-item
          v-for="c in conversations"
          :key="c.id"
          clickable
          :active="c.id === current"
          data-testid="ao-conversation"
          @click="openConversation(c.id)"
        >
          <q-item-section><q-item-label lines="1">{{ c.title }}</q-item-label></q-item-section>
          <q-item-section side>
            <div class="row no-wrap">
              <q-btn flat dense round size="sm" icon="edit" @click.stop="rename(c)" />
              <q-btn flat dense round size="sm" icon="delete" @click.stop="remove(c)" />
            </div>
          </q-item-section>
        </q-item>
      </q-list>
    </div>
    <div class="col column no-wrap">
      <q-banner v-if="!ready" dense class="bg-warning text-black">{{ t('ao.chat.notReady') }}</q-banner>
      <div ref="scroller" class="col q-pa-md scroll">
        <div v-if="!turns.length && suggestions?.length" class="q-gutter-sm">
          <div class="text-subtitle2">{{ t('ao.chat.suggestions') }}</div>
          <q-chip v-for="s in suggestions" :key="s" clickable outline color="primary" @click="send(s)">{{ s }}</q-chip>
        </div>
        <div v-for="(turn, i) in turns" :key="i" class="q-mb-lg">
          <q-chat-message :text="[turn.question]" sent bg-color="primary" text-color="white" />
          <q-expansion-item
            v-if="turn.steps.length"
            dense
            :default-opened="turn.running"
            icon="psychology"
            :label="turn.running ? t('ao.chat.thinking') : t('ao.chat.steps')"
            data-testid="ao-steps"
          >
            <q-list dense class="q-pl-md">
              <q-item v-for="(s, j) in turn.steps" :key="j">
                <q-item-section avatar>
                  <q-spinner v-if="!s.done" size="16px" />
                  <q-icon v-else name="check" color="positive" />
                </q-item-section>
                <q-item-section>
                  <q-item-label>{{ s.label }}</q-item-label>
                  <q-item-label v-if="s.thought" caption>{{ s.thought }}</q-item-label>
                </q-item-section>
              </q-item>
            </q-list>
          </q-expansion-item>
          <q-card v-if="turn.answer !== undefined || turn.error" flat bordered class="q-mt-sm">
            <q-card-section>
              <div v-if="turn.error" class="text-negative">{{ t('ao.common.error', { message: turn.error }) }}</div>
              <div v-if="turn.answer" style="white-space: pre-wrap" data-testid="ao-answer">
                <template v-for="(part, k) in entityLinks(turn.answer)" :key="k">
                  <a v-if="part.id" href="#" class="text-primary" @click.prevent="emit('entity', part.id)">{{ part.text }}</a>
                  <span v-else>{{ part.text }}</span>
                </template>
              </div>
              <div v-if="turn.sql" class="q-mt-sm">
                <q-btn flat dense no-caps size="sm" icon="code" :label="showSql[i] ? t('ao.chat.hideSql') : t('ao.chat.showSql')" @click="showSql[i] = !showSql[i]" />
                <pre v-if="showSql[i]" class="q-pa-sm bg-grey-2 text-black" style="white-space: pre-wrap" data-testid="ao-sql">{{ turn.sql }}</pre>
              </div>
              <AoResultView :rows="turn.rows ?? null" :charts="turn.charts ?? []" :sql="turn.sql ?? null" />
            </q-card-section>
          </q-card>
        </div>
      </div>
      <div class="q-pa-sm row items-end q-gutter-sm">
        <q-input
          v-model="question"
          class="col"
          outlined
          autogrow
          dense
          :placeholder="t('ao.chat.placeholder')"
          data-testid="ao-chat-input"
          @keydown="onKey"
        />
        <q-btn v-if="running" color="negative" icon="stop" :label="t('ao.chat.stop')" @click="stop" />
        <q-btn v-else color="primary" icon="send" :label="t('ao.chat.send')" data-testid="ao-chat-send" @click="send()" />
      </div>
    </div>
  </div>
</template>
