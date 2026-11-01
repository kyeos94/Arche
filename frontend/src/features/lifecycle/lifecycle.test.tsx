import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { AdjustmentRequestDto } from '@/api/lifecycle'
import { allowedTypes } from './LifecycleTab'
import { RequestsTable, describeRequest } from './shared'

vi.mock('@/features/auth/auth', () => ({
  useAuth: () => ({ can: (p: string) => p === 'loan.adjust.approve', me: { loginId: 'head1' } }),
  Can: ({ children }: { children: ReactNode }) => children,
}))

const base: AdjustmentRequestDto = {
  id: 1,
  requestNo: 'AR00000001',
  type: 'ADJUSTMENT',
  accountId: 7,
  accountNo: 'L00001007',
  sourceAccountId: null,
  sourceAccountNo: null,
  setoffSource: null,
  principalDown: 0,
  normalDown: 0,
  lateDown: 1_000,
  costDelta: 50_000,
  preAcqDown: 0,
  amount: 0,
  targetEventId: null,
  reason: '송달료 누락',
  status: 'REQUESTED',
  requestedBy: 'lead1',
  requestedOn: '2026-11-01',
  decidedBy: null,
  decidedOn: null,
  appliedEventId: null,
  consumedOn: null,
}

function wrap(node: ReactNode) {
  return (
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>
  )
}

describe('생애주기 화면', () => {
  it('상태별로 요청할 수 있는 유형이 다르다 (R42~R44)', () => {
    expect(allowedTypes('ACCELERATED')).toEqual(['ADJUSTMENT', 'SETOFF', 'REVERT_SETOFF'])
    expect(allowedTypes('CHARGED_OFF')).toContain('REVERT_CHARGE_OFF')
    expect(allowedTypes('SOLD')).toEqual(['REVERT_SALE', 'REVERT_SETOFF', 'REVERT_PAYOFF'])
    expect(allowedTypes('PAID_OFF')).toEqual(['REVERT_SETOFF', 'REVERT_PAYOFF'])
  })

  it('요청 내용을 한 줄로 요약한다', () => {
    expect(describeRequest(base)).toBe('가산이자 -1,000원, 비용 +50,000원')
    expect(
      describeRequest({ ...base, type: 'SETOFF', sourceAccountNo: 'L1', setoffSource: 'REFUND', amount: 50_000 }),
    ).toBe('L1의 반환 대기 50,000원 → 이 계약')
  })

  it('승인 권한자는 남의 요청만 승인할 수 있다 (작성자 ≠ 승인자)', () => {
    render(wrap(<RequestsTable rows={[base, { ...base, id: 2, requestNo: 'AR00000002', requestedBy: 'head1' }]} showAccount />))
    expect(screen.getAllByRole('button', { name: '승인' })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: 'L00001007' })[0]).toHaveAttribute('href', '/loans/7')
  })
})
