/**
 * 연락 타임라인·발송·템플릿, 담보·보증인, 출금, 신용정보 보고 API (ADR 0008).
 * 계약 하나의 쿼리 키는 ['loan', id, ...]로 시작해 계약 무효화에 같이 걸린다.
 */
import { api, qs } from '@/api/client'

// ------------------------------------------------------------------ 연락 타임라인 (R59)

export type DispatchChannel = 'SMS' | 'FAX' | 'POST'
export type DispatchPurpose = 'COLLECTION' | 'NOTICE'

export interface TimelineItem {
  kind: 'CONTACT' | 'AUTO_MESSAGE' | 'DISPATCH' | 'MEMO'
  sourceId: number
  at: string
  date: string
  channel: string
  title: string | null
  summary: string | null
  status: string | null
  reason: string | null
  actor: string
}

export interface TimelinePage {
  businessDate: string
  remainingToday: number
  blockingReason: string | null
  content: TimelineItem[]
  page: number
  size: number
  totalElements: number
  hasNext: boolean
}

export interface Dispatch {
  id: number
  channel: DispatchChannel
  purpose: DispatchPurpose
  title: string | null
  body: string
  recipientMasked: string | null
  status: string
  reason: string | null
  contactDate: string
  countInWindow: number
  providerId: string | null
  fileRef: string | null
  sentAt: string | null
  deliveredOn: string | null
  remainingToday: number
}

export interface MessageTemplate {
  id: number
  code: string
  name: string
  channel: DispatchChannel
  purpose: DispatchPurpose
  title: string | null
  body: string
  active: boolean
  placeholders: string[]
}

export interface TemplateRequest {
  code: string
  name: string
  channel: DispatchChannel
  purpose: DispatchPurpose
  title?: string
  body: string
  active?: boolean
}

export interface SendRequest {
  channel: DispatchChannel
  templateId?: number
  title?: string
  body?: string
  faxNumber?: string
}

// ------------------------------------------------------------------ 담보·보증인 (R60)

export interface Valuation {
  id: number
  valuedOn: string
  price: number
  source: string
  basis: string
}

export interface Collateral {
  id: number
  type: 'VEHICLE' | 'REAL_ESTATE'
  description: string
  identifier: string | null
  vehicleClass: string | null
  modelYear: number | null
  address: string | null
  areaM2: number | null
  seniorClaim: number
  lienAmount: number
  lienRank: number
  status: 'ACTIVE' | 'RELEASED'
  releasedOn: string | null
  ratio: number
  recognizedValue: number | null
  stale: boolean
  latest: Valuation | null
  history: Valuation[]
}

export interface CollateralOverview {
  businessDate: string
  collaterals: Collateral[]
  summary: {
    totalPrice: number
    totalSenior: number
    totalRecognized: number
    principalBalance: number
    ltv: number | null
    margin: number
    valuedCount: number
    staleCount: number
  }
}

export interface CollateralRequest {
  type: 'VEHICLE' | 'REAL_ESTATE'
  description: string
  identifier?: string
  vehicleClass?: string
  modelYear?: number
  address?: string
  areaM2?: number
  seniorClaim: number
  lienAmount: number
  lienRank?: number
}

export interface Guarantor {
  id: number
  name: string
  residentNoMasked: string
  phoneMasked: string | null
  relation: string
  guaranteeLimit: number
  startDate: string
  endDate: string | null
  status: 'ACTIVE' | 'RELEASED'
  releasedOn: string | null
}

export interface GuarantorRequest {
  name: string
  residentNo: string
  phone?: string
  relation: string
  guaranteeLimit: number
  startDate: string
  endDate?: string
}

// ------------------------------------------------------------------ 출금 (R62~R64)

export type WithdrawalType = 'UNMATCHED_REFUND' | 'OVERPAYMENT_REFUND'

export interface Withdrawal {
  id: number
  withdrawalNo: string
  type: WithdrawalType
  accountId: number | null
  unmatchedId: number | null
  amount: number
  bankName: string
  payeeAccountMasked: string
  holderName: string
  reason: string
  status: string
  twoStep: boolean
  requestedOn: string
  requestedBy: string
  firstApprovedBy: string | null
  firstApprovedAt: string | null
  finalApprovedBy: string | null
  finalApprovedAt: string | null
  rejectedBy: string | null
  rejectReason: string | null
  idempotencyKey: string
  providerTxId: string | null
  resultCode: string | null
  resultMessage: string | null
  sentAt: string | null
  completedAt: string | null
  inquiryCount: number
  nextStep: string | null
}

