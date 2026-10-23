<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import Icon from './Icon.vue'
import AnswerText from './AnswerText.vue'
import PracticePanel from './PracticePanel.vue'
import { isPracticeRequest } from './practice'
import { api, ApiError, session, type User, type Health, type Document, type Answer, type Citation, type DocumentDetail } from './api'

type Page = 'chat' | 'documents' | 'history' | 'practice'
const user = ref<User | null>(null)
const health = ref<Health | null>(null)
const starting = ref(true)
const page = ref<Page>('chat')
const practiceAutoStart = ref(false)
const documents = ref<Document[]>([])
const history = ref<Answer[]>([])
const activeAnswers = ref<Answer[]>([])
const search = ref('')
const selectedDocuments = ref<string[]>([])
const question = ref('')
const pendingQuestion = ref('')
const asking = ref(false)
const uploading = ref(false)
const loadingWorkspace = ref(false)
const authMode = ref<'login' | 'register'>('login')
const credentials = ref({ username: '', displayName: '', password: '' })
const authBusy = ref(false)
const authError = ref('')
const toast = ref<{ message: string; error: boolean } | null>(null)
let toastTimer: number | undefined
const uploadInput = ref<HTMLInputElement>()
const chatScroll = ref<HTMLElement>()
const previewDialog = ref<HTMLDialogElement>()
const deleteDialog = ref<HTMLDialogElement>()
const infoDialog = ref<HTMLDialogElement>()
const preview = ref<DocumentDetail | null>(null)
const previewLoading = ref(false)
const highlightedChunk = ref('')
const deletion = ref<{ type: 'document' | 'answer'; id: string; name: string } | null>(null)
const deleting = ref(false)
const indexing = ref('')
let workspaceVersion = 0
let workspaceRequest = 0
let previewRequest = 0

const labels: Record<Page, string> = { chat: '文档问答', documents: '我的知识库', history: '问答历史', practice: '笔记练习' }
const filteredDocuments = computed(() => documents.value.filter(d => d.name.toLowerCase().includes(search.value.toLowerCase())))
const filteredHistory = computed(() => history.value.filter(a => a.question.toLowerCase().includes(search.value.toLowerCase())))
const totalChunks = computed(() => documents.value.reduce((sum, d) => sum + d.chunkCount, 0))
const latestCitations = computed(() => activeAnswers.value.at(-1)?.citations || [])
const suggestions = ['Spring Boot 的自动配置有什么作用？', '如何保证用户资料隔离？', '数据库事务的 ACID 是什么？']

