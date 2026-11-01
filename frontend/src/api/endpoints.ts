import { downloadXlsx } from './download'
import { api, qs } from './client'
import type { DailyReport, JournalReport, MonthEnd, Provision, ReportKind, RollRate } from './reportTypes'
import type {
  AccessLogRow,
  AccountRef,
  BulkJob,
  BusinessDateInfo,
  ClosingResult,
  Correction,
  CorrectionType,
  CustomerView,
  History,
  LedgerDto,
  LoanDetail,
  LoanListItem,
  Me,
  PageResponse,
  PaymentRow,
  PaymentView,
  Product,
  Quote,
  WorkoutQuote,
  SearchHit,
  LimitationInfo,
  Contacts,
  ContactChannel,
  ContactDecision,
  PaymentPromise,
  CollectionAlert,
  ContactDeferral,
  OutboundMessage,
  WorkoutRequest,
  Schedule,
  StaffRow,
  UnmatchedDeposit,
} from './types'

/** TanStack Query 키 — 계약 하나의 모든 쿼리는 ['loan', id]로 시작해 한 번에 무효화할 수 있다 */
export const queryKeys = {
  me: ['me'] as const,
  businessDate: ['business-date'] as const,
  loans: (params: LoanSearch) => ['loans', params] as const,
  statusCounts: ['loans', 'status-counts'] as const,
  loan: (id: number) => ['loan', id] as const,
  loanSchedule: (id: number) => ['loan', id, 'schedule'] as const,
  loanLedger: (id: number) => ['loan', id, 'ledger'] as const,
  loanHistory: (id: number) => ['loan', id, 'history'] as const,
  loanPayments: (id: number) => ['loan', id, 'payments'] as const,
  quote: (id: number, principal: number | null) => ['loan', id, 'quote', principal] as const,
  workoutQuote: (id: number, body: Omit<WorkoutRequest, 'reason'>) => ['loan', id, 'workout-quote', body] as const,
  loanLimitation: (id: number) => ['loan', id, 'limitation'] as const,
  loanContacts: (id: number) => ['loan', id, 'contacts'] as const,
  loanPromises: (id: number) => ['loan', id, 'promises'] as const,
  alerts: ['collection-alerts'] as const,
  loanDeferrals: (id: number) => ['loan', id, 'deferrals'] as const,
  loanMessages: (id: number) => ['loan', id, 'messages'] as const,
  unmatched: ['unmatched'] as const,
  corrections: (status: string) => ['corrections', status] as const,
  bulkJob: (id: number) => ['bulk', id] as const,
  closings: ['closings'] as const,
  staff: ['admin', 'staff'] as const,
  accessLogs: ['admin', 'access-logs'] as const,
  products: ['products'] as const,
  reportDaily: (date: string) => ['reports', 'daily', date] as const,
  reportJournal: (date: string) => ['reports', 'journal', date] as const,
  reportMonths: ['reports', 'months'] as const,
  reportRollRate: (month: string) => ['reports', 'roll-rate', month] as const,
  reportProvision: (month: string) => ['reports', 'provision', month] as const,
}

export interface LoanSearch {
  status?: string
  q?: string
  page?: number
  size?: number
}

export interface PaymentRequest {
  accountId?: number
  accountNo?: string
  bankDate: string
  amount: number
  depositorName?: string
  bankTxId?: string
  channel?: string
  prepayment?: boolean
  prepaymentPrincipal?: number | null
}

export interface CorrectionRequest {
  type: CorrectionType
  paymentId: number
  targetAccountNo?: string
  correctedAmount?: number
  reason: string
}

export interface NewCustomer {
  name: string
  residentNo: string
  birthDate: string
  phone?: string
  address?: string
}

export interface OpenLoanRequest {
  customerId: number
  productCode: string
  principal: number
  rate: number
  termMonths: number
  graceMonths: number
  dueDay: number
  disbursedOn?: string
  assignedStaffId?: number | null
}

