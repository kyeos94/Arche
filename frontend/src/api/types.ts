// 백엔드 DTO (backend/src/main/java/dev/arche/**/internal/*Controller.java의 record와 같은 모양)

export interface Me {
  id: number
  loginId: string
  name: string
  role: string
  roleLabel: string
  teamId: number | null
  permissions: string[]
  passwordChangeRequired: boolean
  passwordExpiresInDays: number
}

export interface BusinessDateInfo {
  businessDate: string
  lastClosedAt: string | null
}

export type AccountStatus = 'NORMAL' | 'DELINQUENT' | 'ACCELERATED' | 'PAID_OFF'

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface LoanListItem {
  id: number
  accountNo: string
  customerName: string
  productCode: string
  status: string
  principalBalance: number
  payoffAmount: number
  delinquencyDays: number
  nextDueDate: string | null
  assignedStaffId: number | null
}

export interface CustomerView {
  id: number
  customerNo: string
  name: string
  residentNoMasked: string
  birthDate: string
  phoneMasked: string | null
}

export interface AcquisitionInfo {
  acquiredOn: string
  acquisitionPrice: number
  seller: string
  appliedRate: number
  interestMode: string
  recoveryRate: number
  daysSinceLastPayment: number | null
}

export interface LoanDetail {
  id: number
  accountNo: string
  customer: CustomerView
  productCode: string
  productName: string
  repaymentMethod: string
  origin: string
  status: string
  initialPrincipal: number
  disbursedOn: string
  maturityDate: string | null
  termMonths: number
  graceMonths: number
  currentRate: number | null
  lateRate: number | null
  currentDueDay: number | null
  installmentAmount: number | null
  principalBalance: number
  suspenseBalance: number
  costBalance: number
  preAcqInterest: number
  outstandingNormalInterest: number
  outstandingLateInterest: number
  payoffAmount: number
  prepaymentFeeToday: number
  delinquencyDays: number
  delinquentSince: string | null
  nextDueDate: string | null
  acceleratedOn: string | null
  paidOffOn: string | null
  lastSettledOn: string
  asOf: string
  businessDate: string
  virtualAccountNo: string
  totalPaid: number
  refundPending: number
  assignedStaffId: number | null
  limitationCompletionDate: string | null
  acquisition: AcquisitionInfo | null
}

export interface InstallmentDto {
  scheduleVersion: number
  seq: number
  periodStart: string
  dueDate: string
  graceEnd: string
  principalDue: number
  plannedInterest: number
  plannedPayment: number
  principalPaid: number
  status: string
}

export interface Schedule {
  currentVersion: number
  current: InstallmentDto[]
  previous: Record<string, InstallmentDto[]>
}

export interface AllocationDto {
  installmentSeq: number | null
  component: string
  amount: number
}

export interface LedgerDto {
  id: number
  type: string
  valueDate: string
  businessDate: string
  postedAt: string
  sourceRef: string
  amount: number
  principal: number
  normalInterest: number
  lateInterest: number
  cost: number
  fee: number
  preAcqInterest: number
  suspense: number
  refund: number
  normalAccrued: number
  lateAccrued: number
  principalBalanceAfter: number
  paymentId: number | null
  correctionId: number | null
  reversalOfId: number | null
  superseded: boolean
  allocations: AllocationDto[]
}

export interface History {
  rateTerms: { rate: number; validFrom: string; validTo: string | null; reason: string; recordedAt: string }[]
  dueDayTerms: {
    dueDay: number
    validFrom: string
    validTo: string | null
    requestedOn: string
    reason: string
    recordedAt: string
  }[]
  notices: {
    reasonDate: string
    sentOn: string
    deemedArrivalOn: string
    actualArrivalOn: string | null
    accelerationDate: string
    status: string
    closedOn: string | null
  }[]
  events: {
    id: number
    ref: string
    type: string
    eventDate: string
    amount: number | null
    newDueDay: number | null
    paymentId: number | null
    status: string
    cancelledOn: string | null
    reason: string | null
    createdBy: string
    createdAt: string
  }[]
}

/** 상단 통합 검색 결과 */
export interface SearchHit {
  id: number
  accountNo: string
  customerName: string
  residentNoMasked: string
  phoneMasked: string | null
  productCode: string
  status: string
  principalBalance: number
  matchedBy: string
}

/** 소멸시효 (G13) */
export interface LimitationInfo {
  start: string | null
  startReason: string | null
  events: { date: string; type: string; memo: string | null; counted: boolean }[]
  completionDate: string | null
  alertDates: string[]
  completed: boolean
}

export type ContactChannel = 'CALL' | 'SMS' | 'VISIT' | 'STATUTORY_NOTICE'

/** 추심 연락 (G14) */
export interface ContactRow {
  id: number
  contactDate: string
  contactedAt: string
  channel: ContactChannel
  result: 'ALLOWED' | 'SCHEDULED' | 'BLOCKED'
  countInWindow: number
  reason: string | null
  memo: string | null
  createdBy: string
}

