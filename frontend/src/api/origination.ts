/**
 * 심사(origination) API — 신청·자동 거절·서류·배정·중개업자·플랫폼·본인인증 랜딩 (ADR 0007).
 * 다른 화면과 겹치지 않게 endpoints.ts와 분리해 둔다.
 */
import { ApiError, api, qs, request } from './client'
import type { CustomerView } from './types'

export type ApplicationStatus =
  | 'RECEIVED'
  | 'PRE_SCREENING'
  | 'ASSIGNED'
  | 'UNDERWRITING'
  | 'APPROVED'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'CONTRACTED'
  | 'DISBURSED'

export interface Application {
  id: number
  applicationNo: string
  customerId: number
  customerName: string
  productCode: string
  requestedAmount: number
  termMonths: number
  graceMonths: number
  dueDay: number
  approvedAmount: number | null
  approvedRate: number | null
  status: ApplicationStatus
  underwriterId: number | null
  underwriterName: string | null
  disbursedOn: string | null
  channel: 'STAFF' | 'BROKER' | 'PLATFORM'
  intakeSource: string | null
  externalRef: string | null
  assignedAt: string | null
  decidedOn: string | null
  rejectReasonCode: string | null
  receivedAt: string
}

export interface ScreeningRow {
  rule: string
  label: string
  outcome: 'PASS' | 'REJECT' | 'SKIPPED'
  detail: string
  screenedOn: string
}

export interface Consent {
  id: number
  type: string
  channel: string
  agreedOn: string
  validUntil: string
  evidence: string | null
  withdrawnAt: string | null
  valid: boolean
}

export interface CreditInquiry {
  id: number
  inquiryRef: string
  grade: number
  currentlyDelinquent: boolean
  openLoanCount: number
  totalDebt: number
  inquiredOn: string
}

export interface ApplicationDocument {
  id: number
  docType: string
  label: string
  required: boolean
  status: 'REQUESTED' | 'RECEIVED' | 'VERIFIED' | 'REJECTED'
  receivedVia: string | null
  fileName: string | null
  contentType: string | null
  sizeBytes: number | null
  receivedAt: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  note: string | null
}

export interface Review {
  application: Application
  customer: CustomerView
  screening: ScreeningRow[]
  consents: Consent[]
  creditInquiries: CreditInquiry[]
  documents: ApplicationDocument[]
  missingRequiredDocuments: string[]
  creditConsentValid: boolean
}

export interface ApplicationHistory {
  fromStatus: string | null
  toStatus: string
  actor: string
  reason: string | null
  changedAt: string
}

export interface ChannelInfo {
  channel: string
  broker: { id: number; brokerNo: string; name: string; status: string } | null
  commissions: {
    entryType: string
    baseAmount: number
    rate: number
    amount: number
    occurredOn: string
    reasonCode: string | null
    reason: string | null
  }[]
  platformInquiry: {
    inquiryNo: string
    platformCode: string
    platformRef: string
    creditGrade: number | null
    offered: boolean
    offeredLimit: number | null
    offeredRate: number | null
    inquiredOn: string
    validUntil: string
  } | null
  platformNotices: { result: string; delivered: boolean; receiptId: string | null; attempts: number; lastSentAt: string }[]
  landingLinks: LandingLink[]
}

export interface LandingLink {
  id: number
  status: string
  expiresAt: string
  failedAttempts: number
  verifiedAt: string | null
  completedAt: string | null
  createdAt: string
}

export interface Underwriter {
  staffId: number
  loginId: string
  name: string
  role: string
  productCodes: string[]
  allProducts: boolean
  available: boolean
  pending: number
  lastAssignedAt: string | null
}

export interface DocumentRequirement {
  productCode: string
  docType: string
  label: string
  required: boolean
}

export interface Broker {
  id: number
  brokerNo: string
  name: string
  registrationNo: string
  allowedIps: string | null
  status: 'ACTIVE' | 'SUSPENDED'
  currentRate: number | null
}

