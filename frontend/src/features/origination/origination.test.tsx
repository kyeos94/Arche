import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import type { Review } from '@/api/origination'
import { ScreeningTable } from './components'
import { DocumentsTab } from './detailTabs'
import { approvalPermission, brokerSchema, nextSteps, receiveSchema } from './labels'

function wrap(node: ReactNode) {
  return <QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>
}

describe('신청 상태 전이·전결', () => {
  it('상태별 다음 단계는 서버 전이표와 같다 (승인·실행은 전용 처리)', () => {
    expect(nextSteps('RECEIVED')).toEqual(['PRE_SCREENING', 'WITHDRAWN'])
    expect(nextSteps('ASSIGNED')).toEqual(['UNDERWRITING', 'WITHDRAWN'])
    expect(nextSteps('UNDERWRITING')).toEqual(['REJECTED', 'WITHDRAWN'])
    expect(nextSteps('DISBURSED')).toEqual([])
  })

  it('1,000만원 초과 승인은 심사팀장 권한 (정책서 7장)', () => {
    expect(approvalPermission(10_000_000)).toBe('application.approve.small')
    expect(approvalPermission(10_000_001)).toBe('application.approve.large')
  })
})

describe('입력 검증', () => {
  it('신청 금액은 쉼표를 허용하고 약정일은 5일 단위', () => {
    const ok = receiveSchema.safeParse({
      productCode: 'P1',
      requestedAmount: '5,000,000',
      termMonths: '24',
      graceMonths: '0',
      dueDay: '25',
      creditInquiryConsent: false,
    })
    expect(ok.success && ok.data.requestedAmount).toBe(5_000_000)
    const bad = receiveSchema.safeParse({ productCode: 'P1', requestedAmount: '1', termMonths: 12, graceMonths: 0, dueDay: 7 })
    expect(bad.success).toBe(false)
  })

  it('중개수수료율 상한은 4% (R56)', () => {
    expect(brokerSchema.safeParse({ name: 'a', registrationNo: 'r', rate: '4.00' }).success).toBe(true)
    expect(brokerSchema.safeParse({ name: 'a', registrationNo: 'r', rate: '4.5' }).success).toBe(false)
  })
})

describe('ScreeningTable', () => {
  it('규칙별 결과와 근거를 보여준다', () => {
    render(
      <ScreeningTable
        rows={[
          { rule: 'MIN_AGE', label: '만 19세 미만', outcome: 'PASS', detail: '만 39세', screenedOn: '2026-11-01' },
          { rule: 'CREDIT_BUREAU', label: '신용조회 현재 연체', outcome: 'REJECT', detail: '등급 9', screenedOn: '2026-11-01' },
          { rule: 'DUPLICATE_APPLICATION', label: '중복 신청', outcome: 'SKIPPED', detail: '-', screenedOn: '2026-11-01' },
        ]}
      />,
    )
    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).getByText('통과')).toBeInTheDocument()
    expect(within(rows[2]).getByText('거절')).toBeInTheDocument()
    expect(within(rows[3]).getByText('미실시')).toBeInTheDocument()
  })

  it('사전심사 전이면 빈 안내', () => {
    render(<ScreeningTable rows={[]} />)
    expect(screen.getByText('사전심사 전입니다')).toBeInTheDocument()
  })
})

const review = (status: Review['application']['status']): Review => ({
  application: {
    id: 1,
    applicationNo: 'A00001001',
    customerId: 1,
    customerName: '김고객',
    productCode: 'P1',
    requestedAmount: 5_000_000,
    termMonths: 24,
    graceMonths: 0,
    dueDay: 25,
    approvedAmount: null,
    approvedRate: null,
    status,
    underwriterId: null,
    underwriterName: null,
    disbursedOn: null,
    channel: 'STAFF',
    intakeSource: null,
    externalRef: null,
    assignedAt: null,
    decidedOn: null,
    rejectReasonCode: null,
    receivedAt: '2026-11-01T10:00:00+09:00',
  },
  customer: {
    id: 1,
    customerNo: 'C00001001',
    name: '김고객',
    residentNoMasked: '900101-1******',
    birthDate: '1990-01-01',
    phoneMasked: null,
  },
  screening: [],
  consents: [],
  creditInquiries: [],
  missingRequiredDocuments: ['재직 증빙'],
  creditConsentValid: false,
  documents: [
    doc(1, 'ID_CARD', '신분증 사본', 'VERIFIED'),
    doc(2, 'INCOME_PROOF', '소득 증빙', 'RECEIVED'),
    doc(3, 'EMPLOYMENT_PROOF', '재직 증빙', 'REQUESTED'),
  ],
})

function doc(id: number, docType: string, label: string, status: 'REQUESTED' | 'RECEIVED' | 'VERIFIED') {
  return {
    id,
    docType,
    label,
    required: true,
    status,
    receivedVia: status === 'REQUESTED' ? null : 'PAPER',
    fileName: null,
    contentType: null,
    sizeBytes: null,
    receivedAt: null,
    reviewedBy: status === 'VERIFIED' ? 'uw' : null,
    reviewedAt: null,
    note: null,
  }
}

describe('DocumentsTab (R54)', () => {
  it('상태에 맞는 처리만 보여준다: 요청 → 접수, 접수 → 확인·보완, 확인 → 없음', () => {
    render(wrap(<DocumentsTab review={review('UNDERWRITING')} onDone={async () => {}} />))
    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).queryByRole('button')).toBeNull()
    expect(within(rows[2]).getByRole('button', { name: '확인' })).toBeInTheDocument()
    expect(within(rows[2]).getByRole('button', { name: '보완 요청' })).toBeDisabled()
    expect(within(rows[3]).getByRole('button', { name: '서면 접수' })).toBeInTheDocument()
    expect(within(rows[3]).getByLabelText('재직 증빙 파일 업로드')).toBeInTheDocument()
  })

  it('승인 후에는 서류를 바꿀 수 없다', () => {
    render(wrap(<DocumentsTab review={review('APPROVED')} onDone={async () => {}} />))
    expect(screen.queryAllByRole('button')).toHaveLength(0)
  })
})