export interface Contacts {
  businessDate: string
  remainingToday: number
  contacts: ContactRow[]
}

export interface ContactDecision {
  id: number
  contactDate: string
  result: string
  countInWindow: number
  reason: string | null
}

/** 분할납부 약속 (G19) */
export interface PromiseJudgment {
  index: number
  promiseDate: string
  deadline: string
  paid: number
  fulfilled: boolean | null
  consecutiveMisses: number
  action: string
}

export interface PaymentPromise {
  id: number
  registeredOn: string
  firstMonth: string
  day: number
  amount: number
  months: number
  status: string
  terminatedOn: string | null
  legalReview: boolean
  memo: string | null
  judgments: PromiseJudgment[]
}

/** 연락 유예 (정책서 6장) */
export interface ContactDeferral {
  id: number
  startDate: string
  endDate: string
  originalEnd: string
  extended: boolean
  reason: string
  status: 'ACTIVE' | 'ENDED'
  endedOn: string | null
  activeToday: boolean
}

/** 자동 문자 (정책서 6장) */
export interface OutboundMessage {
  id: number
  accountId: number
  type: string
  body: string
  scheduledDate: string
  status: 'SCHEDULED' | 'SENT' | 'SKIPPED'
  skipReason: string | null
  sentAt: string | null
}

export interface CollectionAlert {
  id: number
  accountId: number
  type: string
  message: string
  alertDate: string
  readAt: string | null
}

/** 채무조정 견적 (R9·R23) */
export interface WorkoutQuote {
  date: string
  cost: number
  lateInterest: number
  normalInterest: number
  principal: number
  waiveLate: number
  waiveNormal: number
  waivePrincipal: number
  requiredPayment: number
  paidToday: number
  remainingPayment: number
  newPrincipal: number
  installmentAmount: number | null
  schedulePreview: InstallmentDto[]
}

export interface WorkoutRequest {
  waiveLate: number
  waiveNormal: number
  waivePrincipal: number
  installments: number
  dueDay: number
  reason: string
}

export interface Quote {
  date: string
  cost: number
  lateInterest: number
  normalInterest: number
  overduePrincipal: number
  principal: number
  fee: number
  suspense: number
  total: number
  full: boolean
}

export interface PaymentView {
  id: number
  accountId: number | null
  channel: string
  valueDate: string
  businessDate: string
  amount: number
  depositorName: string | null
  bankTxId: string
  status: string
}

export interface PaymentRow extends PaymentView {
  processedBy: number | null
  cancellableToday: boolean
}

export interface UnmatchedDeposit {
  id: number
  paymentId: number
  status: string
  valueDate: string
  businessDate: string
  amount: number
  depositorName: string | null
  bankTxId: string
  matchedAccountId: number | null
  matchedAt: string | null
}

export type CorrectionType = 'CANCEL' | 'MOVE' | 'AMOUNT_FIX'

export interface Correction {
  id: number
  correctionNo: string
  type: CorrectionType
  originalPaymentId: number
  newPaymentId: number | null
  targetAccountId: number | null
  correctedAmount: number | null
  reason: string
  afterClose: boolean
  requestedBy: number
  approvedBy: number | null
  status: string
  requestedOn: string
  appliedOn: string | null
}

export interface BulkRow {
  rowNo: number
  status: 'VALID' | 'INVALID' | 'SUCCEEDED' | 'FAILED'
  message: string | null
  payload: string
}

export interface BulkJob {
  id: number
  fileName: string
  status: 'VALIDATED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalRows: number
  validRows: number
  successRows: number
  failedRows: number
  rows: BulkRow[]
}

export interface ClosingResult {
  businessDate: string
  closedAt: string
  paymentCount: number
  paymentTotal: number
  correctionTotal: number
  unmatchedTotal: number
  accountsJudged: number
  newNotices: number
  accelerations: number
  mismatches: number
  nextBusinessDate?: string
}

export interface StaffRow {
  id: number
  loginId: string
  name: string
  role: string
  roleLabel: string
  teamId: number | null
  locked: boolean
  failedAttempts: number
}

export interface AccessLogRow {
  staffId: number | null
  loginId: string
  accessedAt: string
  ip: string | null
  action: string
  targetType: string | null
  targetId: string | null
  reason: string | null
}

export interface Product {
  id: number
  code: string
  name: string
  repaymentMethod: string
  defaultRate: number
  minAmount: number
  maxAmount: number
  minTermMonths: number
  maxTermMonths: number
  minGraceMonths: number
  maxGraceMonths: number
}

export interface AccountRef {
  id: number
  accountNo: string
  status: string
  repaymentMethod: string
  assignedStaffId: number | null
  paidOffOn: string | null
  principalBalance: number
  payoffAmount: number
}
