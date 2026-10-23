import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

function load(source, globals = {}) {
  const context = vm.createContext({ exports: {}, ...globals })
  vm.runInContext(ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, context)
  return context.exports
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function apiFixture() {
  const storage = new Map(), events = [], requests = [], timeouts = []
  const module = load(readFileSync(new URL('../src/api.ts', import.meta.url), 'utf8'), {
    Headers, FormData, AbortController, Event, Error, clearTimeout,
    sessionStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    window: { setTimeout: (callback, delay) => { timeouts.push(delay); return setTimeout(callback, delay) }, dispatchEvent: event => events.push(event.type) },
    fetch: () => { const request = deferred(); requests.push(request); return request.promise },
  })
  return { ...module, events, requests, timeouts }
}
const unauthorized = { ok: false, status: 401, json: async () => ({ message: 'expired fixture session' }) }

test('an old 401 cannot revoke the new login', async () => {
  const fixture = apiFixture()
  fixture.session.save('old-token')
  const pending = fixture.api('/documents').catch(error => error.status)
  fixture.session.save('new-token')
  fixture.requests[0].resolve(unauthorized)
  assert.equal(await pending, 401)
  assert.equal(fixture.session.token(), 'new-token')
  assert.deepEqual(fixture.events, [])
})

test('current-session expiry is reported once even with concurrent requests', async () => {
  const fixture = apiFixture()
  fixture.session.save('current-token')
  const first = fixture.api('/documents').catch(error => error.status)
  const second = fixture.api('/questions').catch(error => error.status)
  fixture.requests.forEach(request => request.resolve(unauthorized))
  await Promise.all([first, second])
  assert.equal(fixture.session.token(), null)
  assert.deepEqual(fixture.events, ['session-expired'])
})

test('failed public login does not clear an existing session', async () => {
  const fixture = apiFixture()
  fixture.session.save('current-token')
  const pending = fixture.api('/auth/login', {}, true).catch(error => error.status)
  fixture.requests[0].resolve(unauthorized)
  await pending
  assert.equal(fixture.session.token(), 'current-token')
  assert.deepEqual(fixture.events, [])
})

test('reviewed question creation allows one wording correction while ordinary requests keep their timeout', async () => {
  const fixture = apiFixture()
  const create = fixture.api('/practice', { method: 'POST' })
  assert.equal(fixture.timeouts[0], 320_000)
  fixture.requests[0].resolve({ ok: true, status: 201, json: async () => ({ id: 'reviewed' }) })
  await create
  const list = fixture.api('/practice')
  assert.equal(fixture.timeouts[1], 120_000)
  fixture.requests[1].resolve({ ok: true, status: 200, json: async () => [] })
  await list
})

function previewFixture() {
  const requests = new Map()
  const source = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0]
  const api = { api: path => { const request = deferred(); requests.set(path, request); return request.promise } }
  let closes = 0
  const module = load(source + '\nexport { openDocument, resetSession, preview, previewDialog, previewLoading, highlightedChunk, toast, loadWorkspace, refresh, documents, history, selectedDocuments, loadingWorkspace }', {
    require: name => name === 'vue' ? { ...vue, onMounted() {}, onUnmounted() {} } : name === './api' ? api : {},
    Error, window: { setTimeout: () => 1 }, clearTimeout() {}, document: { getElementById: () => null },
  })
  module.previewDialog.value = { showModal() {}, close() { closes++ } }
  return { ...module, requests, closes: () => closes }
}
const detail = id => ({ document: { id, name: id }, parts: [] })

test('the latest selected document wins even when the older request completes last', async () => {
  const fixture = previewFixture()
  const first = fixture.openDocument('A')
  const second = fixture.openDocument('B')
  fixture.requests.get('/documents/B').resolve(detail('B'))
  await second
  fixture.requests.get('/documents/A').resolve(detail('A'))
  await first
  assert.equal(fixture.preview.value.document.id, 'B')
})

