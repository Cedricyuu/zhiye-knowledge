export interface CachedDraft { answer: string; expectedAnswer: string; savingAnswer?: string }
interface DraftCache { activeId?: string; drafts: Record<string, CachedDraft> }

// A small per-account fallback survives refresh while the server remains the source of saved drafts.
export function createDraftStore(userId: string, storage: Pick<Storage, 'getItem' | 'setItem'> = sessionStorage) {
  const key = `zhiye-practice-drafts:${userId}`
  function read(): DraftCache {
    try {
      const data = JSON.parse(storage.getItem(key) || '{}')
      const drafts: Record<string, CachedDraft> = Object.create(null)
      if (data.drafts && typeof data.drafts === 'object') {
        for (const [id, value] of Object.entries(data.drafts).slice(-10)) {
          const row = value as CachedDraft
          if (id.length <= 36 && row && typeof row.answer === 'string' && row.answer.length <= 4000 &&
            typeof row.expectedAnswer === 'string' && row.expectedAnswer.length <= 4000 &&
            (row.savingAnswer === undefined || typeof row.savingAnswer === 'string' && row.savingAnswer.length <= 4000)) drafts[id] = row
        }
      }
      return { activeId: typeof data.activeId === 'string' && data.activeId.length <= 36 ? data.activeId : undefined, drafts }
    } catch { return { drafts: {} } }
  }
  function write(data: DraftCache) { try { storage.setItem(key, JSON.stringify(data)) } catch { /* Autosave still works when browser storage is unavailable. */ } }
  return {
    get: (id: string) => read().drafts[id],
    last: () => read().activeId,
    remember(id?: string) { const data = read(); data.activeId = id; write(data) },
    put(id: string, draft: CachedDraft) {
      const data = read(); delete data.drafts[id]; data.drafts[id] = draft
      data.drafts = Object.fromEntries(Object.entries(data.drafts).slice(-10)); write(data)
    },
    forget(id: string) { const data = read(); delete data.drafts[id]; if (data.activeId === id) delete data.activeId; write(data) },
    clear(id: string) { const data = read(); delete data.drafts[id]; write(data) },
  }
}
