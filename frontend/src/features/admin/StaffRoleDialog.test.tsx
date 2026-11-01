import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { StaffRow } from '@/api/types'
import { StaffRoleDialog } from './StaffRoleDialog'

function withQuery(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

const OPTIONS = {
  roles: [
    { code: 'COLLECTOR', label: '채권담당' },
    { code: 'COLLECTION_LEAD', label: '채권팀장' },
  ],
  teams: [
    { id: 2, name: '채권1팀' },
    { id: 3, name: '채권2팀' },
  ],
}

const STAFF: StaffRow = {
  id: 5,
  loginId: 'col1',
  name: '박채권',
  role: 'COLLECTOR',
  roleLabel: '채권담당',
  teamId: 2,
  locked: false,
  failedAttempts: 0,
  enabled: true,
  resignedAt: null,
}

afterEach(() => vi.unstubAllGlobals())

describe('직원 역할 변경 (R71)', () => {
  it('막힌 사유가 있으면 버튼을 쓸 수 없다', () => {
    render(withQuery(<StaffRoleDialog staff={STAFF} options={OPTIONS} disabledReason="자기 역할은 바꿀 수 없습니다" />))
    expect(screen.getByRole('button', { name: '역할 변경' })).toBeDisabled()
  })

  it('역할·팀·사유를 보내고, 담당 계약이 남는다는 안내를 보여 준다', async () => {
    const fetchMock = vi.fn(
      async () => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(withQuery(<StaffRoleDialog staff={STAFF} options={OPTIONS} />))
    await userEvent.click(screen.getByRole('button', { name: '역할 변경' }))
    const dialog = screen.getByRole('dialog')
    const submit = within(dialog).getByRole('button', { name: '변경' })
    expect(submit).toBeDisabled() // 그대로면 보낼 수 없음

    await userEvent.selectOptions(within(dialog).getByLabelText('역할'), 'COLLECTION_LEAD')
    await userEvent.selectOptions(within(dialog).getByLabelText('팀'), '3')
    expect(within(dialog).getByText(/담당 계약은 그대로 남습니다/)).toBeInTheDocument()
    await userEvent.type(within(dialog).getByLabelText('변경 사유'), '팀장 발령')
    await userEvent.click(submit)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/admin/staff/5/role')
    expect(JSON.parse(String(init.body))).toEqual({ role: 'COLLECTION_LEAD', teamId: 3, reason: '팀장 발령' })
  })
})
