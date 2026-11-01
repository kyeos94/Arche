/** 보고서 API 응답 (backend reporting 모듈, 정책서 R65~R68) */

export interface ReportLine {
  section: string
  sectionLabel: string
  item: string
  label: string
  count: number
  amount: number
}

export interface DailyReport {
  businessDate: string
  /** 마감 스냅샷이면 true, 미리보기면 false (R65) */
  closed: boolean
  balancesAvailable: boolean
  snapshotAt: string | null
  journalDebit: number
  journalCredit: number
  journalBalanced: boolean
  unmappedCount: number
  lines: ReportLine[]
}

export interface JournalLine {
  entryId: number
  accountNo: string
  postingType: string
  postingLabel: string
  reversal: boolean
  accountCode: string
  accountName: string
  debit: number
  credit: number
  memo: string
  unmapped: boolean
}

export interface AccountTotal {
  accountCode: string
  accountName: string
  category: string
  debit: number
  credit: number
}

export interface JournalReport {
  businessDate: string
  closed: boolean
  debit: number
  credit: number
  balanced: boolean
  matchesSnapshot: boolean
  totals: AccountTotal[]
  unmapped: { postingType: string; count: number; amount: number }[]
  lines: JournalLine[]
}

export interface MonthEnd {
  month: string
  asOf: string
  createdAt: string
}

export interface RollCell {
  to: string
  toLabel: string
  count: number
  balance: number
  countRate: number
  balanceRate: number
}

export interface RollRow {
  from: string
  fromLabel: string
  count: number
  balance: number
  cells: RollCell[]
  worsenRate: number
  cureRate: number
}

export interface RollRate {
  month: string
  previousMonth: string
  previousAvailable: boolean
  currentClosed: boolean
  asOf: string
  columns: string[]
  rows: RollRow[]
}

export interface ProvisionRow {
  bucket: string
  label: string
  count: number
  claim: number
  rate: number
  provision: number
}

export interface Provision {
  month: string | null
  asOf: string
  closed: boolean
  count: number
  claim: number
  provision: number
  rows: ProvisionRow[]
}

export type ReportKind = 'DAILY' | 'JOURNAL' | 'ROLL_RATE' | 'PROVISION'
