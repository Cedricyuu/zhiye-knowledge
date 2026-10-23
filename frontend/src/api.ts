export interface User { id: string; username: string; displayName: string }
export interface Health { status: string; version: string; aiEnabled: boolean; answerMode: string; database: string; retrieval: string; modelName?: string; localModel?: boolean; embeddingModel?: string }
export interface Document {
  id: string; name: string; fileType: string; sizeBytes: number;
  pageCount: number; chunkCount: number; indexedChunks: number; createdAt: string;
}
export interface Part { id: string; ordinal: number; pageNumber: number; startOffset: number; content: string }
export interface DocumentDetail { document: Document; parts: Part[] }
export interface Citation {
  index: number; chunkId: string; documentId: string; documentName: string; fileType: string;
  ordinal: number; pageNumber: number; startOffset: number; content: string; score: number;
  semanticScore?: number; keywordScore?: number; supportingQuotes?: string[];
}
export interface Answer {
  id: string; question: string; answer: string; answerMode: string; citations: Citation[];
  elapsedMs: number; feedback?: number | null; createdAt: string;
}
export interface PracticeCheck { criterion: number; level: 'CORRECT' | 'PARTIAL' | 'MISSING' | 'INCORRECT'; feedback: string; studentQuote: string }
export interface Exercise {
  id: string; question: string; documents: string[]; studentAnswer?: string;
  grade?: { score: number; summary: string; checks: PracticeCheck[] };
  referenceAnswer?: { point: string; source: number; quote: string }[];
  citations: Citation[]; createdAt: string; gradedAt?: string; qualityChecked: boolean;
  reviewOf?: string;
}
export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message) }
}

const TOKEN_KEY = 'zhiye-session'
export const session = {
  token: () => sessionStorage.getItem(TOKEN_KEY),
  save: (token: string) => sessionStorage.setItem(TOKEN_KEY, token),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
}

export async function api<T>(path: string, options: RequestInit = {}, publicRequest = false): Promise<T> {
  const headers = new Headers(options.headers)
  const token = session.token()
  if (token && !publicRequest) headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), path === '/practice' && options.method === 'POST' ? 320_000 : 120_000)
  let response: Response
  try {
    response = await fetch(`/api${path}`, { ...options, headers, signal: controller.signal })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw new ApiError('请求超时，请刷新记录确认结果后再重试。', 0)
    }
    throw new ApiError('暂时无法连接服务，请检查服务是否正在运行。', 0)
  } finally { clearTimeout(timeout) }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    // A response from an old session must not revoke a newer login.
    if (response.status === 401 && !publicRequest && session.token() === token) {
      session.clear()
      window.dispatchEvent(new Event('session-expired'))
    }
    throw new ApiError(data.message || `请求失败（${response.status}）`, response.status)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
