<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { useRoute } from 'vue-router'
import { AoTermsPage } from '@jentsch/quasar-ontology'

const route = useRoute()
</script>

<template>
  <AoTermsPage :term-id="(route.query.id as string) ?? null" />
</template>
