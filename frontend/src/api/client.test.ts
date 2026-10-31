import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, qs, setUnauthorizedListener } from './client'

function mockFetch(status: number, body?: unknown) {
  const fn = vi.fn(async (_url: string, _init?: RequestInit) => {
    return new Response(body === undefined ? null : JSON.stringify(body), { status })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

afterEach(() => {
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  setUnauthorizedListener(null)
})

describe('api client', () => {
  it('변경 요청에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보낸다', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const fetchMock = mockFetch(200, { ok: true })
    await api.post('/api/payments', { amount: 1 })
    const init = fetchMock.mock.calls[0][1]!
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc-123')
    expect(init.credentials).toBe('include')
    expect(init.method).toBe('POST')
  })

  it('GET에는 CSRF 헤더를 붙이지 않는다', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const fetchMock = mockFetch(200, [])
    await api.get('/api/loans')
    expect((fetchMock.mock.calls[0][1]!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('오류 응답은 서버 code·message를 담은 ApiError', async () => {
    mockFetch(422, { code: 'CANCEL_AFTER_PAYOFF', message: '완제된 계약의 입금은 취소할 수 없습니다 (R31)' })
    const err = await api.post('/api/payment-corrections', {}).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe('CANCEL_AFTER_PAYOFF')
    expect((err as ApiError).status).toBe(422)
  })

  it('401이면 로그인 화면으로 보내는 리스너를 호출한다', async () => {
    const listener = vi.fn()
    setUnauthorizedListener(listener)
    mockFetch(401, { code: 'UNAUTHENTICATED', message: '로그인이 필요합니다' })
    await api.get('/api/loans').catch(() => undefined)
    expect(listener).toHaveBeenCalledOnce()
  })

  it('204는 본문 없이 성공', async () => {
    mockFetch(204)
    await expect(api.post('/api/auth/logout')).resolves.toBeUndefined()
  })

  it('쿼리스트링은 빈 값을 뺀다', () => {
    expect(qs({ status: '', q: 'L0001', page: 0, size: undefined })).toBe('?q=L0001&page=0')
    expect(qs({})).toBe('')
  })
})