test('an older error neither closes the current preview nor clears its loading state', async () => {
  const fixture = previewFixture()
  const first = fixture.openDocument('A')
  const second = fixture.openDocument('B')
  fixture.requests.get('/documents/A').reject(new Error('old request failed'))
  await first
  assert.equal(fixture.closes(), 0)
  assert.equal(fixture.previewLoading.value, true)
  assert.equal(fixture.toast.value, null)
  fixture.requests.get('/documents/B').resolve(detail('B'))
  await second
  assert.equal(fixture.preview.value.document.id, 'B')
  assert.equal(fixture.previewLoading.value, false)
})

test('a response from before logout cannot repopulate the preview', async () => {
  const fixture = previewFixture()
  const pending = fixture.openDocument('A')
  fixture.resetSession()
  fixture.requests.get('/documents/A').resolve(detail('A'))
  await pending
  assert.equal(fixture.preview.value, null)
  assert.equal(fixture.previewLoading.value, false)
})

test('an older refresh cannot restore deleted documents or overwrite newer history', async () => {
  const fixture = previewFixture()
  const first = fixture.loadWorkspace()
  const oldDocs = fixture.requests.get('/documents')
  const oldHistory = fixture.requests.get('/questions')
  fixture.selectedDocuments.value = ['deleted']
  const second = fixture.loadWorkspace()
  fixture.requests.get('/documents').resolve([{ id: 'kept' }])
  fixture.requests.get('/questions').resolve([{ id: 'new-answer' }])
  await second
  oldDocs.resolve([{ id: 'deleted' }])
  oldHistory.resolve([{ id: 'old-answer' }])
  await first
  assert.equal(fixture.documents.value.map(doc => doc.id).join(), 'kept')
  assert.equal(fixture.history.value.map(answer => answer.id).join(), 'new-answer')
  assert.equal(fixture.selectedDocuments.value.length, 0)
})

test('an older refresh cannot clear the loading state of a newer refresh', async () => {
  const fixture = previewFixture()
  const first = fixture.loadWorkspace()
  const oldDocs = fixture.requests.get('/documents')
  const oldHistory = fixture.requests.get('/questions')
  const second = fixture.loadWorkspace()
  oldDocs.resolve([])
  oldHistory.resolve([])
  await first
  assert.equal(fixture.loadingWorkspace.value, true)
  fixture.requests.get('/documents').resolve([])
  fixture.requests.get('/questions').resolve([])
  await second
  assert.equal(fixture.loadingWorkspace.value, false)
})

test('logout clears loading immediately and discards late workspace data', async () => {
  const fixture = previewFixture()
  const pending = fixture.loadWorkspace()
  fixture.resetSession()
  assert.equal(fixture.loadingWorkspace.value, false)
  fixture.requests.get('/documents').resolve([{ id: 'previous-user-doc' }])
  fixture.requests.get('/questions').resolve([{ id: 'previous-user-answer' }])
  await pending
  assert.equal(fixture.documents.value.length, 0)
  assert.equal(fixture.history.value.length, 0)
})

test('a failed refresh from before logout cannot disturb the current workspace', async () => {
  const fixture = previewFixture()
  const first = fixture.refresh()
  const oldDocs = fixture.requests.get('/documents')
  const oldHistory = fixture.requests.get('/questions')
  fixture.resetSession()
  const second = fixture.refresh()
  oldDocs.reject(new Error('previous user refresh failed'))
  oldHistory.resolve([])
  await first
  assert.equal(fixture.toast.value, null)
  assert.equal(fixture.loadingWorkspace.value, true)
  fixture.requests.get('/documents').resolve([{ id: 'current-user-doc' }])
  fixture.requests.get('/questions').resolve([])
  await second
  assert.equal(fixture.toast.value.message, '已刷新')
})

test('a current refresh failure is still reported and clears loading', async () => {
  const fixture = previewFixture()
  const pending = fixture.refresh()
  fixture.requests.get('/documents').reject(new Error('current refresh failed'))
  fixture.requests.get('/questions').resolve([])
  await pending
  assert.equal(fixture.toast.value.message, 'current refresh failed')
  assert.equal(fixture.toast.value.error, true)
  assert.equal(fixture.loadingWorkspace.value, false)
})

