<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { api, session, type Document, type Exercise, type Citation } from './api'
import { filterPractice, practiceOverview, needsReview, type PracticeFilter } from './practice'
import { createDraftStore } from './practice-drafts'
import Icon from './Icon.vue'
import AnswerText from './AnswerText.vue'
const props = defineProps<{ userId: string; documents: Document[]; enabled: boolean; autoStart: boolean }>()
const emit = defineEmits<{ source: [citation: Citation]; manage: []; started: [] }>()
const allDocuments = ref(true)
const selected = ref<string[]>([])
const active = ref<Exercise | null>(null)
const records = ref<Exercise[]>([])
const answer = ref('')
const working = ref('')
const loading = ref(false)
const error = ref('')
const confirmDelete = ref('')
const historyFilter = ref<PracticeFilter>('all')
const draftState = ref<'saved' | 'dirty' | 'saving' | 'error' | 'conflict'>('saved')
const draftError = ref('')
const savedAnswer = ref('')
const serverCopy = ref<Exercise | null>(null)
const draftStore = createDraftStore(props.userId)
const ownerToken = session.token()
const sameSession = () => session.token() === ownerToken
let draftTimer: ReturnType<typeof setTimeout> | undefined, saveFlight: Promise<boolean> | null = null
let savingAnswer: string | undefined, restoring = false, activeVersion = 0
let alive = true, historyRequest = 0
const busy = computed(() => !!working.value)
const ready = computed(() => props.enabled && props.documents.length > 0 && (allDocuments.value || selected.value.length > 0))
const editable = computed(() => active.value?.qualityChecked === true && !active.value.grade)
const unsaved = computed(() => editable.value && answer.value !== savedAnswer.value)
const draftHint = computed(() => draftState.value === 'saving' ? '正在保存草稿…' : draftState.value === 'dirty' ? '草稿尚未保存'
  : draftState.value === 'error' || draftState.value === 'conflict' ? draftError.value
  : savedAnswer.value ? '草稿已保存' : '输入后自动保存草稿')
const overview = computed(() => practiceOverview(records.value))
const filteredRecords = computed(() => filterPractice(records.value, historyFilter.value))
const filters = computed(() => [
  { key: 'all' as const, label: '全部', count: records.value.length },
  { key: 'pending' as const, label: '待作答', count: overview.value.pending },
  { key: 'review' as const, label: '待复习', count: overview.value.review.length },
  { key: 'mastered' as const, label: '已掌握', count: overview.value.mastered.length },
])
const levelLabels = { CORRECT: '已掌握', PARTIAL: '部分掌握', MISSING: '遗漏', INCORRECT: '需纠正' }
const name = (value: string) => value.replace(/ [a-f0-9]{32}(?=\.[a-z]+$)/i, '')
const message = (value: unknown) => value instanceof Error ? value.message : '操作失败，请稍后重试'
const recordLabel = (row: Exercise) => row.qualityChecked !== true ? '旧版记录 · 请重新出题'
  : !row.grade ? `${row.studentAnswer ? '草稿已保存' : '待作答'} · 点击继续` : `${row.grade.score}/100 · ${needsReview(row) ? '待复习' : '已掌握'}`
