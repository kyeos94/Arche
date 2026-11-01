import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SplitTargets } from '@/api/types'
import { SplitPage } from './SplitPage'

const targets: SplitTargets = {
  customerId: 7,
  customerNo: 'C00001007',
  customerName: '배분고객',
  businessDate: '2026-11-01',
  accounts: [
    {
      accountId: 1,
      accountNo: 'L00001001',
      productCode: 'P1',
      status: 'DELINQUENT',
      disbursedOn: '2026-01-10',
      delinquentSince: '2026-09-10',
      nextDueDate: '2026-11-10',
      overdue: 300_000,
      nextDue: 150_000,
      payoff: 5_000_000,
    },
    {
      accountId: 2,
      accountNo: 'L00001002',
      productCode: 'N1',
      status: 'ACCELERATED',
      disbursedOn: '2026-06-01',
      delinquentSince: null,
      nextDueDate: null,
      overdue: 1_000_000,
      nextDue: 0,
      payoff: 1_000_000,
    },
  ],
}

function renderPage() {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = String(input)
    const body = url.includes('/api/operation/business-date')
      ? { businessDate: '2026-11-01' }
      : url.includes('/api/payment-splits/targets')
        ? targets
        : url.includes('/api/payment-splits/preview')
          ? [
              { accountId: 1, accountNo: 'L00001001', amount: 300_000 },
              { accountId: 2, accountNo: 'L00001002', amount: 100_000 },
            ]
          : {}
    return new Response(JSON.stringify(body), { status: 200 })
  })
  vi.stubGlobal('fetch', fetchMock)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/payment-splits']}>
        <SplitPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return fetchMock
}

describe('SplitPage (R47)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('계약번호로 고객의 계약을 찾고 배분을 미리 본 뒤에만 실행할 수 있다', async () => {
    const fetchMock = renderPage()
    await userEvent.type(screen.getByLabelText('고객의 계약번호 하나'), 'L00001001')
    await userEvent.click(screen.getByRole('button', { name: '조회' }))
    expect(await screen.findByText(/배분고객/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '배분 실행' })).toBeDisabled()

    await userEvent.type(screen.getByLabelText('입금액'), '400,000')
    await userEvent.click(screen.getByRole('button', { name: '배분 미리보기' }))
    expect(await screen.findByText('100,000원')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '배분 실행' })).toBeEnabled()

    const previewCall = fetchMock.mock.calls.find((c) => String(c[0]).includes('/preview'))
    expect(JSON.parse(String(previewCall?.[1]?.body))).toMatchObject({
      customerId: 7,
      method: 'OLDEST_DELINQUENCY_FIRST',
      amount: 400_000,
    })
  })
})
