import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { StaffAdminPage } from './StaffAdminPage'

vi.mock('@/features/auth/auth', () => ({ useAuth: () => ({ me: { id: 1 }, can: () => true }) }))

function withQuery(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

const STAFF = [
  { id: 1, loginId: 'admin', name: '관리자', role: 'SYSTEM_ADMIN', roleLabel: '시스템관리자', teamId: 9 },
  { id: 5, loginId: 'col1', name: '박채권', role: 'COLLECTOR', roleLabel: '채권담당', teamId: 2 },
  { id: 6, loginId: 'col2', name: '김후임', role: 'COLLECTOR', roleLabel: '채권담당', teamId: 2 },
  { id: 7, loginId: 'old', name: '이퇴사', role: 'TELLER', roleLabel: '출납', teamId: 4, enabled: false, resignedAt: '2026-10-01T09:00:00+09:00' },
].map((s) => ({ locked: false, failedAttempts: 0, enabled: true, resignedAt: null, ...s }))

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
      if (url === '/api/admin/staff' && method === 'GET') return json(STAFF)
      if (url === '/api/admin/staff/role-options') {
        return json({
          roles: [{ code: 'COLLECTOR', label: '채권담당' }],
          teams: [
            { id: 2, name: '채권1팀' },
            { id: 4, name: '출납팀' },
            { id: 9, name: '본부' },
          ],
        })
      }
      if (url === '/api/admin/staff/5/workload') {
        return json({
          staffId: 5,
          name: '박채권',
          roleLabel: '채권담당',
          loans: 12,
          alerts: 2,
          applications: 0,
          loanSuccessors: [{ id: 6, name: '김후임', roleLabel: '채권담당', team: '채권1팀' }],
          applicationSuccessors: [],
        })
      }
      if (url === '/api/admin/staff/5/resignation') {
        return json({ staffId: 5, loans: 12, alerts: 2, applications: 0, handover: '계약 12건·알림 2건 → 김후임' })
      }
      if (url === '/api/admin/staff' && method === 'POST') {
        return json({ id: 8, loginId: body.loginId, temporaryPassword: 'Ab3$xyzPQRst' })
      }
      return json([])
    }),
  )
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('직원 관리 (R75)', () => {
  it('재직 직원만 먼저 보이고, 자기 자신은 퇴사 처리할 수 없다', async () => {
    mockServer()
    render(withQuery(<StaffAdminPage />))
    expect(await screen.findByText('박채권')).toBeInTheDocument()
    expect(screen.queryByText('이퇴사')).not.toBeInTheDocument()
    const adminRow = screen.getByText('관리자').closest('tr')!
    expect(within(adminRow).getByRole('button', { name: '퇴사 처리' })).toBeDisabled()

    await userEvent.selectOptions(screen.getByLabelText('재직 구분'), 'RESIGNED')
    const row = (await screen.findByText('이퇴사')).closest('tr')!
    expect(within(row).getByText(/^퇴사 /)).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: '복직' })).toBeEnabled()
  })

  it('퇴사 처리: 담당 건이 있으면 후임을 골라야 하고, 고르면 이관 결과를 보여 준다', async () => {
    const calls = mockServer()
    render(withQuery(<StaffAdminPage />))
    const row = (await screen.findByText('박채권')).closest('tr')!
    await userEvent.click(within(row).getByRole('button', { name: '퇴사 처리' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/완제 전 담당 계약/)).toHaveTextContent('12건')
    await userEvent.type(within(dialog).getByLabelText('퇴사 사유'), '개인 사정')
    const submit = within(dialog).getByRole('button', { name: '퇴사 처리' })
    expect(submit).toBeDisabled()
    await userEvent.selectOptions(within(dialog).getByLabelText('계약 후임'), '6')
    await userEvent.click(submit)
    expect(await within(dialog).findByText(/계약 12건·알림 2건 → 김후임/)).toBeInTheDocument()
    expect(calls).toContainEqual({
      method: 'POST',
      url: '/api/admin/staff/5/resignation',
      body: { reason: '개인 사정', loanSuccessorId: 6, applicationSuccessorId: null },
    })
  })

  it('직원 등록 후 임시 비밀번호를 한 번 보여 준다', async () => {
    mockServer()
    render(withQuery(<StaffAdminPage />))
    await userEvent.click(await screen.findByRole('button', { name: '직원 등록' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('로그인 ID'), 'Bad')
    expect(within(dialog).getByText('로그인 ID 형식을 확인하세요')).toBeInTheDocument()
    await userEvent.clear(within(dialog).getByLabelText('로그인 ID'))
    await userEvent.type(within(dialog).getByLabelText('로그인 ID'), 'new.staff')
    await userEvent.type(within(dialog).getByLabelText('이름'), '신입')
    await userEvent.click(within(dialog).getByRole('button', { name: '등록' }))
    expect(await within(dialog).findByTestId('temporary-password')).toHaveTextContent('Ab3$xyzPQRst')
  })
})
