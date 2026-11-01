import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { filenameFrom } from '@/api/download'
import type { DailyReport, JournalReport, Provision, RollRate } from '@/api/reportTypes'
import { ExcelDownloadDialog } from './ExcelDownloadDialog'
import { DailyReportView, JournalView, ProvisionView, RollRateView, groupLines } from './views'

function withQuery(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

describe('ExcelDownloadDialog', () => {
  it('사유 없이는 내려받지 않고, 사유를 넣으면 넘겨준다 (R68)', async () => {
    const onDownload = vi.fn().mockResolvedValue('loans-2026-06-12.xlsx')
    render(withQuery(<ExcelDownloadDialog summary="계약 목록 전체" onDownload={onDownload} />))
    await userEvent.click(screen.getByRole('button', { name: '엑셀 다운로드' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '다운로드' }))
    expect(await within(dialog).findByText(/2자 이상/)).toBeInTheDocument()
    expect(onDownload).not.toHaveBeenCalled()

    await userEvent.type(within(dialog).getByLabelText('다운로드 사유'), '월간 점검')
    await userEvent.click(within(dialog).getByRole('button', { name: '다운로드' }))
    expect(onDownload).toHaveBeenCalledWith('월간 점검')
    expect(await within(dialog).findByText(/loans-2026-06-12.xlsx 파일을 저장했습니다/)).toBeInTheDocument()
  })
})

describe('filenameFrom', () => {
  it('Content-Disposition에서 파일명을 꺼낸다', () => {
    expect(filenameFrom('attachment; filename="loans-2026-06-12.xlsx"')).toBe('loans-2026-06-12.xlsx')
    expect(filenameFrom("attachment; filename*=UTF-8''daily-2026-06-12.xlsx")).toBe('daily-2026-06-12.xlsx')
    expect(filenameFrom(null)).toBeNull()
  })
})

const daily: DailyReport = {
  businessDate: '2026-06-12',
  closed: false,
  balancesAvailable: true,
  snapshotAt: null,
  journalDebit: 11_135_616,
  journalCredit: 11_135_616,
  journalBalanced: true,
  unmappedCount: 0,
  lines: [
    { section: 'POSTING', sectionLabel: '원장 거래유형별 (순액)', item: 'REPAYMENT', label: '입금 충당', count: 1, amount: 1_135_616 },
    { section: 'DISBURSEMENT', sectionLabel: '실행·양수', item: 'DISBURSE', label: '실행', count: 1, amount: 10_000_000 },
    { section: 'CASH', sectionLabel: '입금·정정', item: 'CORRECTION', label: '정정 (R28)', count: -1, amount: -1_135_616 },
  ],
}

describe('보고서 화면', () => {
  it('일보는 구역 순서대로 묶고 미리보기·차대 일치를 표시한다', () => {
    expect(groupLines(daily.lines).map((g) => g.section)).toEqual(['DISBURSEMENT', 'CASH', 'POSTING'])
    render(<DailyReportView data={daily} />)
    expect(screen.getByText('미리보기 (마감 전)')).toBeInTheDocument()
    expect(screen.getByTestId('journal-check')).toHaveTextContent('차변 = 대변')
    const correction = within(screen.getByRole('region', { name: '입금·정정' }))
    expect(correction.getByText('-1,135,616원')).toBeInTheDocument()
  })

  it('분개는 계정별 합계와 미매핑 거래유형을 보여준다', () => {
    const journal: JournalReport = {
      businessDate: '2026-06-12',
      closed: true,
      debit: 100_000,
      credit: 100_000,
      balanced: true,
      matchesSnapshot: true,
      totals: [
        { accountCode: 'A-LOAN', accountName: '대출채권', category: 'ASSET', debit: 0, credit: 100_000 },
        { accountCode: 'X-SUSPENSE', accountName: '미결산 (매핑 없음)', category: 'CLEARING', debit: 100_000, credit: 0 },
      ],
      unmapped: [{ postingType: 'SETOFF', count: 1, amount: 100_000 }],
      lines: [],
    }
    render(<JournalView data={journal} />)
    expect(screen.getByText('마감 스냅샷')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('SETOFF 1건 100,000원')
    expect(screen.getByText('미결산 (매핑 없음)')).toBeInTheDocument()
  })

  it('대손충당금 합계와 전이율 비율을 보여준다', () => {
    const provision: Provision = {
      month: '2026-10',
      asOf: '2026-10-31',
      closed: true,
      count: 2,
      claim: 3_000_000,
      provision: 110_000,
      rows: [
        { bucket: 'CURRENT', label: '정상', count: 1, claim: 1_000_000, rate: 1, provision: 10_000 },
        { bucket: 'D1_30', label: '1~30일', count: 1, claim: 2_000_000, rate: 5, provision: 100_000 },
      ],
    }
    render(<ProvisionView data={provision} />)
    expect(screen.getByTestId('provision-total')).toHaveTextContent('110,000원')
    expect(screen.getByText('5.00%')).toBeInTheDocument()

    const roll: RollRate = {
      month: '2026-10',
      previousMonth: '2026-09',
      previousAvailable: true,
      currentClosed: true,
      asOf: '2026-10-31',
      columns: ['CURRENT', 'D1_30', 'CLOSED'],
      rows: [
        {
          from: 'CURRENT',
          fromLabel: '정상',
          count: 2,
          balance: 8_000_000,
          cells: [
            { to: 'CURRENT', toLabel: '정상', count: 1, balance: 5_000_000, countRate: 50, balanceRate: 62.5 },
            { to: 'D1_30', toLabel: '1~30일', count: 1, balance: 3_000_000, countRate: 50, balanceRate: 37.5 },
            { to: 'CLOSED', toLabel: '종결', count: 0, balance: 0, countRate: 0, balanceRate: 0 },
          ],
          worsenRate: 37.5,
          cureRate: 0,
        },
      ],
    }
    render(<RollRateView data={roll} />)
    expect(screen.getByText('62.5%')).toBeInTheDocument()
    expect(screen.getAllByText('37.5%')).toHaveLength(2)
  })
})
