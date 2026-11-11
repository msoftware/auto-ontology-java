<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AoChatPage, useAo } from '@jentsch/quasar-ontology'

/** /chat with deep link ?focus=<id> and demo question suggestions (FR-DEMO-04). */
const route = useRoute()
const router = useRouter()
const ao = useAo()
const suggestions = ref<string[]>([])
onMounted(async () => {
  try {
    suggestions.value = (await ao.get<{ data: string[] }>('/demo/questions')).data
  } catch {
    suggestions.value = []
  }
})
function focus(id: string) {
  void router.replace({ query: { focus: id } })
}
function entity(id: string) {
  void router.push({ path: '/terms', query: { id } })
}
</script>

<template>
  <AoChatPage :focus="(route.query.focus as string) ?? null" :suggestions="suggestions" @focus="focus" @entity="entity" />
</template>
