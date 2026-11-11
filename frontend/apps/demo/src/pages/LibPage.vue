<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed } from 'vue'
import {
  AoAnalysisPage,
  AoAnalyticsPage,
  AoDataPage,
  AoDiscoveryPage,
  AoExplorationPage,
} from '@jentsch/quasar-ontology'

const props = defineProps<{ page: 'discovery' | 'analysis' | 'exploration' | 'data' | 'analytics' }>()
const component = computed(
  () =>
    ({
      discovery: AoDiscoveryPage,
      analysis: AoAnalysisPage,
      exploration: AoExplorationPage,
      data: AoDataPage,
      analytics: AoAnalyticsPage,
    })[props.page],
)
</script>

<template>
  <component :is="component" />
</template>