export interface WithdrawalCandidates {
  unmatched: {
    unmatchedId: number
    paymentId: number
    amount: number
    bankDate: string
    depositorName: string | null
    status: string
  }[]
  refunds: { accountId: number; accountNo: string; refundPending: number; reserved: number; available: number }[]
}

export interface WithdrawalRequest {
  type: WithdrawalType
  accountId?: number
  unmatchedId?: number
  amount: number
  bankName: string
  accountNo: string
  holderName: string
  reason: string
}

// ------------------------------------------------------------------ 신용정보 보고 (R61)

export interface CreditReportFile {
  id: number
  baseDate: string
  fileName: string
  recordCount: number
  byteLength: number
  status: 'GENERATED' | 'SENT' | 'RESULT_RECEIVED'
  receiptNo: string | null
  sentAt: string | null
  resultAt: string | null
  errorCount: number
}

export interface CreditReportDetail {
  file: CreditReportFile
  records: {
    seq: number
    accountId: number
    recordType: 'OPEN' | 'DELINQUENCY' | 'RELEASE' | 'CLOSE'
    eventDate: string
    balance: number
    delinquencyDays: number
    resultCode: string | null
    resultMessage: string | null
  }[]
}

// ------------------------------------------------------------------ 키·호출

export const sliceKeys = {
  timeline: (id: number) => ['loan', id, 'timeline'] as const,
  collaterals: (id: number) => ['loan', id, 'collaterals'] as const,
  guarantors: (id: number) => ['loan', id, 'guarantors'] as const,
  templates: (activeOnly: boolean) => ['message-templates', activeOnly] as const,
  withdrawals: (status: string) => ['withdrawals', status] as const,
  withdrawalCandidates: ['withdrawals', 'candidates'] as const,
  creditReports: ['credit-reports'] as const,
  creditReport: (id: number) => ['credit-reports', id] as const,
}

export const sliceApi = {
  timeline: (id: number, page: number, size = 20) =>
    api.get<TimelinePage>(`/api/loans/${id}/timeline${qs({ page, size })}`),
  send: (id: number, body: SendRequest) => api.post<Dispatch>(`/api/loans/${id}/dispatches`, body),
  track: (id: number, dispatchId: number) => api.post<Dispatch>(`/api/loans/${id}/dispatches/${dispatchId}/track`),
  memo: (id: number, body: string) => api.post<TimelineItem>(`/api/loans/${id}/memos`, { body }),
  preview: (id: number, templateId: number) =>
    api.get<{ title: string | null; body: string; purpose: DispatchPurpose }>(
      `/api/loans/${id}/templates/${templateId}/preview`,
    ),
  templates: (activeOnly: boolean) => api.get<MessageTemplate[]>(`/api/message-templates${qs({ activeOnly })}`),
  createTemplate: (body: TemplateRequest) => api.post<MessageTemplate>('/api/message-templates', body),
  updateTemplate: (id: number, body: TemplateRequest) =>
    api.post<MessageTemplate>(`/api/message-templates/${id}`, body),

  collaterals: (id: number) => api.get<CollateralOverview>(`/api/loans/${id}/collaterals`),
  registerCollateral: (id: number, body: CollateralRequest) =>
    api.post<Collateral>(`/api/loans/${id}/collaterals`, body),
  valuate: (id: number, collateralId: number) =>
    api.post<Valuation>(`/api/loans/${id}/collaterals/${collateralId}/valuations`),
  releaseCollateral: (id: number, collateralId: number) =>
    api.post<Collateral>(`/api/loans/${id}/collaterals/${collateralId}/release`),
  guarantors: (id: number) => api.get<Guarantor[]>(`/api/loans/${id}/guarantors`),
  registerGuarantor: (id: number, body: GuarantorRequest) =>
    api.post<Guarantor>(`/api/loans/${id}/guarantors`, body),
  releaseGuarantor: (id: number, guarantorId: number) =>
    api.post<Guarantor>(`/api/loans/${id}/guarantors/${guarantorId}/release`),

  withdrawals: (status: string) => api.get<Withdrawal[]>(`/api/withdrawals${qs({ status })}`),
  withdrawalCandidates: () => api.get<WithdrawalCandidates>('/api/withdrawals/candidates'),
  createWithdrawal: (body: WithdrawalRequest) => api.post<Withdrawal>('/api/withdrawals', body),
  approveWithdrawal: (id: number) => api.post<Withdrawal>(`/api/withdrawals/${id}/approve`),
  rejectWithdrawal: (id: number, reason: string) => api.post<Withdrawal>(`/api/withdrawals/${id}/reject`, { reason }),
  cancelWithdrawal: (id: number) => api.post<Withdrawal>(`/api/withdrawals/${id}/cancel`),
  inquireWithdrawal: (id: number) => api.post<Withdrawal>(`/api/withdrawals/${id}/inquire`),

  creditReports: () =>
    api.get<{ defaultBaseDate: string; files: CreditReportFile[] }>('/api/credit-reports'),
  creditReport: (id: number) => api.get<CreditReportDetail>(`/api/credit-reports/${id}`),
  runCreditReport: (baseDate?: string) => api.post<CreditReportFile>('/api/credit-reports/run', { baseDate }),
  sendCreditReport: (id: number) => api.post<CreditReportFile>(`/api/credit-reports/${id}/send`),
  creditReportResult: (id: number) => api.post<CreditReportFile>(`/api/credit-reports/${id}/result`),
  creditReportFileUrl: (id: number) => `/api/credit-reports/${id}/file`,
}

