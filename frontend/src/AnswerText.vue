<script setup lang="ts">
import { computed } from 'vue'
import type { Citation } from './api'
const props = defineProps<{ text: string; citations: Citation[] }>()
const emit = defineEmits<{ source: [citation: Citation] }>()
const paragraphs = computed(() => props.text.split(/\n\s*\n/).filter(Boolean).map(text =>
  text.split(/(\[\d{1,3}\])/g).filter(Boolean).map(part => ({
    text: part,
    citation: /^\[\d+\]$/.test(part) ? props.citations.find(c => c.index === Number(part.slice(1, -1))) : undefined,
  })),
))
</script>
<template>
  <div class="readable-answer"><p v-for="(paragraph, index) in paragraphs" :key="index"><template v-for="(part, token) in paragraph" :key="token"><button v-if="part.citation" class="answer-citation" :aria-label="`查看引用 ${part.citation.index}：${part.citation.documentName}`" :title="`${part.citation.documentName} · 片段 ${part.citation.ordinal}`" @click="emit('source', part.citation)">{{ part.citation.index }}</button><span v-else>{{ part.text }}</span></template></p></div>
</template>
