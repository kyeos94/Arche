import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { pageWindow } from '@/components/Pagination'
import { BulkSmsDialog } from './bulk/BulkSmsDialog'
import { LoanListPage } from './LoanListPage'
import { activeCount, exportFilter, readSearch } from './loanFilter'

vi.mock('@/features/auth/auth', () => ({ useAuth: () => ({ me: { id: 1 }, can: () => true }) }))

function Location() {
  const l = useLocation()
  return <output data-testid="location">{l.search}</output>
}

function withProviders(ui: React.ReactNode, url = '/loans') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route
            path="/loans"
            element={
              <>
                {ui}
                <Location />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const ROW = {
  id: 1,
  accountNo: 'L00000001',
  customerName: '홍길동',
  productCode: 'P1',
  status: 'NORMAL',
  principalBalance: 900,
  payoffAmount: 950,
  delinquencyDays: 0,
  nextDueDate: '2026-12-10',
  assignedStaffId: 5,
  assignedStaffName: '박채권',
  initialPrincipal: 1000,
  disbursedOn: '2026-01-10',
}

function mockServer() {
  const calls: { method: string; url: string; body?: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, url, body })
      const json = (data: unknown) =>
        new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url.startsWith('/api/loans?') || url === '/api/loans') {
        return json({ content: [ROW], page: 0, size: 50, totalElements: 120, totalPages: 3 })
      }
      if (url === '/api/loans/status-counts') return json({ NORMAL: 120 })
      if (url === '/api/products') return json([{ code: 'P1', name: '개인신용 분할' }])
      if (url === '/api/loans/assignees') return json([{ id: 5, name: '박채권', roleLabel: '채권담당', team: '채권1팀' }])
      if (url.startsWith('/api/message-templates')) {
        return json([
          { id: 3, code: 'DUE', name: '납입 안내', channel: 'SMS', purpose: 'NOTICE', title: null, body: '{{고객명}}님', active: true, placeholders: [] },
          { id: 4, code: 'FAXT', name: '팩스', channel: 'FAX', purpose: 'NOTICE', title: 't', body: 'x', active: true, placeholders: [] },
        ])
      }
      if (url === '/api/dispatches/bulk-sms') {
        return json({
          sent: 1,
          scheduled: 0,
          blocked: 1,
          skipped: 0,
          items: [
            { accountId: 1, accountNo: 'L1', result: 'SENT', reason: null },
            { accountId: 2, accountNo: 'L2', result: 'BLOCKED', reason: '7일 7회 초과' },
          ],
        })
      }
      return json([])
    }),
  )
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('계약 목록 검색 조건 (ADR 0011)', () => {
  it('URL에서 검색 조건을 읽고, 예전 q는 서버가 자동 판단하게 보낸다', () => {
    const s = readSearch(new URLSearchParams('status=DELINQUENT&principalMin=100&size=999&q=L0001'))
    expect(s).toMatchObject({ status: 'DELINQUENT', principalMin: '100', size: 50, page: 0, q: 'L0001' })
    expect(s.keyword).toBeUndefined()
    expect(activeCount({ principalMin: '1', principalMax: '2', keyword: 'a', unassigned: 'true' })).toBe(3)
    expect(exportFilter('', { assignedStaffId: '5', keyword: '홍', keywordType: 'NAME' })).toMatchObject({
      status: null,
      assignedStaffId: 5,
      unassigned: false,
      keywordType: 'NAME',
      keyword: '홍',
    })
  })

  it('쪽 번호는 처음·끝과 현재 쪽 주변만 보여 준다', () => {
    expect(pageWindow(0, 3)).toEqual([0, 1, 2])
    expect(pageWindow(10, 30)).toEqual([0, null, 8, 9, 10, 11, 12, null, 29])
    expect(pageWindow(0, 0)).toEqual([])
  })

  it('상세 조건을 적용하면 URL과 요청에 반영되고, 머리글을 누르면 서버 정렬, 쪽 건수를 바꾸면 첫 쪽으로', async () => {
    const calls = mockServer()
    render(withProviders(<LoanListPage />, '/loans?page=2'))
    expect(await screen.findByText('L00000001')).toBeInTheDocument()
    expect(screen.getByText('박채권')).toBeInTheDocument()

    const form = screen.getByRole('form', { name: '계약 상세 검색' })
    await userEvent.click(within(form).getByRole('button', { name: /상세 조건 펼치기/ }))
    await userEvent.selectOptions(within(form).getByLabelText('검색 항목'), 'NAME')
    await userEvent.type(within(form).getByLabelText('검색어'), '홍')
    await userEvent.selectOptions(await within(form).findByLabelText('담당자'), 'NONE')
    await userEvent.type(within(form).getByLabelText('원금 잔액 (원) 부터'), '1000000')
    await userEvent.click(within(form).getByRole('button', { name: '검색' }))
    const loc = () => new URLSearchParams(screen.getByTestId('location').textContent ?? '')
    expect(loc().get('keywordType')).toBe('NAME')
    expect(loc().get('keyword')).toBe('홍')
    expect(loc().get('unassigned')).toBe('true')
    expect(loc().get('principalMin')).toBe('1000000')
    expect(loc().get('page')).toBeNull() // 조건이 바뀌면 첫 쪽
    expect(await screen.findByText('상세 조건 3개 적용 중')).toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('keywordType=NAME') && c.url.includes('unassigned=true'))).toBe(true)

    await userEvent.click(screen.getByRole('button', { name: /원금 잔액/ }))
    expect(loc().get('sort')).toBe('principalBalance')
    expect(loc().get('dir')).toBe('asc')
    await userEvent.click(screen.getByRole('button', { name: /원금 잔액/ }))
    expect(loc().get('dir')).toBe('desc')

    await userEvent.click(screen.getByRole('button', { name: '2쪽' }))
    expect(loc().get('page')).toBe('1')
    await userEvent.selectOptions(screen.getByLabelText('한 쪽 건수'), '100')
    expect(loc().get('size')).toBe('100')
    expect(loc().get('page')).toBeNull()
  })
})

describe('문자 일괄 발송 (ADR 0011)', () => {
  it('문자 템플릿만 고를 수 있고, 계약별 결과(발송·차단)를 보여 준다', async () => {
    const calls = mockServer()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onDone = vi.fn()
    const selected = [
      { ...ROW, id: 1, accountNo: 'L1' },
      { ...ROW, id: 2, accountNo: 'L2' },
    ]
    render(withProviders(<BulkSmsDialog selected={selected} onClose={() => {}} onDone={onDone} />))
    const dialog = await screen.findByRole('dialog')
    const select = within(dialog).getByLabelText('템플릿')
    await within(dialog).findByRole('option', { name: '납입 안내' })
    expect(within(dialog).queryByRole('option', { name: '팩스' })).not.toBeInTheDocument()
    const send = within(dialog).getByRole('button', { name: '2건 발송' })
    expect(send).toBeDisabled()
    await userEvent.selectOptions(select, '3')
    expect(within(dialog).getByText('{{고객명}}님')).toBeInTheDocument()
    await userEvent.click(send)
    expect(await within(dialog).findByText(/발송 1건 · 예약 0건 · 차단 1건/)).toBeInTheDocument()
    expect(within(dialog).getByText('7일 7회 초과')).toBeInTheDocument()
    expect(calls).toContainEqual({
      method: 'POST',
      url: '/api/dispatches/bulk-sms',
      body: { accountIds: [1, 2], templateId: 3, body: null },
    })
    await userEvent.click(within(dialog).getByRole('button', { name: '닫고 선택 비우기' }))
    expect(onDone).toHaveBeenCalled()
  })
})
