import type { Exercise } from './api'

export type PracticeFilter = 'all' | 'pending' | 'review' | 'mastered'

export function needsReview(row: Exercise): boolean {
  return row.qualityChecked === true && !!row.grade &&
    (row.grade.score < 100 || row.grade.checks.some(check => check.level !== 'CORRECT'))
}

function latest(rows: Exercise[], graded = false): Exercise | undefined {
  return rows.reduce<Exercise | undefined>((current, row) => {
    const time = graded ? row.gradedAt || row.createdAt : row.createdAt
    const currentTime = current && (graded ? current.gradedAt || current.createdAt : current.createdAt)
    return !current || (time || '') > (currentTime || '') ||
      (time === currentTime && row.id > current.id) ? row : current
  }, undefined)
}

export function practiceOverview(records: Exercise[]) {
  const checked = records.filter(row => row.qualityChecked === true)
  const graded = checked.filter(row => row.grade)
  const pending = checked.filter(row => !row.grade)
  const groups = new Map<string, Exercise[]>()
  for (const row of checked) {
    const key = row.reviewOf || row.id
    const family = groups.get(key) || []
    family.push(row); groups.set(key, family)
  }
  const review: Exercise[] = [], mastered: Exercise[] = []
  for (const family of groups.values()) {
    const completed = latest(family.filter(row => row.grade), true)
    if (!completed) continue
    if (needsReview(completed)) review.push(latest(family.filter(row => !row.grade)) || completed)
    else mastered.push(completed)
  }
  const byDate = (a: Exercise, b: Exercise) => (b.createdAt || '').localeCompare(a.createdAt || '') || b.id.localeCompare(a.id)
  return {
    total: records.length, graded: graded.length, pending: pending.length,
    average: graded.length ? Math.round(graded.reduce((sum, row) => sum + row.grade!.score, 0) / graded.length) : null,
    review: review.sort(byDate), mastered: mastered.sort(byDate),
  }
}

export function filterPractice(records: Exercise[], filter: PracticeFilter): Exercise[] {
  if (filter === 'pending') return records.filter(row => row.qualityChecked === true && !row.grade)
  if (filter === 'review' || filter === 'mastered') return practiceOverview(records)[filter]
  return records
}

export function isPracticeRequest(text: string): boolean {
  const value = text.trim().replace(/[，。！!？?\s]/g, '')
  return /^(?:请)?(?:你)?(?:问问我(?:问题)?|考考我|考我一下)$/.test(value)
    || /^(?:请)?(?:你)?(?:根据|基于).{0,50}(?:出.{0,8}题|考考我|考我)$/.test(value)
    || /^(?:请)?(?:给我|帮我)出.{0,12}题$/.test(value)
}
