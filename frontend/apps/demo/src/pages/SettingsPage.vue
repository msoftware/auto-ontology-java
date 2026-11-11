<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { AoSettingsPage } from '@jentsch/quasar-ontology'

const route = useRoute()
const router = useRouter()
</script>

<template>
  <AoSettingsPage :section="(route.params.section as string) || 'connections'" @section="(s) => router.replace(`/settings/${s}`)" />
</template>