export const endpoints = {
  csrf: () => api.get<unknown>('/api/auth/csrf'),
  login: (loginId: string, password: string) => api.post<Me>('/api/auth/login', { loginId, password }),
  me: () => api.get<Me>('/api/auth/me'),
  logout: () => api.post<void>('/api/auth/logout'),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<void>('/api/auth/password', { currentPassword, newPassword }),
  businessDate: () => api.get<BusinessDateInfo>('/api/operation/business-date'),

  loans: (p: LoanSearch) => api.get<PageResponse<LoanListItem>>(`/api/loans${qs({ ...p })}`),
  statusCounts: () => api.get<Record<string, number>>('/api/loans/status-counts'),
  loan: (id: number) => api.get<LoanDetail>(`/api/loans/${id}`),
  schedule: (id: number) => api.get<Schedule>(`/api/loans/${id}/schedule`),
  ledger: (id: number) => api.get<LedgerDto[]>(`/api/loans/${id}/ledger`),
  history: (id: number) => api.get<History>(`/api/loans/${id}/history`),
  quote: (id: number, principal: number | null) =>
    api.get<Quote>(`/api/loans/${id}/quote${qs({ principal })}`),
  changeRate: (id: number, body: { rate: number; validFrom: string; reason: string }) =>
    api.post<AccountRef>(`/api/loans/${id}/rate-changes`, body),
  changeDueDay: (id: number, body: { dueDay: number; reason: string }) =>
    api.post<AccountRef>(`/api/loans/${id}/due-day-changes`, body),
  workoutQuote: (id: number, body: Omit<WorkoutRequest, 'reason'>) =>
    api.get<WorkoutQuote>(`/api/loans/${id}/workout-quote${qs({ ...body })}`),
  workout: (id: number, body: WorkoutRequest) => api.post<AccountRef>(`/api/loans/${id}/workouts`, body),
  search: (q: string) => api.get<SearchHit[]>(`/api/loans/search${qs({ q })}`),
  limitation: (id: number) => api.get<LimitationInfo>(`/api/loans/${id}/limitation`),
  recordLimitationEvent: (id: number, body: { type: string; date: string; memo?: string }) =>
    api.post<LimitationInfo>(`/api/loans/${id}/limitation-events`, body),
  contacts: (id: number) => api.get<Contacts>(`/api/loans/${id}/contacts`),
  recordContact: (id: number, body: { channel: ContactChannel; memo?: string }) =>
    api.post<ContactDecision>(`/api/loans/${id}/contacts`, body),
  promises: (id: number) => api.get<PaymentPromise[]>(`/api/loans/${id}/promises`),
  registerPromise: (
    id: number,
    body: { firstMonth: string; day: number; amount: number; months: number; memo?: string },
  ) => api.post<PaymentPromise>(`/api/loans/${id}/promises`, body),
  deferrals: (id: number) => api.get<ContactDeferral[]>(`/api/loans/${id}/deferrals`),
  registerDeferral: (id: number, body: { startDate: string; endDate: string; reason: string }) =>
    api.post<ContactDeferral>(`/api/loans/${id}/deferrals`, body),
  extendDeferral: (id: number, deferralId: number, endDate: string) =>
    api.post<ContactDeferral>(`/api/loans/${id}/deferrals/${deferralId}/extend`, { endDate }),
  endDeferral: (id: number, deferralId: number) =>
    api.post<ContactDeferral>(`/api/loans/${id}/deferrals/${deferralId}/end`),
  messages: (id: number) => api.get<OutboundMessage[]>(`/api/loans/${id}/messages`),
  dispatchMessages: () => api.post<{ sent: number; skipped: number }>('/api/collection/messages/dispatch'),
  alerts: (unreadOnly: boolean) => api.get<CollectionAlert[]>(`/api/collection/alerts${qs({ unreadOnly })}`),
  readAlert: (id: number) => api.post<CollectionAlert>(`/api/collection/alerts/${id}/read`),
  openLoan: (body: OpenLoanRequest) => api.post<AccountRef>('/api/loans', body),
  products: () => api.get<Product[]>('/api/products'),
  registerCustomer: (body: NewCustomer) => api.post<CustomerView>('/api/customers', body),
  revealResidentNo: (customerId: number, reason: string) =>
    api.post<{ residentNo: string }>(`/api/customers/${customerId}/reveal`, { reason }),

  payments: (accountId: number) => api.get<PaymentRow[]>(`/api/payments${qs({ accountId })}`),
  registerPayment: (body: PaymentRequest) => api.post<PaymentView>('/api/payments', body),
  unmatched: () => api.get<UnmatchedDeposit[]>('/api/unmatched-deposits?status=OPEN'),
  match: (id: number, accountNo: string) =>
    api.post<PaymentView>(`/api/unmatched-deposits/${id}/match`, { accountNo }),
  requestCorrection: (body: CorrectionRequest) => api.post<Correction>('/api/payment-corrections', body),
  corrections: (status: string) => api.get<Correction[]>(`/api/payment-corrections${qs({ status })}`),
  approveCorrection: (id: number, approve: boolean) =>
    api.post<Correction>(`/api/payment-corrections/${id}/approval`, { approve }),

  uploadBulk: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload<BulkJob>('/api/payments/bulk', form)
  },
  bulkJob: (id: number) => api.get<BulkJob>(`/api/payments/bulk/${id}`),
  executeBulk: (id: number) => api.post<BulkJob>(`/api/payments/bulk/${id}/execute`),
  bulkTemplateUrl: '/api/payments/bulk/template',
  bulkFailuresUrl: (id: number) => `/api/payments/bulk/${id}/failures`,

  close: () => api.post<ClosingResult>('/api/operation/closings'),
  closings: () => api.get<ClosingResult[]>('/api/operation/closings'),
  staff: () => api.get<StaffRow[]>('/api/admin/staff'),
  unlockStaff: (id: number) => api.post<void>(`/api/admin/staff/${id}/unlock`),
  accessLogs: () => api.get<AccessLogRow[]>('/api/admin/access-logs'),

  // 보고서 (R65~R68). date·month가 빈 값이면 서버가 현재 업무일자 기준
  reportDaily: (date: string) => api.get<DailyReport>(`/api/reports/daily${qs({ date })}`),
  reportJournal: (date: string) => api.get<JournalReport>(`/api/reports/journal${qs({ date })}`),
  reportMonths: () => api.get<MonthEnd[]>('/api/reports/months'),
  reportRollRate: (month: string) => api.get<RollRate>(`/api/reports/roll-rate${qs({ month })}`),
  reportProvision: (month: string) => api.get<Provision>(`/api/reports/provision${qs({ month })}`),
  exportLoans: (body: { status?: string; q?: string; reason: string }) =>
    downloadXlsx('/api/exports/loans', body, 'loans.xlsx'),
  exportReport: (body: { kind: ReportKind; date?: string; month?: string; reason: string }) =>
    downloadXlsx('/api/exports/reports', body, 'report.xlsx'),
}