watch(() => props.documents, docs => { selected.value = selected.value.filter(id => docs.some(doc => doc.id === id)) })
function invalidateHistory() { historyRequest++; loading.value = false }
function save(row: Exercise) { records.value = [row, ...records.value.filter(item => item.id !== row.id)].slice(0, 100) }
function mergeDraft(row: Exercise) { records.value = records.value.map(item => item.id === row.id ? row : item) }
function stopDraftTimer() { if (draftTimer !== undefined) clearTimeout(draftTimer); draftTimer = undefined }
function rememberDraft() {
  if (!editable.value || !active.value) return
  draftStore.remember(active.value.id)
  if (answer.value !== savedAnswer.value || draftState.value === 'conflict') draftStore.put(active.value.id, { answer: answer.value, expectedAnswer: savedAnswer.value, savingAnswer })
  else draftStore.clear(active.value.id)
}
function scheduleDraft() {
  stopDraftTimer()
  if (unsaved.value && draftState.value !== 'conflict') draftTimer = setTimeout(() => { void flushDraft() }, 800)
}
watch(answer, () => {
  if (restoring || !editable.value) return
  rememberDraft()
  if (draftState.value !== 'conflict' && draftState.value !== 'saving') draftState.value = unsaved.value ? 'dirty' : 'saved'
  scheduleDraft()
}, { flush: 'sync' })
function activate(row: Exercise) {
  stopDraftTimer(); activeVersion++; restoring = true
  active.value = row; savedAnswer.value = row.studentAnswer || ''; answer.value = savedAnswer.value
  draftState.value = 'saved'; draftError.value = ''; serverCopy.value = null
  if (row.qualityChecked === true && !row.grade) {
    draftStore.remember(row.id)
    const cached = draftStore.get(row.id)
    if (cached && cached.answer !== savedAnswer.value) {
      answer.value = cached.answer
      if (cached.expectedAnswer === savedAnswer.value || cached.savingAnswer === savedAnswer.value) {
        draftState.value = 'dirty'
      } else {
        savedAnswer.value = cached.expectedAnswer; serverCopy.value = row; draftState.value = 'conflict'
        draftError.value = '已保存的草稿有更新，当前输入已保留。请选择使用哪一份。'
      }
    } else draftStore.clear(row.id)
  } else draftStore.forget(row.id)
  restoring = false; rememberDraft(); scheduleDraft()
}
async function flushDraft(): Promise<boolean> {
  stopDraftTimer()
  while (alive && sameSession()) {
    if (saveFlight) { if (!await saveFlight) return false; continue }
    if (draftState.value === 'conflict') return false
    if (!editable.value || !active.value || !unsaved.value) return true
    const id = active.value.id, value = answer.value, expected = savedAnswer.value, version = activeVersion
    const current = () => alive && sameSession() && active.value?.id === id && version === activeVersion
    draftState.value = 'saving'; draftError.value = ''; savingAnswer = value; rememberDraft()
    saveFlight = (async () => {
      try {
        const row = await api<Exercise>(`/practice/${id}/draft`, { method: 'PATCH', keepalive: true, body: JSON.stringify({ answer: value, expectedAnswer: expected }) })
        if (!current()) return false
        invalidateHistory(); mergeDraft(row); active.value = row; savedAnswer.value = row.studentAnswer || ''
        draftState.value = answer.value === savedAnswer.value ? 'saved' : 'dirty'
        return true
      } catch (reason) {
        if (!current()) return false
        draftState.value = (reason as { status?: number }).status === 409 ? 'conflict' : 'error'
        draftError.value = draftState.value === 'conflict' ? '已保存的草稿有更新，当前输入已保留。请选择使用哪一份。' : `${message(reason)} 当前输入已保留。`
        if (draftState.value === 'conflict') {
          try { const row = await api<Exercise>(`/practice/${id}`); if (current()) serverCopy.value = row }
          catch { /* Keep the local input until the saved record can be read. */ }
        }
        return false
      } finally {
        if (current()) { savingAnswer = undefined; rememberDraft() }
      }
    })().finally(() => { saveFlight = null })
    if (!await saveFlight) return false
  }
  return false
}
async function resolveDraft(useCurrent: boolean) {
  if (busy.value || !sameSession() || !active.value || draftState.value !== 'conflict') return
  working.value = 'resolve'
  try {
    const row = await api<Exercise>(`/practice/${active.value.id}`)
    if (!alive) return
    serverCopy.value = row
    if (useCurrent && row.qualityChecked === true && !row.grade) {
      savedAnswer.value = row.studentAnswer || ''; draftState.value = 'dirty'; draftError.value = ''; rememberDraft()
      await flushDraft()
    } else if (!useCurrent) { draftStore.clear(row.id); activate(row) }
    else draftError.value = '这道题已批改或已失效，当前输入仍保留，可选择查看已保存记录。'
  } catch (reason) { if (alive) draftError.value = message(reason) }
  finally { if (alive) working.value = '' }
}
function leavePractice() {
  rememberDraft(); stopDraftTimer()
  if (sameSession() && unsaved.value && draftState.value !== 'conflict') void flushDraft()
}
async function loadHistory() {
  const request = ++historyRequest
  loading.value = true
  try { const rows = await api<Exercise[]>('/practice'); if (alive && request === historyRequest) records.value = rows }
  catch (reason) { if (alive && request === historyRequest) error.value = message(reason) }
  finally { if (alive && request === historyRequest) loading.value = false }
}
async function generate() {
  if (busy.value || !ready.value) return
  invalidateHistory(); working.value = 'create'; error.value = ''
  try {
    if ((unsaved.value || saveFlight || draftState.value === 'conflict') && !await flushDraft()) return
    if (!alive) return
    const row = await api<Exercise>('/practice', { method: 'POST', body: JSON.stringify({ documentIds: allDocuments.value ? [] : selected.value }) })
    if (!alive) return
    activate(row); save(row)
  } catch (reason) { if (alive) error.value = message(reason) }
  finally { if (alive) working.value = '' }
}
async function submit() {
  if (busy.value || !active.value || active.value.grade || active.value.qualityChecked !== true || !answer.value.trim()) return
  invalidateHistory(); working.value = 'grade'; error.value = ''
  const id = active.value.id
  try {
    if (!await flushDraft() || !alive) return
    const row = await api<Exercise>(`/practice/${id}/answer`, { method: 'POST', body: JSON.stringify({ answer: answer.value.trim(), expectedAnswer: savedAnswer.value }) })
    if (!alive) return
    activate(row); save(row)
  } catch (reason) {
    if (alive) {
      error.value = message(reason)
      if ((reason as { status?: number }).status === 409) {
        draftState.value = 'conflict'; draftError.value = '已保存的作答有更新，当前输入已保留。请选择使用哪一份。'; rememberDraft()
        try { const row = await api<Exercise>(`/practice/${id}`); if (alive) serverCopy.value = row } catch { }
      }
    }
  }
  finally { if (alive) working.value = '' }
}
async function retry(row: Exercise) {
  if (busy.value || row.qualityChecked !== true || !row.grade) return
  invalidateHistory(); working.value = 'retry'; error.value = ''
  try {
    if ((unsaved.value || saveFlight || draftState.value === 'conflict') && !await flushDraft()) return
    if (!alive) return
    const fresh = await api<Exercise>(`/practice/${row.id}/retry`, { method: 'POST' })
    if (!alive) return
    activate(fresh); confirmDelete.value = ''; save(fresh)
  } catch (reason) { if (alive) error.value = message(reason) }
  finally { if (alive) working.value = '' }
}
async function open(row: Exercise) {
  if (busy.value || active.value?.id === row.id) return
  working.value = 'switch'; error.value = ''
  try {
    if ((unsaved.value || saveFlight || draftState.value === 'conflict') && !await flushDraft()) return
    if (alive) { activate(row); confirmDelete.value = '' }
  } finally { if (alive) working.value = '' }
}
async function remove(row: Exercise) {
  if (busy.value) return
  invalidateHistory(); working.value = 'delete'; error.value = ''
  try {
    await api<void>(`/practice/${row.id}`, { method: 'DELETE' })
    if (!alive) return
    records.value = records.value.filter(item => item.id !== row.id)
    draftStore.forget(row.id)
    if (active.value?.id === row.id) { stopDraftTimer(); activeVersion++; restoring = true; active.value = null; answer.value = ''; restoring = false }
    confirmDelete.value = ''
  } catch (reason) { if (alive) error.value = message(reason) }
  finally { if (alive) working.value = '' }
}
onMounted(async () => {
  window.addEventListener('pagehide', leavePractice)
  await loadHistory()
  const remembered = draftStore.last()
  if (alive && remembered) {
    try {
      const row = records.value.find(item => item.id === remembered) || await api<Exercise>(`/practice/${remembered}`)
      if (alive && row.qualityChecked === true && !row.grade) activate(row)
      else draftStore.forget(remembered)
    } catch (reason) { if ((reason as { status?: number }).status === 404) draftStore.forget(remembered) }
  }
  if (alive && props.autoStart) { emit('started'); generate() }
})
onUnmounted(() => { leavePractice(); alive = false; historyRequest++; window.removeEventListener('pagehide', leavePractice) })
</script>

