import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminPage } from './AdminPage'

const auth = vi.hoisted(() => ({ can: (_: string): boolean => true }))
vi.mock('@/features/auth/auth', () => ({ useAuth: () => ({ me: { id: 1 }, can: auth.can }) }))

function withQuery(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  )
}

const CLOSING = {
  businessDate: '2027-10-15',
  closedAt: '2027-10-15T23:00:00+09:00',
  paymentCount: 2,
  paymentTotal: 200000,
  correctionTotal: 0,
  unmatchedTotal: 0,
  accountsJudged: 9,
  newNotices: 0,
  accelerations: 0,
  mismatches: 0,
  nextBusinessDate: '2027-10-16',
}

function mockServer(judgeFailures: number) {
  const calls: { method: string; url: string }[] = []
  let resolved = false
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      calls.push({ method, url })
      const json = (data: unknown) =>
        new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url === '/api/operation/business-date') return json({ businessDate: '2027-10-16', lastClosedAt: null })
      if (url === '/api/operation/closings') return json([{ ...CLOSING, judgeFailures }])
      if (url === '/api/operation/closings/2027-10-15/failures') {
        return json([
          {
            id: 1,
            businessDate: '2027-10-15',
            accountId: 42,
            accountNo: 'N2027000042',
            kind: 'SYSTEM',
            code: 'IllegalStateException',
            message: '재생 입력 오류: 이벤트 EV-7(PAYMENT)의 날짜 2027-08-20이 실행(양수)일 2027-09-01보다 이릅니다',
            recordedAt: '2027-10-15T23:00:01+09:00',
            resolved,
            resolvedAt: resolved ? '2027-10-16T09:10:00+09:00' : null,
            resolvedBy: resolved ? 'admin' : null,
          },
        ])
      }
      if (url === '/api/operation/accounts/42/rejudge' && method === 'POST') {
        resolved = true
        return json({ accountId: 42, accountNo: 'N2027000042', status: 'ACCELERATED' })
      }
      return json([])
    }),
  )
  return calls
}

afterEach(() => {
  vi.unstubAllGlobals()
  auth.can = () => true
})

describe('일마감 판정 실패 (R76)', () => {
  it('실패 건수를 누르면 실패 계약 목록이 열리고, 재판정하면 해결로 바뀐다', async () => {
    const calls = mockServer(1)
    render(withQuery(<AdminPage />))

    await userEvent.click(await screen.findByRole('button', { name: '2027-10-15 판정 실패 1건 보기' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('N2027000042')).toBeInTheDocument()
    expect(within(dialog).getByText('IllegalStateException')).toBeInTheDocument()
    expect(within(dialog).getByText('미해결')).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: '재판정' }))
    expect(await within(dialog).findByText(/재판정 완료/)).toBeInTheDocument()
    expect(calls).toContainEqual({ method: 'POST', url: '/api/operation/accounts/42/rejudge' })
    expect(await within(dialog).findByText('해결')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: '재판정' })).not.toBeInTheDocument()
  })

  it('실패가 없으면 건수는 누를 수 없고, 운영 관리 권한이 없으면 재판정 버튼이 없다', async () => {
    mockServer(0)
    const { unmount } = render(withQuery(<AdminPage />))
    await screen.findByText('2027-10-15')
    expect(screen.queryByRole('button', { name: /판정 실패 .*건 보기/ })).not.toBeInTheDocument()
    unmount()

    auth.can = (p: string) => p !== 'admin.manage'
    mockServer(1)
    render(withQuery(<AdminPage />))
    await userEvent.click(await screen.findByRole('button', { name: '2027-10-15 판정 실패 1건 보기' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('N2027000042')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: '재판정' })).not.toBeInTheDocument()
  })
})