export interface Commission {
  id: number
  brokerId: number
  applicationId: number
  applicationNo: string
  entryType: 'SETTLEMENT' | 'CLAWBACK'
  baseAmount: number
  rate: number
  amount: number
  occurredOn: string
  reasonCode: string | null
  reason: string | null
  createdAt: string
}

export interface BrokerDetail {
  broker: Broker
  rates: { id: number; rate: number; validFrom: string; validTo: string | null; reason: string }[]
  commissions: Commission[]
  settledTotal: number
  clawbackTotal: number
}

export interface ReceiveApplication {
  customerId: number
  productCode: string
  requestedAmount: number
  termMonths: number
  graceMonths: number
  dueDay: number
  creditInquiryConsent: boolean
  consentEvidence?: string
}

/** 랜딩(고객) 화면 */
export interface LandingView {
  applicationNo: string
  productName: string
  customerName: string
  status: string
  expiresAt: string
  consentGiven: boolean
  documents: { docType: string; label: string; required: boolean; status: string }[]
}

export interface LimitInquiryResult {
  inquiryNo: string
  platformRef: string
  offered: boolean
  offeredLimit: number | null
  offeredRate: number | null
  declineReason: string | null
  validUntil: string
}

export interface IntakeResult {
  applicationNo: string
  status: string
  rejectReasonCode: string | null
}

export const originationKeys = {
  applications: (status: string) => ['applications', status] as const,
  review: (id: number) => ['application', id, 'review'] as const,
  history: (id: number) => ['application', id, 'history'] as const,
  channel: (id: number) => ['application', id, 'channel'] as const,
  underwriters: ['underwriters'] as const,
  requirements: ['document-requirements'] as const,
  brokers: ['brokers'] as const,
  broker: (id: number) => ['brokers', id] as const,
}

const app = (id: number) => `/api/applications/${id}`

/** 플랫폼·본인인증처럼 우리 세션 밖에서 부르는 API (헤더 키 직접 지정) */
async function external<T>(url: string, body: unknown, headers: Record<string, string> = {}): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : undefined
  if (!res.ok) {
    throw new ApiError(res.status, data && data.message ? data : { code: `HTTP_${res.status}`, message: '요청 실패' })
  }
  return data as T
}