function notify(message: string, error = false) {
  clearTimeout(toastTimer)
  toast.value = { message, error }
  toastTimer = window.setTimeout(() => { toast.value = null }, 5000)
}
function errorMessage(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
function formatDate(value: string) {
  return new Date(`${value}Z`).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function size(bytes: number) { return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB` }
function modeLabel(mode: string) { return mode === 'AI_GENERATED' ? 'AI 依据资料回答' : mode === 'NO_EVIDENCE' ? '未找到依据' : '原文检索' }
function location(citation: Citation) { return `${citation.fileType === 'pdf' ? `第 ${citation.pageNumber} 页 · ` : ''}片段 ${citation.ordinal}` }
function navigate(target: Page) { page.value = target; search.value = '' }
function newQuestion() { activeAnswers.value = []; pendingQuestion.value = ''; navigate('chat') }
async function scrollChat() { await nextTick(); chatScroll.value?.scrollTo({ top: chatScroll.value.scrollHeight, behavior: 'smooth' }) }
function resetSession() {
  workspaceVersion++
  workspaceRequest++
  previewRequest++
  user.value = null; documents.value = []; history.value = []; activeAnswers.value = []; selectedDocuments.value = []
  preview.value = null; previewLoading.value = false
  loadingWorkspace.value = false
  previewDialog.value?.close(); deleteDialog.value?.close(); infoDialog.value?.close()
  pendingQuestion.value = ''; question.value = ''; page.value = 'chat'
  practiceAutoStart.value = false
}
function expired() { resetSession(); notify('登录已过期，请重新登录', true) }

async function loadWorkspace() {
  const request = ++workspaceRequest
  const version = workspaceVersion
  const current = () => request === workspaceRequest && version === workspaceVersion
  loadingWorkspace.value = true
  try {
    const [docs, answers] = await Promise.all([api<Document[]>('/documents'), api<Answer[]>('/questions')])
    if (!current()) return false
    documents.value = docs; history.value = answers
    selectedDocuments.value = selectedDocuments.value.filter(id => docs.some(d => d.id === id))
    return true
  } catch (error) {
    if (current()) throw error
    return false
  } finally { if (current()) loadingWorkspace.value = false }
}

async function authenticate() {
  authBusy.value = true; authError.value = ''
  try {
    const result = await api<{ token: string; user: User }>(`/auth/${authMode.value}`, {
      method: 'POST', body: JSON.stringify(credentials.value),
    }, true)
    session.save(result.token); user.value = result.user; credentials.value.password = ''
    await loadWorkspace()
  } catch (error) { if (user.value) notify(errorMessage(error), true); else authError.value = errorMessage(error) }
  finally { authBusy.value = false }
}

async function logout() {
  if (asking.value || uploading.value) return
  try { await api<void>('/auth/logout', { method: 'POST' }) }
  catch (error) { notify(errorMessage(error), true) }
  finally { session.clear(); resetSession() }
}

async function importSample() {
  if (uploading.value) return
  uploading.value = true
  try {
    await api<Document>('/documents/sample', { method: 'POST' })
    await loadWorkspace(); notify('示例资料已加入你的知识库，可以开始提问了')
  } catch (error) { notify(errorMessage(error), true) }
  finally { uploading.value = false }
}

async function uploadFiles(files: FileList | File[] | null) {
  if (!files?.length || uploading.value) return
  uploading.value = true
  let uploaded = 0
  try {
    for (const file of Array.from(files).slice(0, 10)) {
      if (!/\.(pdf|txt|md)$/i.test(file.name)) { notify(`${file.name}：仅支持 PDF、TXT、MD`, true); continue }
      if (file.size > 10 * 1024 * 1024) { notify(`${file.name}：不能超过 10 MB`, true); continue }
      const body = new FormData(); body.append('file', file)
      try { await api<Document>('/documents', { method: 'POST', body }); uploaded++ }
      catch (error) { notify(errorMessage(error), true); if (error instanceof ApiError && error.status === 401) break }
    }
    if (user.value) await loadWorkspace()
    if (uploaded) notify(`已添加 ${uploaded} 份资料，解析完成`)
  } catch (error) { notify(errorMessage(error), true) }
  finally { uploading.value = false; if (uploadInput.value) uploadInput.value.value = '' }
}
function drop(event: DragEvent) { event.preventDefault(); uploadFiles(event.dataTransfer?.files || null) }

async function ask(text?: string) {
  const value = (text || question.value).trim()
  if (!value || asking.value || !documents.value.length) return
  if (isPracticeRequest(value)) { question.value = ''; practiceAutoStart.value = true; navigate('practice'); return }
  const version = workspaceVersion
  question.value = ''; pendingQuestion.value = value; asking.value = true; navigate('chat'); scrollChat()
  try {
    const result = await api<Answer>('/questions', {
      method: 'POST', body: JSON.stringify({ question: value, documentIds: selectedDocuments.value }),
    })
    if (version !== workspaceVersion) return
    activeAnswers.value.push(result); history.value.unshift(result); history.value = history.value.slice(0, 100)
  } catch (error) { if (version === workspaceVersion) { question.value = value; notify(errorMessage(error), true) } }
  finally { pendingQuestion.value = ''; asking.value = false; scrollChat() }
}

function openAnswer(answer: Answer) { if (asking.value) return; activeAnswers.value = [answer]; navigate('chat'); scrollChat() }
async function feedback(answer: Answer, value: number) {
  const next = answer.feedback === value ? null : value
  try {
    await api<void>(`/questions/${answer.id}/feedback`, { method: 'PATCH', body: JSON.stringify({ feedback: next }) })
    answer.feedback = next
    const row = history.value.find(a => a.id === answer.id)
    if (row) row.feedback = next
    notify(next === null ? '已撤销反馈' : '反馈已保存')
  } catch (error) { notify(errorMessage(error), true) }
}
async function copy(answer: Answer) {
  try { await navigator.clipboard.writeText(answer.answer); notify('已复制回答') }
  catch { notify('复制失败，请手动选择文字复制', true) }
}
async function openDocument(id: string, chunkId = '') {
  const request = ++previewRequest
  const version = workspaceVersion
  const current = () => request === previewRequest && version === workspaceVersion
  preview.value = null; highlightedChunk.value = chunkId; previewLoading.value = true
  previewDialog.value?.showModal()
  try {
    const result = await api<DocumentDetail>(`/documents/${id}`)
    if (!current()) return
    preview.value = result
    await nextTick()
    if (current() && chunkId) document.getElementById(`part-${chunkId}`)?.scrollIntoView({ block: 'center' })
  } catch (error) { if (current()) { previewDialog.value?.close(); notify(errorMessage(error), true) } }
  finally { if (current()) previewLoading.value = false }
}
function confirmDelete(type: 'document' | 'answer', id: string, name: string) {
  deletion.value = { type, id, name }; deleteDialog.value?.showModal()
}
async function deleteRecord() {
  if (!deletion.value || deleting.value) return
  deleting.value = true
  try {
    const { type, id } = deletion.value
    await api<void>(`/${type === 'document' ? 'documents' : 'questions'}/${id}`, { method: 'DELETE' })
    if (type === 'answer') activeAnswers.value = activeAnswers.value.filter(a => a.id !== id)
    await loadWorkspace(); deleteDialog.value?.close(); notify('已删除')
  } catch (error) { notify(errorMessage(error), true) }
  finally { deleting.value = false }
}
async function refresh() { try { if (await loadWorkspace()) notify('已刷新') } catch (error) { notify(errorMessage(error), true) } }

async function reindex(doc: Document) {
  if (indexing.value) return
  indexing.value = doc.id
  try {
    await api<Document>(`/documents/${doc.id}/index`, { method: 'POST' })
    await loadWorkspace(); notify('语义索引已建立，可以按意思查找资料了')
  } catch (error) { notify(errorMessage(error), true) }
  finally { indexing.value = '' }
}

onMounted(async () => {
  window.addEventListener('session-expired', expired)
  try {
    health.value = await api<Health>('/health', {}, true)
    if (session.token()) { user.value = await api<User>('/auth/me'); await loadWorkspace() }
  } catch (error) { notify(errorMessage(error), true) }
  finally { starting.value = false }
})
onUnmounted(() => { workspaceRequest++; previewRequest++; window.removeEventListener('session-expired', expired); clearTimeout(toastTimer) })
</script>

<template>
  <div v-if="starting" class="startup"><span class="spinner"></span><p>正在打开知页…</p></div>
  <div v-else-if="!user" class="auth-shell">
    <section class="auth-story">
      <a class="brand" href="/" aria-label="知页首页"><span class="brand-mark"><Icon name="book" :size="25" /></span><strong>知页<span class="brand-en">ZHIYE</span></strong></a>
      <div class="auth-intro"><span class="eyebrow">你的个人文档工作台</span><h1>把资料读薄，<br />把答案找准。</h1><p>让零散的学习笔记、技术手册与项目资料，<br />成为随时可以查阅的知识。</p>
        <div class="auth-paper"><div class="paper-heading"><span class="file-icon md"><Icon name="files" /></span><span>Java 学习手册.md<small>从资料出发，有据可查</small></span><span class="tiny-dot"></span></div><div class="paper-line w90"></div><div class="paper-line"></div><div class="paper-highlight"><Icon name="quote" :size="18" /><span>每个答案，都能回到原文。</span><b>[1]</b></div><div class="paper-line w90"></div><div class="paper-line w60"></div><span class="paper-stamp"><Icon name="check" :size="13" /> 保留文档出处</span></div>
      </div>
      <div class="auth-foot"><Icon name="lock" :size="15" /> 你的资料，只在你的个人空间中检索</div>
    </section>
    <main class="auth-form-wrap"><form class="auth-form" @submit.prevent="authenticate">
      <span class="eyebrow">开始积累，随时查阅</span><h2>{{ authMode === 'login' ? '欢迎回来' : '创建你的知识空间' }}</h2><p>{{ authMode === 'login' ? '登录后，继续与你的资料对话。' : '从第一份资料开始，让知识逐渐连接。' }}</p>
      <label>用户名<input v-model="credentials.username" name="username" autocomplete="username" required minlength="3" maxlength="32" pattern="[A-Za-z0-9_]{3,32}" placeholder="3–32 位英文字母、数字或下划线" /></label>
      <label v-if="authMode === 'register'">昵称<input v-model="credentials.displayName" name="displayName" autocomplete="nickname" required maxlength="40" placeholder="你希望我们如何称呼你" /></label>
      <label>密码<input v-model="credentials.password" name="password" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" type="password" required :minlength="authMode === 'register' ? 8 : 1" maxlength="72" placeholder="至少 8 位字符" /></label>
      <div v-if="authError" class="inline-error" role="alert">{{ authError }}</div>
      <button class="button primary auth-submit" :disabled="authBusy"><span v-if="authBusy" class="spinner light"></span>{{ authBusy ? '正在进入…' : authMode === 'login' ? '登录知页' : '创建账号并进入' }}<Icon v-if="!authBusy" name="chevron" :size="17" /></button>
      <div class="auth-switch">{{ authMode === 'login' ? '还没有账号？' : '已经有账号？' }}<button type="button" class="text-button" @click="authMode = authMode === 'login' ? 'register' : 'login'; authError = ''">{{ authMode === 'login' ? '创建账号' : '立即登录' }}</button></div>
      <div class="auth-note"><span class="status-dot"></span>{{ health ? (health.aiEnabled ? '模型已配置，可依据资料生成回答' : '语义检索已就绪，无需模型密钥即可开始') : '服务未连接，请检查后刷新页面' }}</div>
    </form></main>
  </div>

  <div v-else class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="newQuestion"><span class="brand-mark"><Icon name="book" :size="24" /></span><strong>知页<span class="brand-en">ZHIYE</span></strong></a>
      <button class="new-button" :disabled="asking" @click="newQuestion"><Icon name="plus" :size="18" /> 开始新问答<span>＋</span></button>
      <span class="nav-caption">工作空间</span>
      <nav aria-label="主导航">
        <button :class="{ active: page === 'chat' }" @click="navigate('chat')"><Icon name="chat" />文档问答</button>
        <button :class="{ active: page === 'practice' }" @click="navigate('practice')"><Icon name="spark" />笔记练习</button>
        <button :class="{ active: page === 'documents' }" @click="navigate('documents')"><Icon name="files" />我的知识库<span class="nav-count">{{ documents.length }}</span></button>
        <button :class="{ active: page === 'history' }" @click="navigate('history')"><Icon name="history" />问答历史</button>
      </nav>
      <div class="recent"><span class="nav-caption">最近问过</span><button v-for="answer in history.slice(0, 4)" :key="answer.id" class="recent-item" :title="answer.question" :disabled="asking" @click="openAnswer(answer)"><span></span>{{ answer.question }}</button><p v-if="!history.length">你的问题，会慢慢积累在这里。</p></div>
      <div class="sidebar-bottom"><div class="space-note"><span class="space-symbol"><Icon name="book" :size="17" /></span><div>一点一滴，形成自己的知识库<small>{{ documents.length }} 份资料 · {{ totalChunks }} 个片段</small></div></div><button class="runtime-button" @click="infoDialog?.showModal()"><span class="status-dot"></span>{{ health?.aiEnabled ? 'AI 问答已配置' : '语义检索已就绪' }}<Icon name="info" :size="15" /></button><div class="user-row"><span class="avatar">{{ user.displayName.slice(0, 1) }}</span><div><strong>{{ user.displayName }}</strong><small>个人空间</small></div><button class="icon-button" aria-label="退出登录" title="退出登录" :disabled="asking || uploading" @click="logout"><Icon name="logout" :size="18" /></button></div></div>
    </aside>

    <div class="workspace">
      <header class="topbar"><div><span class="breadcrumb">个人空间</span><Icon name="chevron" :size="13" /><strong>{{ labels[page] }}</strong></div><div class="topbar-right"><span class="mode-badge"><span class="status-dot"></span>{{ health?.aiEnabled ? (health.localModel ? '本地 AI 问答' : 'AI 资料问答') : '语义检索模式' }}</span><button class="icon-button" title="运行状态" aria-label="运行状态" @click="infoDialog?.showModal()"><Icon name="info" :size="19" /></button><button class="icon-button mobile-logout" title="退出登录" aria-label="退出登录" :disabled="asking || uploading" @click="logout"><Icon name="logout" :size="18" /></button></div></header>

      <main v-if="page === 'chat'" class="chat-layout">
        <section class="chat-main">
          <div ref="chatScroll" class="chat-scroll" aria-live="polite">
            <div v-if="!activeAnswers.length && !asking" class="welcome">
              <div class="welcome-icon"><Icon name="book" :size="34" /><span><Icon name="spark" :size="14" /></span></div>
              <span class="eyebrow">从你的资料中，找到下一步</span><h1>让每一份资料，都有答案。</h1><p>上传文档，提出问题。每一条线索，都有原文可循。</p>
              <div v-if="!documents.length" class="getting-started"><div class="step"><span>01</span><div><strong>放入你的第一份资料</strong><small>支持 PDF、TXT、Markdown</small></div></div><div class="step"><span>02</span><div><strong>用自己的问题开始探索</strong><small>查看相关原文与引用出处</small></div></div><div class="welcome-actions"><button class="button primary" :disabled="uploading" @click="uploadInput?.click()"><Icon name="upload" :size="17" />上传资料</button><button class="button secondary" :disabled="uploading" @click="importSample"><span v-if="uploading" class="spinner"></span><Icon v-else name="book" :size="17" />导入示例，试一试</button></div></div>
              <div v-else class="suggestions"><span>可以从这些问题开始</span><button v-for="suggestion in suggestions" :key="suggestion" @click="ask(suggestion)">{{ suggestion }}<Icon name="chevron" :size="15" /></button><small>这些问题适用于内置的 Java 示例资料，也可以在下方输入自己的问题。</small></div>
            </div>
            <div v-for="answer in activeAnswers" :key="answer.id" class="exchange"><div class="question-bubble">{{ answer.question }}</div><article class="answer-card"><div class="answer-heading"><span class="answer-avatar"><Icon name="book" :size="17" /></span><strong>知页</strong><span>{{ modeLabel(answer.answerMode) }}</span></div><p v-if="answer.answerMode === 'LOCAL_RETRIEVAL'" class="retrieval-note">以下为匹配的文档原文，未调用语言模型进行总结。</p><AnswerText :text="answer.answer" :citations="answer.citations" @source="source => openDocument(source.documentId, source.chunkId)" /><div v-if="answer.citations.length" class="inline-sources"><button v-for="source in answer.citations" :key="source.chunkId" @click="openDocument(source.documentId, source.chunkId)"><b>{{ source.index }}</b>{{ source.documentName }}<Icon name="external" :size="12" /></button></div><div class="answer-footer"><span>{{ formatDate(answer.createdAt) }} · {{ answer.citations.length }} 处来源</span><div><button class="icon-button" aria-label="复制回答" title="复制回答" @click="copy(answer)"><Icon name="copy" :size="15" /></button><button class="icon-button" :class="{ chosen: answer.feedback === 1 }" aria-label="回答有帮助" title="有帮助" @click="feedback(answer, 1)"><Icon name="thumbUp" :size="16" /></button><button class="icon-button" :class="{ chosen: answer.feedback === -1 }" aria-label="回答没有帮助" title="没有帮助" @click="feedback(answer, -1)"><Icon name="thumbDown" :size="16" /></button></div></div></article></div>
            <div v-if="asking" class="exchange"><div class="question-bubble">{{ pendingQuestion }}</div><div class="thinking"><span class="spinner"></span>{{ health?.aiEnabled ? '正在查阅资料并整理答案…' : '正在检索相关原文…' }}</div></div>
          </div>
          <form class="composer-wrap" @submit.prevent="ask()"><div class="composer"><textarea v-model="question" name="question" aria-label="输入资料问题" :placeholder="documents.length ? '向你的资料提问…' : '先上传一份资料，再从这里开始提问'" rows="2" maxlength="1000" :disabled="!documents.length || asking" @keydown.enter.exact.prevent="ask()"></textarea><div class="composer-bottom"><span><Icon name="files" :size="14" />{{ selectedDocuments.length ? `已选 ${selectedDocuments.length} 份资料` : `全部 ${documents.length} 份资料` }}</span><div><small>{{ question.length }}/1000</small><button class="send-button" :disabled="asking || !question.trim() || !documents.length" aria-label="发送问题"><Icon name="arrow" :size="19" /></button></div></div></div><p>{{ health?.aiEnabled ? '回答由模型根据检索到的片段生成，请结合引用核对。' : '已启用中文语义检索；接入语言模型后，可根据资料归纳回答。' }}</p></form>
        </section>

        <aside class="context-panel"><div class="context-heading"><span><Icon name="files" :size="17" />参考资料</span><button class="icon-button" title="添加资料" aria-label="添加资料" :disabled="uploading" @click="uploadInput?.click()"><Icon name="plus" :size="17" /></button></div><p class="panel-description">选择本次问答要查阅的文档，<br />未选择时检索全部资料。</p><div v-if="!documents.length" class="context-empty"><Icon name="files" :size="29" /><strong>还没有资料</strong><p>上传你的学习笔记或技术手册，<br />让问题有据可查。</p><button class="text-button" :disabled="uploading" @click="uploadInput?.click()">添加第一份资料 <Icon name="plus" :size="13" /></button></div><div v-else class="context-documents"><label v-for="doc in documents" :key="doc.id" class="context-document"><input v-model="selectedDocuments" :value="doc.id" type="checkbox" :disabled="asking" /><span class="file-icon" :class="doc.fileType"><Icon name="files" :size="17" /></span><span><strong :title="doc.name">{{ doc.name }}</strong><small>{{ doc.chunkCount }} 个片段 · {{ size(doc.sizeBytes) }}</small></span></label></div><div class="panel-divider"></div><div class="context-heading"><span><Icon name="quote" :size="17" />本次引用</span><span class="count-label">{{ latestCitations.length }}</span></div><div v-if="!latestCitations.length" class="source-placeholder">回答后，相关原文和出处<br />会出现在这里。</div><details v-for="source in latestCitations" :key="source.chunkId" class="source-card" open><summary><span class="source-number">{{ source.index }}</span><strong>{{ source.documentName }}</strong><Icon name="chevronDown" :size="14" /></summary><small>{{ location(source) }}</small><div v-if="source.supportingQuotes?.length" class="verified-quotes"><span><Icon name="check" :size="12" />回答依据 · 原文摘录</span><blockquote v-for="(quote, i) in source.supportingQuotes" :key="i">{{ quote }}</blockquote></div><blockquote v-else>{{ source.content }}</blockquote><button class="text-button" @click="openDocument(source.documentId, source.chunkId)">定位原文 <Icon name="external" :size="12" /></button></details><div class="panel-tip"><Icon name="lock" :size="15" /><span>先检索你的个人资料，再组织回答。<br />引用保留回答当时的原文。</span></div></aside>
      </main>

      <PracticePanel v-else-if="page === 'practice'" :key="user.id" :user-id="user.id" :documents="documents" :enabled="health?.aiEnabled === true" :auto-start="practiceAutoStart" @started="practiceAutoStart = false" @manage="navigate('documents')" @source="citation => openDocument(citation.documentId, citation.chunkId)" />

      <main v-else class="collection-page">
        <div class="page-heading"><div><span class="eyebrow">{{ page === 'documents' ? '知识，在这里慢慢生长' : '每一个问题，都是积累' }}</span><h1>{{ labels[page] }}</h1><p>{{ page === 'documents' ? '管理你的学习笔记、技术文档和项目资料。' : '回看查阅过的资料，保留每次探索的线索。' }}</p></div><button v-if="page === 'documents'" class="button primary" :disabled="uploading" @click="uploadInput?.click()"><Icon name="plus" :size="17" />添加资料</button><button v-else class="button primary" :disabled="asking" @click="newQuestion"><Icon name="plus" :size="17" />开始新问答</button></div>
        <div class="stats"><div><span>文档资料</span><strong>{{ documents.length }}<small>份</small></strong></div><div><span>可检索片段</span><strong>{{ totalChunks }}<small>个</small></strong></div><div><span>最近问答</span><strong>{{ history.length }}<small>条</small></strong></div></div>
        <div v-if="page === 'documents'" class="upload-zone" :class="{ busy: uploading }" @dragover.prevent @drop="drop"><span class="upload-zone-icon"><Icon name="upload" :size="24" /></span><div><strong>{{ uploading ? '正在上传并解析，请稍候…' : '拖入资料，或点击选择文件' }}</strong><small>PDF、TXT、MD · 单份不超过 10 MB · PDF 不超过 100 页</small></div><button class="button secondary" :disabled="uploading" @click="uploadInput?.click()">选择文件</button></div>
        <div class="collection-toolbar"><strong>{{ page === 'documents' ? '全部资料' : '最近 100 条问答' }} <span>{{ page === 'documents' ? documents.length : history.length }}</span></strong><div><label class="search-field"><Icon name="search" :size="16" /><input v-model="search" :aria-label="page === 'documents' ? '搜索文档名称' : '搜索历史问题'" :placeholder="page === 'documents' ? '搜索文档名称' : '搜索历史问题'" /></label><button class="text-button" :disabled="loadingWorkspace" @click="refresh">刷新</button></div></div>
        <div v-if="page === 'documents'" class="document-list"><div class="list-labels"><span>文档名称</span><span>资料信息</span><span>添加时间</span><span>操作</span></div><div v-for="doc in filteredDocuments" :key="doc.id" class="document-row"><button class="document-title" @click="openDocument(doc.id)"><span class="file-icon" :class="doc.fileType"><Icon name="files" :size="21" /></span><span><strong>{{ doc.name }}</strong><small><span class="status-dot"></span>{{ doc.indexedChunks === doc.chunkCount ? '语义索引完成' : '待建立语义索引' }} · {{ doc.fileType.toUpperCase() }}</small></span></button><div class="document-meta">{{ doc.chunkCount }} 个片段<small>{{ size(doc.sizeBytes) }}{{ doc.fileType === 'pdf' ? ` · ${doc.pageCount} 页` : '' }}</small></div><time>{{ formatDate(doc.createdAt) }}</time><div class="row-actions"><button v-if="doc.indexedChunks !== doc.chunkCount" class="text-button" :disabled="!!indexing" @click="reindex(doc)">{{ indexing === doc.id ? '正在索引…' : '建立索引' }}</button><button class="text-button" @click="openDocument(doc.id)">查看原文</button><button class="icon-button danger-hover" :aria-label="`删除 ${doc.name}`" title="删除资料" @click="confirmDelete('document', doc.id, doc.name)"><Icon name="trash" :size="17" /></button></div></div><div v-if="!filteredDocuments.length" class="list-empty"><Icon name="files" :size="34" /><h3>{{ search ? '没有找到匹配的文档' : '给知识留出第一页' }}</h3><p>{{ search ? '试试其他名称关键词。' : '上传自己的资料，或导入一份 Java 示例手册。' }}</p><button v-if="!search" class="button secondary" :disabled="uploading" @click="importSample">导入示例资料</button></div></div>
        <div v-else class="history-list"><article v-for="answer in filteredHistory" :key="answer.id" class="history-row"><span class="history-icon"><Icon name="chat" :size="19" /></span><button class="history-title" :disabled="asking" @click="openAnswer(answer)"><strong>{{ answer.question }}</strong><small>{{ formatDate(answer.createdAt) }}<span>·</span>{{ answer.citations.length }} 处来源<span>·</span>{{ modeLabel(answer.answerMode) }}</small></button><span v-if="answer.feedback" class="feedback-tag">{{ answer.feedback === 1 ? '有帮助' : '待改进' }}</span><div class="row-actions"><button class="text-button" :disabled="asking" @click="openAnswer(answer)">查看回答</button><button class="icon-button danger-hover" title="删除记录" :aria-label="`删除问题：${answer.question}`" @click="confirmDelete('answer', answer.id, answer.question)"><Icon name="trash" :size="17" /></button></div></article><div v-if="!filteredHistory.length" class="list-empty"><Icon name="history" :size="34" /><h3>{{ search ? '没有找到匹配的问题' : '你的探索，从一个问题开始' }}</h3><p>{{ search ? '试试其他问题关键词。' : '完成一次文档问答后，记录会自动保存在这里。' }}</p><button v-if="!search" class="button secondary" :disabled="asking" @click="newQuestion">前往提问</button></div></div>
        <p class="collection-foot"><Icon name="lock" :size="13" />所有资料与问答记录仅对当前账号可见。</p>
      </main>
    </div>

    <input ref="uploadInput" class="visually-hidden" type="file" accept=".pdf,.txt,.md" multiple aria-label="上传资料文件" @change="uploadFiles(($event.target as HTMLInputElement).files)" />
    <dialog ref="previewDialog" class="preview-dialog"><div class="dialog-header"><div><span class="eyebrow">文档原文</span><h2>{{ preview?.document.name || '正在读取…' }}</h2></div><button class="icon-button" aria-label="关闭原文" @click="previewDialog?.close()"><Icon name="close" /></button></div><div v-if="previewLoading" class="dialog-loading"><span class="spinner"></span>正在读取原文…</div><template v-else-if="preview"><p class="preview-description">以下是解析后的文字片段。相邻片段保留部分重叠，便于完整检索上下文。</p><article v-for="part in preview.parts" :id="`part-${part.id}`" :key="part.id" class="preview-part" :class="{ highlighted: part.id === highlightedChunk }"><div><strong>片段 {{ part.ordinal }}</strong><span>{{ preview.document.fileType === 'pdf' ? `第 ${part.pageNumber} 页 · ` : '' }}字符位置 {{ part.startOffset }}<b v-if="part.id === highlightedChunk">当前引用</b></span></div><pre>{{ part.content }}</pre></article></template></dialog>
    <dialog ref="deleteDialog" class="confirm-dialog"><div class="delete-symbol"><Icon name="trash" :size="25" /></div><h2>删除{{ deletion?.type === 'document' ? '这份资料' : '这条问答记录' }}？</h2><p class="delete-name">{{ deletion?.name }}</p><p>{{ deletion?.type === 'document' ? '删除后不再参与检索。已有问答仍保留当时的引用；需要彻底清除时，请同时删除相关问答记录。' : '删除后无法恢复，你仍可基于现有资料重新提问。' }}</p><div class="dialog-actions"><button class="button secondary" :disabled="deleting" @click="deleteDialog?.close()">取消</button><button class="button danger" :disabled="deleting" @click="deleteRecord">{{ deleting ? '正在删除…' : '确认删除' }}</button></div></dialog>
    <dialog ref="infoDialog" class="info-dialog"><div class="dialog-header"><h2>当前运行状态</h2><button class="icon-button" aria-label="关闭运行状态" @click="infoDialog?.close()"><Icon name="close" /></button></div><div class="runtime-summary"><span class="status-dot"></span>{{ health?.aiEnabled ? '已配置模型服务' : '本地原文检索已就绪' }}</div><p>{{ health?.aiEnabled ? (health.localModel ? '当前由本机 Qwen 模型结合你的资料生成回答，提问、语义检索和生成均在本机完成。' : '问题会先检索你的资料，再将相关片段发送至已配置的模型服务。模型的可用性以实际问答结果为准。') : '当前通过中文向量与关键词查找相关原文，支持同义表达检索。未启用语言模型时返回原文；启用后会归纳回答，并核对支持每个要点的原文摘录。' }}</p><p>来源中保存的是回答时的资料片段。引用可以帮助核对依据，但语义相似度不代表答案正确率，仍请核对引用是否支持回答。</p><small>知页 v{{ health?.version || '0.1.0' }} · 个人文档工作台</small></dialog>
  </div>
  <Transition name="toast"><div v-if="toast" class="toast-message" :class="{ error: toast.error }" role="status"><Icon :name="toast.error ? 'info' : 'check'" :size="18" />{{ toast.message }}<button aria-label="关闭提示" @click="toast = null"><Icon name="close" :size="14" /></button></div></Transition>
</template>