function practiceFixture(options = {}) {
  const requests = []
  let unmount = () => {}, mount = () => {}, token = options.token || 'owner-token'
  const storage = options.storage || new Map(), timers = new Map(), events = new Map()
  let timerId = 0
  const sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) }
  const source = readFileSync(new URL('../src/PracticePanel.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0]
  const props = { userId: options.userId || 'owner', documents: [{ id: 'one' }, { id: 'two' }], enabled: true, autoStart: false }
  const practice = load(readFileSync(new URL('../src/practice.ts', import.meta.url), 'utf8'))
  const drafts = load(readFileSync(new URL('../src/practice-drafts.ts', import.meta.url), 'utf8'), { sessionStorage })
  const module = load(source + '\nexport { generate, submit, retry, remove, open, activate, flushDraft, resolveDraft, leavePractice, draftState, draftError, savedAnswer, loadHistory, allDocuments, selected, active, records, answer, working, error, overview, historyFilter, filteredRecords }', {
    require: name => name === 'vue' ? { ...vue, onMounted: fn => { mount = fn }, onUnmounted: fn => { unmount = fn } } : name === './api' ? {
      api: (path, options) => { const request = deferred(); requests.push({ ...request, path, options }); return request.promise },
      session: { token: () => token },
    } : name === './practice' ? practice : name === './practice-drafts' ? drafts : {},
    Error, defineProps: () => props, defineEmits: () => () => {},
    setTimeout: fn => { const id = ++timerId; timers.set(id, fn); return id }, clearTimeout: id => timers.delete(id),
    window: { addEventListener: (name, fn) => events.set(name, fn), removeEventListener: name => events.delete(name) },
  })
  return { ...module, requests, storage, timers, events, mount: () => mount(), unmount: () => unmount(), setToken: value => { token = value } }
}
async function settle() { for (let i = 0; i < 12; i++) await Promise.resolve() }
const exercise = { id: 'quiz', question: '子类先初始化哪一部分？', documents: ['继承.md'], citations: [], qualityChecked: true, createdAt: '2026-10-22T10:00:00' }

test('practice uses all notes by default and supports an explicit source selection', async () => {
  const fixture = practiceFixture()
  const first = fixture.generate()
  assert.deepEqual(JSON.parse(fixture.requests[0].options.body), { documentIds: [] })
  fixture.requests[0].resolve(exercise)
  await first
  fixture.allDocuments.value = false
  fixture.selected.value = ['two']
  const second = fixture.generate()
  assert.deepEqual(JSON.parse(fixture.requests[1].options.body), { documentIds: ['two'] })
  fixture.requests[1].resolve({ ...exercise, id: 'quiz-two' })
  await second
  assert.equal(fixture.active.value.id, 'quiz-two')
})

test('a failed grade retains the draft and double clicks cannot submit it twice', async () => {
  const fixture = practiceFixture()
  fixture.active.value = exercise
  fixture.answer.value = '先初始化父类'
  const pending = fixture.submit()
  await fixture.submit()
  assert.equal(fixture.requests.length, 1)
  assert.equal(fixture.requests[0].path, '/practice/quiz/draft')
  fixture.requests[0].resolve({ ...exercise, studentAnswer: '先初始化父类' })
  await settle()
  assert.deepEqual(JSON.parse(fixture.requests[1].options.body), { answer: '先初始化父类', expectedAnswer: '先初始化父类' })
  fixture.requests[1].reject(new Error('model temporarily unavailable'))
  await pending
  assert.equal(fixture.answer.value, '先初始化父类')
  assert.equal(fixture.error.value, 'model temporarily unavailable')
  assert.equal(fixture.working.value, '')
  const retry = fixture.submit()
  await settle()
  fixture.requests[2].resolve({ ...exercise, studentAnswer: '先初始化父类', grade: { score: 100, checks: [] } })
  await retry
  assert.equal(fixture.active.value.grade.score, 100)
  await fixture.submit()
  assert.equal(fixture.requests.length, 3)
})

test('leaving practice prevents a late response from restoring its previous session', async () => {
  const fixture = practiceFixture()
  const pending = fixture.generate()
  fixture.unmount()
  fixture.requests[0].resolve(exercise)
  await pending
  assert.equal(fixture.active.value, null)
  assert.equal(fixture.records.value.length, 0)
})

test('an older history read cannot overwrite a newly generated practice record', async () => {
  const fixture = practiceFixture()
  const oldHistory = fixture.loadHistory()
  const current = fixture.generate()
  fixture.requests[1].resolve(exercise)
  await current
  fixture.requests[0].resolve([{ ...exercise, id: 'older' }])
  await oldHistory
  assert.equal(fixture.records.value[0].id, 'quiz')
})

test('an unchecked legacy exercise or a missing review flag cannot be submitted for grading', async () => {
  for (const qualityChecked of [false, undefined]) {
    const fixture = practiceFixture()
    fixture.active.value = { ...exercise, qualityChecked }
    fixture.answer.value = '先初始化父类部分'
    await fixture.submit()
    assert.equal(fixture.requests.length, 0)
    assert.equal(fixture.answer.value, '先初始化父类部分')
  }
})

test('explicit requests to be quizzed route to practice without intercepting knowledge questions', () => {
  const { isPracticeRequest } = load(readFileSync(new URL('../src/practice.ts', import.meta.url), 'utf8'))
  for (const text of ['问问我问题', '考考我', '根据我的笔记出一道题', '请给我出三道题']) assert.equal(isPracticeRequest(text), true)
  for (const text of ['Java 继承有什么特点？', '异常有哪些常见问题？', '这道题为什么出错？']) assert.equal(isPracticeRequest(text), false)
})

test('retry preserves the previous attempt and duplicate clicks make only one request', async () => {
  const fixture = practiceFixture()
  const original = { ...exercise, studentAnswer: '先初始化子类', grade: { score: 0, checks: [] } }
  fixture.active.value = original; fixture.records.value = [original]; fixture.answer.value = original.studentAnswer
  const pending = fixture.retry(original)
  await fixture.retry(original)
  assert.equal(fixture.requests.length, 1)
  assert.equal(fixture.requests[0].path, '/practice/quiz/retry')
  fixture.requests[0].resolve({ ...exercise, id: 'retry', reviewOf: 'quiz', createdAt: '2026-10-22T11:00:00' })
  await pending
  assert.equal(fixture.active.value.id, 'retry')
  assert.equal(fixture.active.value.grade, undefined)
  assert.equal(fixture.answer.value, '')
  assert.equal(fixture.records.value.length, 2)
  assert.equal(fixture.records.value[1].grade.score, 0)
  assert.equal(fixture.overview.value.review.length, 1)
  assert.equal(fixture.overview.value.pending, 1)
})

test('failed retry keeps the graded answer available, and legacy or pending records cannot retry', async () => {
  const fixture = practiceFixture()
  const original = { ...exercise, studentAnswer: '原答案', grade: { score: 0, checks: [] } }
  fixture.active.value = original; fixture.answer.value = '原答案'
  const pending = fixture.retry(original)
  fixture.requests[0].reject(new Error('retry failed'))
  await pending
  assert.equal(fixture.active.value.id, 'quiz')
  assert.equal(fixture.answer.value, '原答案')
  assert.equal(fixture.error.value, 'retry failed')
  assert.equal(fixture.working.value, '')
  await fixture.retry({ ...original, qualityChecked: false })
  await fixture.retry(exercise)
  assert.equal(fixture.requests.length, 1)
})

test('retry invalidates an older history load and leaving discards a late retry response', async () => {
  const fixture = practiceFixture()
  const original = { ...exercise, grade: { score: 0, checks: [] } }
  const history = fixture.loadHistory()
  const retry = fixture.retry(original)
  fixture.requests[1].resolve({ ...exercise, id: 'retry', reviewOf: 'quiz' })
  await retry
  fixture.requests[0].resolve([original]); await history
  assert.equal(fixture.records.value[0].id, 'retry')
  const next = fixture.retry(original)
  fixture.unmount()
  fixture.requests[2].resolve({ ...exercise, id: 'late' }); await next
  assert.equal(fixture.active.value.id, 'retry')
})

test('grading a retry clears its wrong-question filter and deletion recalculates the overview', async () => {
  const fixture = practiceFixture()
  const original = { ...exercise, grade: { score: 0, checks: [] }, gradedAt: '2026-10-22T10:01:00' }
  const repeat = { ...exercise, id: 'retry', reviewOf: 'quiz', createdAt: '2026-10-22T11:00:00' }
  fixture.records.value = [repeat, original]; fixture.active.value = repeat
  fixture.historyFilter.value = 'review'
  assert.equal(fixture.filteredRecords.value[0].id, 'retry')
  fixture.answer.value = '先初始化父类'
  const submit = fixture.submit()
  fixture.requests[0].resolve({ ...repeat, studentAnswer: '先初始化父类' })
  await settle()
  fixture.requests[1].resolve({ ...repeat, studentAnswer: '先初始化父类', grade: { score: 100, checks: [] }, gradedAt: '2026-10-22T11:01:00' })
  await submit
  assert.equal(fixture.filteredRecords.value.length, 0)
  assert.equal(fixture.overview.value.average, 50)
  assert.equal(fixture.overview.value.mastered.length, 1)
  const remove = fixture.remove(fixture.active.value)
  fixture.requests[2].resolve(undefined); await remove
  assert.equal(fixture.overview.value.review.length, 1)
  assert.equal(fixture.overview.value.mastered.length, 0)
  assert.equal(fixture.active.value, null)
})

test('draft autosave debounces typing and updates history in place without grading it', async () => {
  const fixture = practiceFixture()
  const newer = { ...exercise, id: 'newer', createdAt: '2026-10-23T10:00:00' }
  fixture.records.value = [newer, exercise]; fixture.activate(exercise)
  fixture.answer.value = '先初始化'; fixture.answer.value = '先初始化父类'
  assert.equal(fixture.requests.length, 0); assert.equal(fixture.timers.size, 1)
  const saving = fixture.flushDraft()
  assert.deepEqual(JSON.parse(fixture.requests[0].options.body), { answer: '先初始化父类', expectedAnswer: '' })
  assert.equal(fixture.requests[0].options.keepalive, true)
  fixture.requests[0].resolve({ ...exercise, studentAnswer: '先初始化父类' }); await saving
  assert.equal(fixture.records.value[0].id, 'newer'); assert.equal(fixture.records.value[1].studentAnswer, '先初始化父类')
  assert.equal(fixture.active.value.grade, undefined); assert.equal(fixture.overview.value.graded, 0)
  assert.equal(fixture.draftState.value, 'saved')
})

test('typing during a save queues the latest text using the newly saved expected answer', async () => {
  const fixture = practiceFixture(); fixture.activate(exercise)
  fixture.answer.value = '第一版'; const first = fixture.flushDraft()
  fixture.answer.value = '第二版'; const second = fixture.flushDraft()
  assert.equal(fixture.requests.length, 1)
  fixture.requests[0].resolve({ ...exercise, studentAnswer: '第一版' }); await settle()
  assert.equal(fixture.requests.length, 2)
  assert.deepEqual(JSON.parse(fixture.requests[1].options.body), { answer: '第二版', expectedAnswer: '第一版' })
  fixture.requests[1].resolve({ ...exercise, studentAnswer: '第二版' }); await Promise.all([first, second])
  assert.equal(fixture.answer.value, '第二版'); assert.equal(fixture.savedAnswer.value, '第二版')
})

test('a failed save blocks switching exercises and keeps the current input available for retry', async () => {
  const fixture = practiceFixture(); fixture.activate(exercise); fixture.answer.value = '不能丢失的草稿'
  const changing = fixture.open({ ...exercise, id: 'other' })
  fixture.requests[0].reject(new Error('offline')); await changing
  assert.equal(fixture.active.value.id, 'quiz'); assert.equal(fixture.answer.value, '不能丢失的草稿')
  assert.equal(fixture.draftState.value, 'error'); assert.match(fixture.draftError.value, /offline/)
  assert.equal(fixture.working.value, '')
})

test('clearing a draft saves an empty answer with the exact previous text', async () => {
  const fixture = practiceFixture(); fixture.activate({ ...exercise, studentAnswer: '  old draft  ' })
  fixture.answer.value = ''; const saving = fixture.flushDraft()
  assert.deepEqual(JSON.parse(fixture.requests[0].options.body), { answer: '', expectedAnswer: '  old draft  ' })
  fixture.requests[0].resolve({ ...exercise, studentAnswer: '' }); await saving
  assert.equal(fixture.savedAnswer.value, ''); assert.equal(fixture.draftState.value, 'saved')
})

test('the refresh fallback restores recent typing for its owner and never another account', () => {
  const first = practiceFixture(); first.activate(exercise); first.answer.value = '刷新前的输入'
  const refreshed = practiceFixture({ storage: first.storage }); refreshed.activate(exercise)
  assert.equal(refreshed.answer.value, '刷新前的输入'); assert.equal(refreshed.draftState.value, 'dirty')
  const other = practiceFixture({ storage: first.storage, userId: 'another', token: 'other-token' }); other.activate(exercise)
  assert.equal(other.answer.value, ''); assert.equal(other.draftState.value, 'saved')
})

test('a changed remote draft requires an explicit conflict choice before replacing it', async () => {
  const first = practiceFixture(); first.activate(exercise); first.answer.value = '本地输入'
  const fixture = practiceFixture({ storage: first.storage }); fixture.activate({ ...exercise, studentAnswer: '其他页面的新输入' })
  assert.equal(fixture.answer.value, '本地输入'); assert.equal(fixture.draftState.value, 'conflict')
  assert.equal(await fixture.flushDraft(), false); assert.equal(fixture.requests.length, 0)
  const resolving = fixture.resolveDraft(true)
  fixture.requests[0].resolve({ ...exercise, studentAnswer: '其他页面的新输入' }); await settle()
  assert.deepEqual(JSON.parse(fixture.requests[1].options.body), { answer: '本地输入', expectedAnswer: '其他页面的新输入' })
  fixture.requests[1].resolve({ ...exercise, studentAnswer: '本地输入' }); await resolving
  assert.equal(fixture.savedAnswer.value, '本地输入'); assert.equal(fixture.draftState.value, 'saved')
})

test('queued saves stop after the login token changes and unmount cannot use the new token', async () => {
  const fixture = practiceFixture(); fixture.activate(exercise); fixture.answer.value = '第一版'
  const saving = fixture.flushDraft(); fixture.answer.value = '第二版'; fixture.setToken('new-login')
  fixture.requests[0].resolve({ ...exercise, studentAnswer: '第一版' }); await saving
  fixture.unmount()
  assert.equal(fixture.requests.length, 1); assert.equal(fixture.answer.value, '第二版')
})

test('autosave invalidates an older history response and cannot restore state after unmount', async () => {
  const fixture = practiceFixture(); fixture.activate(exercise); fixture.records.value = [exercise]
  const history = fixture.loadHistory(); fixture.answer.value = '草稿'
  const saving = fixture.flushDraft(); fixture.requests[1].resolve({ ...exercise, studentAnswer: '草稿' }); await saving
  fixture.requests[0].resolve([exercise]); await history
  assert.equal(fixture.records.value[0].studentAnswer, '草稿')
  fixture.answer.value = '离开前输入'; const late = fixture.flushDraft(); fixture.unmount()
  fixture.requests[2].resolve({ ...exercise, studentAnswer: '离开前输入' }); await late
  assert.equal(fixture.savedAnswer.value, '草稿')
})

test('a reused pending retry restores its saved answer instead of clearing it', async () => {
  const fixture = practiceFixture(), original = { ...exercise, grade: { score: 0, checks: [] } }
  const retrying = fixture.retry(original)
  fixture.requests[0].resolve({ ...exercise, id: 'retry', reviewOf: 'quiz', studentAnswer: '原先的复练草稿' }); await retrying
  assert.equal(fixture.answer.value, '原先的复练草稿'); assert.equal(fixture.savedAnswer.value, '原先的复练草稿')
})

test('a grading conflict preserves the local answer and asks before overwriting newer saved text', async () => {
  const fixture = practiceFixture(); fixture.activate({ ...exercise, studentAnswer: '我的作答' })
  const grading = fixture.submit(); await settle()
  const conflict = new Error('draft changed'); conflict.status = 409
  fixture.requests[0].reject(conflict); await settle()
  fixture.requests[1].resolve({ ...exercise, studentAnswer: '新的远端作答' }); await grading
  assert.equal(fixture.answer.value, '我的作答'); assert.equal(fixture.draftState.value, 'conflict')
  assert.equal(fixture.active.value.grade, undefined)
})
