import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

const context = vm.createContext({ exports: {} })
vm.runInContext(ts.transpileModule(readFileSync(new URL('../src/practice.ts', import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText, context)
const { practiceOverview, filterPractice } = context.exports
const row = (id, score, extra = {}) => ({
  id, question: id, documents: [], citations: [], qualityChecked: true,
  createdAt: '2026-10-22T10:00:00',
  ...(score === null ? {} : { grade: { score, checks: [{ level: score === 100 ? 'CORRECT' : 'MISSING' }] } }),
  ...extra,
})

test('empty and legacy records do not fabricate scores or wrong questions', () => {
  const empty = practiceOverview([])
  assert.equal(empty.average, null); assert.equal(empty.review.length, 0)
  const result = practiceOverview([row('old', 100, { qualityChecked: false }), row('missing', 0, { qualityChecked: undefined }), row('pending', null)])
  assert.equal(result.total, 3); assert.equal(result.graded, 0); assert.equal(result.pending, 1)
  assert.equal(result.average, null); assert.equal(result.review.length, 0); assert.equal(result.mastered.length, 0)
})

test('the latest grading decides review state regardless of history array order', () => {
  const wrong = row('root', 0, { gradedAt: '2026-10-22T10:01:00' })
  const right = row('second', 100, { reviewOf: 'root', createdAt: '2026-10-22T11:00:00', gradedAt: '2026-10-22T11:01:00' })
  const result = practiceOverview([wrong, right])
  assert.equal(result.review.length, 0); assert.equal(result.mastered.length, 1)
  assert.equal(result.mastered[0].id, 'second'); assert.equal(result.graded, 2); assert.equal(result.average, 50)
  const relapse = row('third', 50, { reviewOf: 'root', createdAt: '2026-10-22T12:00:00', gradedAt: '2026-10-22T12:01:00' })
  assert.equal(practiceOverview([wrong, relapse, right]).review[0].id, 'third')
})

test('a pending retry continues the same wrong question without counting it twice', () => {
  const records = [row('pending', null, { reviewOf: 'root', createdAt: '2026-10-22T12:00:00' }), row('second', 50, { reviewOf: 'root', gradedAt: '2026-10-22T11:01:00' }), row('root', 0)]
  const result = practiceOverview(records)
  assert.equal(result.review.length, 1); assert.equal(result.review[0].id, 'pending')
  assert.equal(filterPractice(records, 'review').length, 1)
  assert.equal(filterPractice(records, 'pending')[0].id, 'pending')
  assert.equal(filterPractice(records, 'all').length, 3)
})

test('review families still work when the original record falls outside the recent history', () => {
  const result = practiceOverview([row('retry-a', 0, { reviewOf: 'absent-root' }), row('other', 100), row('retry-b', 100, { reviewOf: 'absent-root', gradedAt: '2026-10-22T12:00:00' })])
  assert.equal(result.review.length, 0); assert.equal(result.mastered.length, 2)
})