<template>
  <main class="practice-page">
    <div class="practice-heading"><span class="eyebrow">从看懂，到自己说清楚</span><h1>笔记练习</h1><p>根据你的资料，一次练一道题。提交回答后再看批改、参考答案和原文。</p></div>
    <section class="practice-overview" aria-label="近期练习概览">
      <div class="practice-overview-heading"><strong>近期练习概览</strong><small>按最近 100 条记录统计，旧版记录不计分</small></div>
      <div class="practice-metrics"><div><span>已作答</span><strong>{{ overview.graded }}<small>次</small></strong></div><div><span>待作答</span><strong>{{ overview.pending }}<small>次</small></strong></div><div><span>待复习</span><strong>{{ overview.review.length }}<small>题</small></strong></div><div><span>平均得分</span><strong>{{ overview.average ?? '—' }}<small v-if="overview.average !== null">分</small></strong></div></div>
      <p>同一道题按最近一次批改判断是否需要复习；每次作答均保留记录。AI 批改结果可结合原文核对。</p>
    </section>
    <section class="practice-setup" aria-label="练习资料范围">
      <div class="practice-setup-top"><strong><Icon name="files" :size="17" />出题范围</strong><label><input v-model="allDocuments" type="checkbox" :disabled="busy" />全部 {{ documents.length }} 份资料</label></div>
      <div v-if="!allDocuments" class="practice-document-list">
        <label v-for="doc in documents" :key="doc.id"><input v-model="selected" type="checkbox" :value="doc.id" :disabled="busy" /><span :title="doc.name">{{ name(doc.name) }}</span></label>
      </div>
      <p v-if="!documents.length">先添加一份有正文的笔记。<button class="text-button" @click="emit('manage')">前往我的知识库</button></p>
      <p v-else-if="!allDocuments && !selected.length">请勾选至少一份资料，或选择“全部资料”。</p>
      <p v-else>每题选取一个知识点，检查题目、答案与原文依据；未整理的转写和含混片段不用于出题。取消“全部资料”可指定某一章。</p>
      <p v-if="!enabled" class="practice-warning">笔记练习需要模型服务，请先启动本地模型。</p>
      <button class="button primary" :disabled="busy || !ready" @click="generate"><span v-if="working === 'create'" class="spinner light"></span><Icon v-else name="spark" :size="17" />{{ working === 'create' ? '正在出题并检查答案…' : active ? '再出一题' : '开始出一题' }}</button>
    </section>
    <div v-if="error" class="practice-error" role="alert">{{ error }}</div>
    <article v-if="active" class="practice-card" aria-label="当前练习">
      <div class="practice-card-top"><span class="practice-pill">{{ active.reviewOf ? '原题复练' : '简答题' }}</span><span>{{ active.qualityChecked !== true ? '旧版记录' : active.grade ? '已批改' : '待作答' }}</span></div>
      <h2>{{ active.question }}</h2><p class="practice-origin">本题来自：{{ active.documents.map(name).join('、') }}</p>
      <p v-if="active.qualityChecked !== true" class="practice-error" role="alert">这条旧版练习未经过新版质量检查，原参考答案和评分暂停展示。记录和你的回答仍保留，请点击“再出一题”重新练习。</p>
      <form @submit.prevent="submit"><label class="practice-answer-label" for="practice-answer">你的回答</label><textarea id="practice-answer" v-model="answer" rows="5" maxlength="4000" :readonly="!!active.grade || active.qualityChecked !== true" :disabled="busy" placeholder="先用自己的话回答，不确定的部分也可以写出来。"></textarea>
        <div v-if="editable" class="practice-draft" :class="{ warning: draftState === 'error' || draftState === 'conflict' }" role="status"><span>{{ draftHint }}</span><button v-if="draftState === 'error'" type="button" class="text-button" :disabled="busy" @click="flushDraft">重试保存</button><template v-if="draftState === 'conflict'"><button type="button" class="text-button" :disabled="busy" @click="resolveDraft(false)">使用已保存内容</button><button v-if="serverCopy?.qualityChecked === true && !serverCopy.grade" type="button" class="text-button" :disabled="busy" @click="resolveDraft(true)">保存当前草稿</button></template></div>
        <div class="practice-answer-actions"><small>{{ answer.length }}/4000</small><button v-if="!active.grade && active.qualityChecked === true" class="button primary" :disabled="busy || !answer.trim()"><span v-if="working === 'grade'" class="spinner light"></span>{{ working === 'grade' ? '正在批改…' : '提交批改' }}</button></div></form>
      <section v-if="active.grade && active.qualityChecked === true" class="practice-review" aria-label="批改结果">
        <div class="practice-result"><strong>{{ active.grade.score }}<small>/100</small></strong><p>{{ active.grade.summary }}</p></div>
        <div v-for="check in active.grade.checks" :key="check.criterion" class="practice-check"><span class="practice-pill" :class="check.level">{{ levelLabels[check.level] }}</span><p>{{ check.feedback }}</p></div>
        <h3>参考答案</h3><AnswerText v-for="(point, i) in active.referenceAnswer" :key="i" :text="`${i + 1}. ${point.point} [${point.source}]`" :citations="active.citations" @source="citation => emit('source', citation)" />
        <details class="practice-evidence"><summary>核对原文 · {{ active.citations.length }} 处来源</summary><div v-for="source in active.citations" :key="source.index"><strong>[{{ source.index }}] {{ name(source.documentName) }} · 片段 {{ source.ordinal }}</strong><blockquote v-for="(quote, i) in source.supportingQuotes" :key="i">{{ quote }}</blockquote><button class="text-button" @click="emit('source', source)">查看文档原文 <Icon name="external" :size="13" /></button></div></details>
        <p class="practice-note">题目与答案经过格式检查和单独复核；AI 仍可能判断有误，请结合原文核对。引用保留出题时的快照。</p>
        <div class="practice-retry"><span>再用自己的话回答一次，每次结果单独保存。</span><button class="button secondary" :disabled="busy" @click="retry(active)"><span v-if="working === 'retry'" class="spinner"></span>{{ working === 'retry' ? '正在准备…' : '原题再练' }}</button></div>
      </section>
    </article>
    <section class="practice-history" aria-label="练习记录"><div class="practice-history-heading"><h2>练习记录 <small>最近 {{ records.length }} 条</small></h2><button class="text-button" :disabled="busy || loading" @click="loadHistory">{{ loading ? '正在读取…' : '刷新' }}</button></div>
      <div class="practice-filters" role="group" aria-label="筛选练习记录"><button v-for="filter in filters" :key="filter.key" :aria-pressed="historyFilter === filter.key" :disabled="busy" @click="historyFilter = filter.key; confirmDelete = ''">{{ filter.label }}<small>{{ filter.count }}</small></button></div>
      <p v-if="!filteredRecords.length && !loading" class="practice-empty">{{ !records.length ? '还没有练习。出题和批改结果会自动保存，离开后可以继续。' : historyFilter === 'review' ? '近期记录中没有待复习的题目。' : '这个分类下还没有练习记录。' }}</p>
      <div v-for="row in filteredRecords" :key="row.id" class="practice-history-row"><button class="practice-history-open" :disabled="busy" @click="open(row)"><strong>{{ row.question }}</strong><small>{{ row.reviewOf ? '原题复练 · ' : '' }}{{ recordLabel(row) }}</small></button><span v-if="confirmDelete === row.id" class="practice-delete-confirm">删除这条练习？<button class="text-button" :disabled="busy" @click="remove(row)">确认</button><button class="text-button" :disabled="busy" @click="confirmDelete = ''">取消</button></span><button v-else class="icon-button danger-hover" :disabled="busy" aria-label="删除这条练习" @click="confirmDelete = row.id"><Icon name="trash" :size="16" /></button></div>
    </section>
  </main>
