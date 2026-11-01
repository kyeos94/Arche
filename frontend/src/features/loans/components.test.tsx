import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { LoanDetail } from '@/api/types'
import { StatusTabs } from './StatusTabs'
import { SummaryBar } from './SummaryBar'

describe('StatusTabs', () => {
  it('상태별 건수와 전체 합계를 보여주고 클릭하면 상태를 넘긴다', async () => {
    const onChange = vi.fn()
    render(
      <StatusTabs
        counts={{ NORMAL: 3, DELINQUENT: 2, ACCELERATED: 1, PAID_OFF: 0 }}
        value=""
        onChange={onChange}
      />,
    )
    expect(screen.getByRole('tab', { name: /전체 6/ })).toHaveAttribute('aria-selected', 'true')
    await userEvent.click(screen.getByRole('tab', { name: /연체 2/ }))
    expect(onChange).toHaveBeenCalledWith('DELINQUENT')
  })
})

const g27: LoanDetail = {
  id: 2,
  accountNo: 'L00001002',
  customer: {
    id: 2,
    customerNo: 'C00001002',
    name: '김연체',
    residentNoMasked: '900101-1******',
    birthDate: '1990-01-01',
    phoneMasked: '010-****-5678',
  },
  productCode: 'P3',
  productName: '자동차담보',
  repaymentMethod: 'EQUAL_PRINCIPAL',
  origin: 'ORIGINATED',
  status: 'DELINQUENT',
  initialPrincipal: 12_000_000,
  disbursedOn: '2026-03-20',
  maturityDate: '2027-03-25',
  termMonths: 12,
  graceMonths: 0,
  currentRate: 15,
  lateRate: 18,
  currentDueDay: 25,
  installmentAmount: null,
  principalBalance: 11_000_000,
  suspenseBalance: 0,
  costBalance: 0,
  preAcqInterest: 0,
  outstandingNormalInterest: 230_547,
  outstandingLateInterest: 1_726,
  payoffAmount: 11_377_000,
  prepaymentFeeToday: 0,
  delinquencyDays: 21,
  delinquentSince: '2026-05-25',
  nextDueDate: '2026-06-25',
  acceleratedOn: null,
  paidOffOn: null,
  lastSettledOn: '2026-04-25',
  asOf: '2026-06-15',
  businessDate: '2026-06-15',
  virtualAccountNo: '99000001003',
  totalPaid: 1_177_534,
  refundPending: 0,
  assignedStaffId: 3,
  limitationCompletionDate: null,
  acquisition: null,
  assignedStaffName: null,
  assignedStaffTeam: null,
}

describe('SummaryBar', () => {
  it('G27 취소 후 상태: 연체 21일, 잔액 11,000,000원', () => {
    render(<SummaryBar loan={g27} />)
    const bar = screen.getByRole('region', { name: '계약 요약' })
    expect(within(bar).getByText('김연체')).toBeInTheDocument()
    expect(within(bar).getByText('L00001002')).toBeInTheDocument()
    expect(within(bar).getByText('연체')).toBeInTheDocument()
    expect(within(bar).getByText('11,000,000원')).toBeInTheDocument()
    expect(within(bar).getByText('21일')).toBeInTheDocument()
    expect(within(bar).getByText('2026-06-25')).toBeInTheDocument()
  })
})
