import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

const context = vm.createContext({ exports: {} })
vm.runInContext(ts.transpileModule(readFileSync(new URL('../src/practice-drafts.ts', import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText, context)
const { createDraftStore } = context.exports
const fixture = () => { const values = new Map(); return { values, storage: { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) } } }

test('draft cache is bounded and scopes active exercises and text to the account', () => {
  const { storage } = fixture(), owner = createDraftStore('owner', storage), other = createDraftStore('other', storage)
  for (let i = 0; i < 12; i++) owner.put('quiz-' + i, { answer: 'text ' + i, expectedAnswer: '' })
  owner.remember('quiz-11')
  assert.equal(owner.get('quiz-0'), undefined); assert.equal(owner.get('quiz-11').answer, 'text 11')
  assert.equal(owner.last(), 'quiz-11'); assert.equal(other.last(), undefined); assert.equal(other.get('quiz-11'), undefined)
  owner.forget('quiz-11'); assert.equal(owner.last(), undefined); assert.equal(owner.get('quiz-11'), undefined)
})

test('invalid cached data and unavailable browser storage do not break practice', () => {
  const { values, storage } = fixture(); values.set('zhiye-practice-drafts:owner', '{broken')
  const owner = createDraftStore('owner', storage); assert.equal(owner.last(), undefined)
  values.set('zhiye-practice-drafts:owner', JSON.stringify({ drafts: { invalid: { answer: 'a'.repeat(4001), expectedAnswer: '' } } }))
  assert.equal(owner.get('invalid'), undefined)
  const disabled = createDraftStore('owner', { getItem() { throw new Error('disabled') }, setItem() { throw new Error('disabled') } })
  assert.doesNotThrow(() => disabled.put('quiz', { answer: 'draft', expectedAnswer: '' }))
  assert.equal(disabled.get('quiz'), undefined)
})
