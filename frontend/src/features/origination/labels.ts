import { z } from 'zod'
import { DUE_DAYS, money } from '@/lib/schemas'
import type { ApplicationStatus } from '@/api/origination'

/** 신청 상태 (guide 2.2) — 계약 상태 라벨(lib/format)과 같은 코드가 다른 뜻이라 따로 둔다 */
export const APPLICATION_STATUS_LABEL: Record<ApplicationStatus, string> = {
  RECEIVED: '접수',
  PRE_SCREENING: '사전심사',
  ASSIGNED: '배정',
  UNDERWRITING: '심사 중',
  APPROVED: '승인',
  REJECTED: '거절',
  WITHDRAWN: '철회',
  CONTRACTED: '약정',
  DISBURSED: '실행',
}

export const APPLICATION_STATUSES = Object.keys(APPLICATION_STATUS_LABEL) as ApplicationStatus[]

export const APPLICATION_STATUS_TONE: Record<ApplicationStatus, string> = {
  RECEIVED: 'bg-sky-50 text-sky-700 border-sky-200',
  PRE_SCREENING: 'bg-sky-50 text-sky-700 border-sky-200',
  ASSIGNED: 'bg-amber-50 text-amber-800 border-amber-200',
  UNDERWRITING: 'bg-amber-50 text-amber-800 border-amber-200',
  APPROVED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  CONTRACTED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  DISBURSED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  REJECTED: 'bg-red-50 text-red-700 border-red-200',
  WITHDRAWN: 'bg-slate-100 text-slate-600 border-slate-200',
}

export const CHANNEL_LABEL: Record<string, string> = {
  STAFF: '직원 입력',
  BROKER: '중개업자',
  PLATFORM: '비교 플랫폼',
  LANDING: '고객 링크',
}

export const OUTCOME_LABEL: Record<string, string> = { PASS: '통과', REJECT: '거절', SKIPPED: '미실시' }
export const OUTCOME_TONE: Record<string, string> = {
  PASS: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  REJECT: 'bg-red-50 text-red-700 border-red-200',
  SKIPPED: 'bg-slate-100 text-slate-600 border-slate-200',
}

export const DOCUMENT_STATUS_LABEL: Record<string, string> = {
  REQUESTED: '요청',
  RECEIVED: '접수',
  VERIFIED: '확인',
  REJECTED: '보완 요청',
}
export const DOCUMENT_STATUS_TONE: Record<string, string> = {
  REQUESTED: 'bg-amber-50 text-amber-800 border-amber-200',
  RECEIVED: 'bg-sky-50 text-sky-700 border-sky-200',
  VERIFIED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  REJECTED: 'bg-red-50 text-red-700 border-red-200',
}

export const VIA_LABEL: Record<string, string> = {
  UPLOAD: '직원 업로드',
  PAPER: '서면 원본',
  LANDING: '고객 링크',
  BROKER: '중개업자 API',
}

export const LANDING_STATUS_LABEL: Record<string, string> = {
  ISSUED: '발급',
  VERIFIED: '본인 확인',
  COMPLETED: '제출 완료',
  FAILED: '인증 실패로 폐기',
  REVOKED: '재발급으로 무효',
}

export const REJECT_REASON_LABEL: Record<string, string> = {
  MIN_AGE: '만 19세 미만',
  DUPLICATE_APPLICATION: '중복 신청',
  RECENT_REJECTION: '최근 30일 거절 이력',
  INTERNAL_HISTORY: '내부 불량 이력',
  CREDIT_BUREAU: '신용조회 현재 연체',
  UNDERWRITING_DECLINE: '심사 거절',
}

/** 상태별로 직원이 누를 수 있는 다음 단계 (승인·실행은 전용 화면, ApplicationStatus 전이표와 같음) */
export function nextSteps(status: ApplicationStatus): ApplicationStatus[] {
  switch (status) {
    case 'RECEIVED':
      return ['PRE_SCREENING', 'WITHDRAWN']
    case 'PRE_SCREENING':
      return ['ASSIGNED', 'REJECTED', 'WITHDRAWN']
    case 'ASSIGNED':
      return ['UNDERWRITING', 'WITHDRAWN']
    case 'UNDERWRITING':
      return ['REJECTED', 'WITHDRAWN']
    case 'APPROVED':
      return ['CONTRACTED', 'WITHDRAWN']
    default:
      return []
  }
}

/** 심사 승인 전결: 1,000만원 이하 심사역, 초과는 심사팀장 (정책서 7장) */
export const APPROVAL_THRESHOLD = 10_000_000

export function approvalPermission(amount: number): string {
  return amount > APPROVAL_THRESHOLD ? 'application.approve.large' : 'application.approve.small'
}

export const receiveSchema = z.object({
  productCode: z.string().min(1, '상품을 고르세요'),
  requestedAmount: money,
  termMonths: z.coerce.number().int().positive('기간을 입력하세요'),
  graceMonths: z.coerce.number().int().min(0),
  dueDay: z.coerce
    .number()
    .refine((v) => (DUE_DAYS as readonly number[]).includes(v), '약정일은 5·10·15·20·25일 중에서 고릅니다'),
  creditInquiryConsent: z.boolean().default(false),
  consentEvidence: z.string().max(100).optional(),
})

export const approvalSchema = z.object({
  amount: money,
  rate: z.coerce.number().positive('금리를 입력하세요').max(20, '법정 최고이자율(20%)을 넘을 수 없습니다'),
})

export const brokerSchema = z.object({
  name: z.string().trim().min(1, '이름을 입력하세요').max(100),
  registrationNo: z.string().trim().min(1, '등록 번호를 입력하세요').max(30),
  allowedIps: z.string().max(200).optional(),
  rate: z.coerce.number().min(0, '0 이상').max(4, '수수료율 상한은 4.00%입니다 (R56)'),
})

export const brokerRateSchema = z.object({
  rate: z.coerce.number().min(0, '0 이상').max(4, '수수료율 상한은 4.00%입니다 (R56)'),
  validFrom: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'yyyy-MM-dd 형식으로 입력하세요'),
  reason: z.string().trim().min(1, '사유를 입력하세요').max(200),
})

export function formatBytes(n: number | null | undefined): string {
  if (n == null) return '-'
  if (n < 1024) return `${n}B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`
  return `${(n / 1024 / 1024).toFixed(1)}MB`
}