export const origination = {
  applications: (status: string) => api.get<Application[]>(`/api/applications${qs({ status })}`),
  receive: (body: ReceiveApplication) => api.post<Application>('/api/applications', body),
  review: (id: number) => api.get<Review>(`${app(id)}/review`),
  history: (id: number) => api.get<ApplicationHistory[]>(`${app(id)}/history`),
  channel: (id: number) => api.get<ChannelInfo>(`${app(id)}/channel`),
  transition: (
    id: number,
    body: { to: ApplicationStatus; reason?: string; reasonCode?: string; underwriterId?: number },
  ) => api.post<Application>(`${app(id)}/transitions`, body),
  approve: (id: number, body: { amount: number; rate: number }) => api.post<Application>(`${app(id)}/approval`, body),
  disburse: (id: number) => api.post<Application>(`${app(id)}/disbursement`),
  reassign: (id: number, body: { underwriterId: number; reason: string }) =>
    api.post<Application>(`${app(id)}/assignment`, body),
  recordConsent: (id: number, evidence: string) => api.post<Consent>(`${app(id)}/consents`, { evidence }),
  withdrawConsent: (id: number, consentId: number) =>
    api.post<Consent>(`${app(id)}/consents/${consentId}/withdrawal`),
  inquireCredit: (id: number) => api.post<CreditInquiry>(`${app(id)}/credit-inquiries`),
  uploadDocument: (id: number, docId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload<ApplicationDocument>(`${app(id)}/documents/${docId}/file`, form)
  },
  receivePaper: (id: number, docId: number, note: string) =>
    api.post<ApplicationDocument>(`${app(id)}/documents/${docId}/paper`, { note }),
  verifyDocument: (id: number, docId: number) => api.post<ApplicationDocument>(`${app(id)}/documents/${docId}/verify`),
  rejectDocument: (id: number, docId: number, note: string) =>
    api.post<ApplicationDocument>(`${app(id)}/documents/${docId}/reject`, { note }),
  documentFileUrl: (id: number, docId: number) => `${app(id)}/documents/${docId}/file`,
  issueLanding: (id: number) =>
    api.post<{ link: LandingLink; path: string; url: string; messageId: string }>(`${app(id)}/landing-links`),
  resendPlatformNotice: (id: number) => api.post<ChannelInfo['platformNotices']>(`${app(id)}/platform-notices/resend`),

  underwriters: () => api.get<{ strategy: string; underwriters: Underwriter[] }>('/api/underwriters'),
  saveProfile: (staffId: number, body: { productCodes: string[]; available: boolean }) =>
    api.post<void>(`/api/underwriters/${staffId}/profile`, body),
  requirements: () => api.get<DocumentRequirement[]>('/api/document-requirements'),

  brokers: () => api.get<Broker[]>('/api/brokers'),
  broker: (id: number) => api.get<BrokerDetail>(`/api/brokers/${id}`),
  registerBroker: (body: { name: string; registrationNo: string; allowedIps?: string; rate: number }) =>
    api.post<{ broker: Broker; apiKey: string }>('/api/brokers', body),
  updateBroker: (id: number, body: { status?: string; allowedIps?: string | null }) =>
    api.post<Broker>(`/api/brokers/${id}`, body),
  rotateBrokerKey: (id: number) => api.post<{ broker: Broker; apiKey: string }>(`/api/brokers/${id}/api-key`),
  changeBrokerRate: (id: number, body: { rate: number; validFrom: string; reason: string }) =>
    api.post<unknown>(`/api/brokers/${id}/rates`, body),
  misSelling: (applicationId: number, reason: string) =>
    api.post<Commission[]>(`/api/brokers/commissions/applications/${applicationId}/mis-selling`, { reason }),
  clawbackScan: () => api.post<{ clawbacks: number }>('/api/brokers/commissions/clawback-scan'),

  // ---------------------------------------------------------------- 고객 랜딩 (세션 없음)
  landing: (token: string) => request<LandingView>('GET', `/api/integration/landing/${token}`),
  landingStartIdentity: (token: string) =>
    external<{ transactionId: string; authUrl: string }>(`/api/integration/landing/${token}/identity`, {}),
  landingIdentityResult: (token: string, transactionId: string) =>
    external<LandingView>(`/api/integration/landing/${token}/identity/result`, { transactionId }),
  landingConsent: (token: string, creditInquiry: boolean) =>
    external<LandingView>(`/api/integration/landing/${token}/consents`, { creditInquiry }),
  landingUpload: (token: string, docType: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<LandingView>('POST', `/api/integration/landing/${token}/documents/${docType}`, form)
  },
  landingComplete: (token: string) => external<{ status: string }>(`/api/integration/landing/${token}/complete`, {}),

  // ---------------------------------------------------------------- Mock 기관 화면
  mockIdentity: (tx: string) => request<{ transactionId: string }>('GET', `/api/integration/mock-identity/${tx}`),
  mockIdentityComplete: (tx: string, body: { success: boolean; name?: string; birthDate?: string }) =>
    external<{ redirectUrl: string }>(`/api/integration/mock-identity/${tx}`, body),

  // ---------------------------------------------------------------- Mock 플랫폼 (플랫폼 쪽 클라이언트 흉내)
  platformLimit: (key: string, body: Record<string, unknown>) =>
    external<LimitInquiryResult>('/api/integration/platform/MOCKPLAT/limit-inquiries', body, { 'X-Platform-Key': key }),
  platformApply: (key: string, body: Record<string, unknown>) =>
    external<IntakeResult>('/api/integration/platform/MOCKPLAT/applications', body, { 'X-Platform-Key': key }),
  platformNotices: async (key: string) => {
    const res = await fetch('/api/integration/mock-platform/notices', { headers: { 'X-Platform-Key': key } })
    if (!res.ok) throw new Error(`통보 조회 실패 (${res.status})`)
    return (await res.json()) as {
      receivedAt: string
      delivered: boolean
      notice: { platformRef: string; applicationNo: string; result: string; approvedAmount: number | null }
    }[]
  },
}