</template>

<style scoped>
.practice-draft{display:flex;align-items:center;flex-wrap:wrap;gap:6px 12px;margin-top:9px;color:#8d9b83;font-size:11px;line-height:1.8}.practice-draft.warning{color:#94633e}.practice-draft .text-button{font-size:11px}
.practice-overview{border:1px solid #e3e9df;background:#fff;border-radius:11px;padding:22px 24px;margin-top:24px}.practice-overview-heading{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;color:#587553;font-size:14px}.practice-overview-heading small{font-size:11px;color:#8d9b83}.practice-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px;margin:22px 0 14px}.practice-metrics>div{display:flex;flex-direction:column;gap:10px}.practice-metrics span{font-size:12px;color:#8d9b83}.practice-metrics strong{font-size:27px;font-weight:550;color:#355b4b}.practice-metrics strong small{font-size:11px;font-weight:400;color:#8d9b83;margin-left:5px}.practice-overview>p{font-size:11px;color:#8d9b83;line-height:1.8;margin-bottom:0}.practice-filters{display:flex;gap:8px;flex-wrap:wrap;margin-top:20px}.practice-filters button{display:flex;align-items:center;gap:8px;border:1px solid #e3e9df;border-radius:6px;padding:7px 12px;font:inherit;font-size:12px;color:#7b8d70;background:#fff}.practice-filters button[aria-pressed=true]{background:#eff6ec;border-color:#b6cdb4;color:#356c50}.practice-filters small{font-size:10px;opacity:.8}.practice-retry{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-top:20px;padding-top:18px;border-top:1px solid #edf0e8}.practice-retry>span{font-size:11px;color:#8d9b83;line-height:1.8}.practice-retry button{flex-shrink:0}@media(max-width:650px){.practice-overview{padding:18px}.practice-metrics{grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.practice-retry{align-items:flex-start;flex-direction:column}.practice-filters{gap:6px}.practice-filters button{padding:7px 9px}}
.practice-page{flex:1;min-height:0;overflow:auto;padding:32px clamp(20px,4vw,60px) 60px;background:#fafbf9}.practice-page>div,.practice-page>section,.practice-page>article{max-width:900px;margin-left:auto;margin-right:auto}.practice-heading h1{font-size:27px;color:#355b4b;margin:12px 0}.practice-heading>p{color:#83917e;font-size:13px;line-height:1.8}.practice-setup,.practice-card,.practice-history{border:1px solid #e3e9df;background:#fff;border-radius:11px;padding:24px;margin-top:24px}.practice-setup-top,.practice-card-top,.practice-answer-actions,.practice-history-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.practice-setup-top strong{display:flex;align-items:center;gap:8px;color:#587553;font-size:14px}.practice-setup label{display:flex;align-items:center;gap:8px;font-size:12px;cursor:pointer}.practice-setup input{accent-color:#167c65;width:15px;height:15px;flex-shrink:0}.practice-setup>p{font-size:12px;color:#8d9b83;line-height:1.8;margin:15px 0}.practice-document-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:15px 20px;margin-top:22px}.practice-document-list span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.practice-warning{color:#a16630!important}.practice-error{border:1px solid #e9d4c6;border-radius:8px;color:#915638;background:#fff8f2;font-size:13px;line-height:1.8;padding:13px 17px;margin-top:20px}.practice-card h2{font-size:19px;line-height:1.9;margin:18px 0 8px;color:#3e5843}.practice-card-top>span:last-child,.practice-origin{color:#96a18b;font-size:11px;line-height:1.9}.practice-pill{display:inline-flex;border:1px solid #e0e9d9;border-radius:5px;background:#f5f8ef;padding:4px 8px;font-size:11px;color:#799062;flex-shrink:0}.practice-card form{margin-top:24px}.practice-answer-label{display:block;font-size:13px;color:#6a7c5b;margin-bottom:10px}.practice-card textarea{width:100%;resize:vertical;min-height:125px;max-height:380px;border:1px solid #dce6d7;border-radius:8px;padding:14px;color:#536149;font:inherit;font-size:13px;line-height:1.9;background:#fdfefd;box-sizing:border-box}.practice-card textarea:focus{outline:2px solid #a7c8ad;outline-offset:1px}.practice-card textarea[readonly]{background:#f7f9f4}.practice-answer-actions{margin-top:12px}.practice-answer-actions small{color:#a2ad98;font-size:11px}.practice-review{border-top:1px solid #e7ece2;margin-top:27px;padding-top:24px}.practice-result{display:flex;align-items:center;gap:22px;margin-bottom:22px}.practice-result>strong{font-size:34px;color:#167c65;font-weight:550}.practice-result small{font-size:13px;color:#9ba88b;margin-left:3px}.practice-result>p,.practice-check p{font-size:13px;line-height:1.9;color:#75846a}.practice-check{display:flex;align-items:flex-start;gap:13px;margin:13px 0}.practice-check p{margin:0}.practice-pill.INCORRECT{background:#fff4ed;border-color:#efdacc;color:#a37552}.practice-pill.MISSING,.practice-pill.PARTIAL{background:#faf7e9;border-color:#e9e2c8;color:#a2935b}.practice-review h3{font-size:14px;margin:25px 0 13px;color:#54724d}.practice-evidence{margin-top:22px;border:1px solid #e5eadf;border-radius:8px;padding:14px;font-size:12px;line-height:1.9;color:#6d805e}.practice-evidence summary{cursor:pointer;color:#526e4c}.practice-evidence>div{margin-top:17px}.practice-evidence strong{font-size:12px;font-weight:500}.practice-evidence blockquote{border-left:2px solid #b9cdb0;background:#f6f9f1;margin:10px 0;padding:9px 13px;white-space:pre-wrap;overflow-wrap:anywhere}.practice-note{font-size:11px;color:#9aa68f;line-height:1.8;margin:18px 0 0}.practice-history h2{font-size:16px;color:#586c4f;margin:0}.practice-history h2 small{font-size:11px;color:#9bad8b;margin-left:5px}.practice-history-row{display:flex;align-items:center;gap:15px;padding:16px 0;border-bottom:1px solid #edf0e8}.practice-history-row:last-child{border:0}.practice-history-open{text-align:left;min-width:0;flex:1}.practice-history-open strong{display:block;font-size:13px;font-weight:500;line-height:1.8;color:#6d7f5e}.practice-history-open small{display:block;font-size:11px;color:#a1ad94;margin-top:4px}.practice-empty{font-size:12px;color:#a1ad94;line-height:1.9;margin:20px 0}.practice-delete-confirm{font-size:11px;color:#9b7960;display:flex;gap:9px;align-items:center;flex-wrap:wrap}@media(max-width:650px){.practice-document-list{grid-template-columns:1fr}.practice-setup,.practice-card,.practice-history{padding:18px}.practice-setup-top{align-items:flex-start;flex-direction:column}.practice-result{gap:14px}.practice-history-row{align-items:flex-start;flex-wrap:wrap}}
</style>

<style>
@media(max-width:650px){.app-shell .sidebar nav{gap:2px}.app-shell .sidebar nav button{min-width:0;padding:0 5px;gap:4px;font-size:11px;white-space:nowrap}.app-shell .sidebar nav button svg{width:13px}.app-shell .sidebar nav .nav-count{display:none}}
</style>