/** 이 화면들에서 쓰는 코드 이름 (공용 statusLabel에 없는 것) */
export const sliceLabels: Record<string, string> = {
  CONTACT: '연락',
  AUTO_MESSAGE: '자동 문자',
  DISPATCH: '발송',
  MEMO: '메모',
  SMS: '문자',
  FAX: '팩스',
  POST: '우편',
  CALL: '전화',
  VISIT: '방문',
  STATUTORY_NOTICE: '법정 통지',
  COLLECTION: '추심 안내',
  NOTICE: '법정·계약상 통지',
  SCHEDULED: '예약',
  SENT: '발송',
  ACCEPTED: '접수',
  IN_TRANSIT: '배송 중',
  DELIVERED: '배달 완료',
  RETURNED: '반송',
  FAILED: '실패',
  BLOCKED: '차단',
  ALLOWED: '허용',
  SKIPPED: '건너뜀',
  VEHICLE: '차량',
  REAL_ESTATE: '부동산',
  ACTIVE: '유효',
  RELEASED: '해지',
  UNMATCHED_REFUND: '미확인 입금 반환',
  OVERPAYMENT_REFUND: '과입금 반환',
  REQUESTED: '승인 대기',
  FIRST_APPROVED: '1단계 승인',
  SENDING: '송금 중',
  UNKNOWN: '결과 조회 필요',
  COMPLETED: '송금 완료',
  REJECTED: '반려',
  CANCELLED: '취소',
  GENERATED: '생성',
  RESULT_RECEIVED: '결과 반영',
  OPEN: '개설',
  DELINQUENCY: '연체 등록',
  RELEASE: '연체 해제',
  CLOSE: '종료',
}

export function sliceLabel(code: string | null | undefined): string {
  if (!code) return '-'
  return sliceLabels[code] ?? code
}

export const sliceTones: Record<string, string> = {
  SENT: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  DELIVERED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  COMPLETED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  ALLOWED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  RESULT_RECEIVED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  ACCEPTED: 'bg-sky-50 text-sky-700 border-sky-200',
  IN_TRANSIT: 'bg-sky-50 text-sky-700 border-sky-200',
  SENDING: 'bg-sky-50 text-sky-700 border-sky-200',
  SCHEDULED: 'bg-amber-50 text-amber-800 border-amber-200',
  REQUESTED: 'bg-amber-50 text-amber-800 border-amber-200',
  FIRST_APPROVED: 'bg-amber-50 text-amber-800 border-amber-200',
  UNKNOWN: 'bg-amber-50 text-amber-800 border-amber-200',
  BLOCKED: 'bg-red-50 text-red-700 border-red-200',
  FAILED: 'bg-red-50 text-red-700 border-red-200',
  RETURNED: 'bg-red-50 text-red-700 border-red-200',
  REJECTED: 'bg-slate-100 text-slate-600 border-slate-200',
  CANCELLED: 'bg-slate-100 text-slate-600 border-slate-200',
  RELEASED: 'bg-slate-100 text-slate-600 border-slate-200',
}
