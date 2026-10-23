<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 })
const paths: Record<string, string> = {
  book: 'M4 4h6a4 4 0 0 1 4 4v13H8a4 4 0 0 1-4-4V4Zm10 4h6v13h-6M8 9h2m-2 4h2',
  chat: 'M21 11a9 9 0 0 1-9 9H5l-3 2 1-6a9 9 0 1 1 18-5ZM8 10h8m-8 4h5',
  files: 'M8 3h8l4 4v14H8V3Zm8 0v5h4M4 7v14M11 12h6m-6 4h6',
  history: 'M3 11a9 9 0 1 1 2 7M3 4v7h7m2-4v6l4 2',
  plus: 'M12 5v14M5 12h14',
  arrow: 'M12 19V5m-6 6 6-6 6 6',
  upload: 'M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6',
  search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',
  chevron: 'm9 5 7 7-7 7',
  chevronDown: 'm6 9 6 6 6-6',
  close: 'm6 6 12 12M6 18 18 6',
  trash: 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7',
  check: 'm5 12 4 4L19 6',
  quote: 'M4 5h6v7H7v4H4V5Zm10 0h6v7h-3v4h-3V5Z',
  external: 'M14 3h7v7m0-7L10 14M10 3H3v18h18v-7',
  thumbUp: 'M7 10H3v11h4V10Zm0 10h12l2-10h-7V4c0-2-3-2-3 0l-4 6',
  thumbDown: 'M7 14H3V3h4v11Zm0-10h12l2 10h-7v6c0 2-3 2-3 0l-4-6',
  logout: 'M9 4H3v16h6m5-13 5 5-5 5M8 12h13',
  spark: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z',
  info: 'M12 11v6m0-10v.1M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  clock: 'M12 7v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  lock: 'M6 10V7a6 6 0 0 1 12 0v3M4 10h16v12H4V10Zm8 5v3',
  copy: 'M8 8h13v13H8V8ZM4 16H2V2h14v2',
}
</script>
<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.files" /></svg>
</template>
