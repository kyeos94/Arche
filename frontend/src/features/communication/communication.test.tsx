import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '@/api/types'
import { AuthProvider } from '@/features/auth/auth'
import type { MessageTemplate, TimelinePage } from './api'
import { buildSendRequest } from './quickInput'
import { collateralSchema, guarantorSchema, templateSchema, unknownPlaceholders, withdrawalSchema } from './schemas'
import { TimelinePanel } from './TimelinePanel'

const me: Me = {
  id: 1,
  loginId: 'col1',
  name: '채권담당',
  role: 'COLLECTOR',
  roleLabel: '채권담당',
  teamId: 2,
  permissions: ['loan.read', 'collection.activity'],
  passwordChangeRequired: false,
  passwordExpiresInDays: -1,
}

const page: TimelinePage = {
  businessDate: '2026-11-01',
  remainingToday: 0,
  blockingReason: '연락 유예 중입니다 (2026-11-01 ~ 2026-12-01, 입원)',
  content: [
    {
      kind: 'DISPATCH',
      sourceId: 5,
      at: '2026-11-01T10:00:00+09:00',
      date: '2026-11-01',
      channel: 'POST',
      title: '납부 독촉 안내',
      summary: '홍길동님 귀하',
      status: 'ACCEPTED',
      reason: null,
      actor: 'col1',
    },
    {
      kind: 'MEMO',
      sourceId: 3,
      at: '2026-11-01T09:00:00+09:00',
      date: '2026-11-01',
      channel: 'MEMO',
      title: null,
      summary: '배우자와 통화',
      status: null,
      reason: null,
      actor: 'col1',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  hasNext: false,
}

const templates: MessageTemplate[] = [
  {
    id: 7,
    code: 'SMS_DUE_GUIDE',
    name: '연체 납부 안내 문자',
    channel: 'SMS',
    purpose: 'COLLECTION',
    title: null,
    body: '{고객명}님',
    active: true,
    placeholders: [],
  },
]

function renderPanel() {
  const calls: { url: string; method: string; body: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined })
      const json = (v: unknown) => new Response(JSON.stringify(v), { status: 200 })
      if (url.includes('/api/auth/me')) return json(me)
      if (url.includes('/timeline')) return json(page)
      if (url.includes('/api/message-templates')) return json(templates)
      if (url.includes('/dispatches')) {
        return json({ status: 'BLOCKED', reason: '연락 유예 중입니다', contactDate: '2026-11-01', remainingToday: 0 })
      }
      return json({})
    }),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <TimelinePanel loanId={9} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  return calls
}

describe('TimelinePanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('남은 연락 횟수·막는 사유와 출처별 기록을 보여준다', async () => {
    renderPanel()
    expect(await screen.findByText('납부 독촉 안내')).toBeInTheDocument()
    expect(screen.getByTestId('remaining-today')).toHaveTextContent('0회 / 7회')
    expect(screen.getByText(/연락 유예 중입니다/)).toBeInTheDocument()
    expect(screen.getByText('우편 발송')).toBeInTheDocument()
    expect(screen.getByText('배우자와 통화')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '배달 조회' })).toBeInTheDocument()
  })

  it('문자를 보내면 서버 판정(차단)을 그대로 알려준다', async () => {
    const calls = renderPanel()
    const form = await screen.findByRole('form', { name: '빠른 입력' })
    await userEvent.click(within(form).getByRole('radio', { name: '문자' }))
    await userEvent.type(within(form).getByLabelText(/내용/), '납부 안내')
    await userEvent.click(within(form).getByRole('button', { name: '문자 보내기' }))
    expect(await screen.findByText(/차단: 연락 유예 중입니다/)).toBeInTheDocument()
    const sent = calls.find((c) => c.url.endsWith('/api/loans/9/dispatches'))
    expect(sent?.method).toBe('POST')
    expect(sent?.body).toEqual({ channel: 'SMS', body: '납부 안내' })
  })
})

describe('빠른 입력·폼 검증', () => {
  it('템플릿을 고르면 내용 대신 템플릿 ID를, 팩스는 번호를 보낸다', () => {
    expect(buildSendRequest('SMS', { templateId: '7', title: '', body: '무시', faxNumber: '' })).toEqual({
      channel: 'SMS',
      templateId: 7,
    })
    expect(buildSendRequest('FAX', { templateId: '', title: ' 확인서 ', body: ' 본문 ', faxNumber: '02-1234-5678' })).toEqual(
      { channel: 'FAX', title: '확인서', body: '본문', faxNumber: '02-1234-5678' },
    )
  })

  it('템플릿은 모르는 치환자와 제목 없는 우편을 거절한다', () => {
    expect(unknownPlaceholders('{고객명} {고객이름} {기준일}')).toEqual(['고객이름'])
    const base = { code: 'POST_X', name: '우편', channel: 'POST', purpose: 'NOTICE', body: '{고객명}님', active: true }
    expect(templateSchema.safeParse(base).success).toBe(false)
    expect(templateSchema.safeParse({ ...base, title: '통지' }).success).toBe(true)
    expect(templateSchema.safeParse({ ...base, title: '통지', body: '{고객이름}' }).success).toBe(false)
  })

  it('담보는 유형별 필수값을 보고 금액을 원 단위로 바꾼다', () => {
    const v = collateralSchema.parse({
      type: 'REAL_ESTATE',
      description: '아파트',
      address: '가상시 중앙구',
      areaM2: '84.95',
      seniorClaim: '120,000,000',
      lienAmount: '',
    })
    expect(v).toMatchObject({ areaM2: 84.95, seniorClaim: 120_000_000, lienAmount: 0, vehicleClass: undefined })
    expect(collateralSchema.safeParse({ type: 'VEHICLE', description: '차', modelYear: '23' }).success).toBe(false)
  })

  it('보증인·출금 입력을 검증한다', () => {
    const g = { name: '보증', residentNo: '800101-1234567', relation: '배우자', guaranteeLimit: '1,000,000', startDate: '2026-11-01' }
    expect(guarantorSchema.parse(g).guaranteeLimit).toBe(1_000_000)
    expect(guarantorSchema.safeParse({ ...g, residentNo: '1234' }).success).toBe(false)
    expect(guarantorSchema.safeParse({ ...g, endDate: '2026-10-01' }).success).toBe(false)
    const w = { bankName: '가상은행', accountNo: '110-123-4561', holderName: '홍길동', amount: '3,000,000', reason: '반환' }
    expect(withdrawalSchema.parse(w).amount).toBe(3_000_000)
    expect(withdrawalSchema.safeParse({ ...w, accountNo: '계좌' }).success).toBe(false)
  })
})
