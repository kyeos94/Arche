import { z } from 'zod'

/** 날짜 입력 yyyy-MM-dd */
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'yyyy-MM-dd 형식으로 입력하세요')

/** 금액 입력은 쉼표를 허용하고 원 단위 정수로 바꾼다 */
export const money = z
  .union([z.string(), z.number()])
  .transform((v) => (typeof v === 'number' ? v : Number(v.replace(/[^0-9]/g, ''))))
  .pipe(z.number({ error: '금액을 입력하세요' }).int().positive('0보다 커야 합니다'))

export const DUE_DAYS = [5, 10, 15, 20, 25] as const

export const dueDay = z.coerce
  .number()
  .refine((v) => (DUE_DAYS as readonly number[]).includes(v), '약정일은 5·10·15·20·25일 중에서 고릅니다')

const reason = z.string().trim().min(1, '사유를 입력하세요').max(200)

export const loginSchema = z.object({
  loginId: z.string().trim().min(1, '아이디를 입력하세요'),
  password: z.string().min(1, '비밀번호를 입력하세요'),
})

export const passwordChangeSchema = z
  .object({
    currentPassword: z.string().min(1, '현재 비밀번호를 입력하세요'),
    newPassword: z.string().min(10, '10자 이상 입력하세요'),
    confirm: z.string(),
  })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: '새 비밀번호가 일치하지 않습니다' })

export const PAYMENT_CHANNELS = [
  'BANK_TRANSFER',
  'VIRTUAL_ACCOUNT',
  'AUTO_DEBIT',
  'CASH',
  'AUCTION_DISTRIBUTION',
  'COURT_DEPOSIT',
] as const

/** 입금자 구분 (R49) */
export const PAYER_TYPES = ['CUSTOMER', 'GUARANTOR', 'THIRD_PARTY'] as const

/** 보증인·제3자 입금은 입금자 이름 필수 (R49) */
function payerRules(v: { payerType: string; payerName?: string }, ctx: z.RefinementCtx) {
  if (v.payerType !== 'CUSTOMER' && !v.payerName?.trim()) {
    ctx.addIssue({ code: 'custom', path: ['payerName'], message: '보증인·제3자 입금은 입금자 이름을 입력하세요' })
  }
}

export const paymentSchema = z
  .object({
    bankDate: date,
    amount: money,
    depositorName: z.string().max(50).optional(),
    bankTxId: z.string().max(40).optional(),
    prepayment: z.boolean().default(false),
    prepaymentPrincipal: z.string().optional(),
    channel: z.enum(PAYMENT_CHANNELS).default('BANK_TRANSFER'),
    payerType: z.enum(PAYER_TYPES).default('CUSTOMER'),
    payerName: z.string().max(50).optional(),
    payerRef: z.string().max(100).optional(),
    legalActionId: z.string().optional(),
  })
  .superRefine((v, ctx) => payerRules(v, ctx))
  .superRefine((v, ctx) => {
    const court = v.channel === 'AUCTION_DISTRIBUTION' || v.channel === 'COURT_DEPOSIT'
    if (court && v.payerType !== 'CUSTOMER') {
      ctx.addIssue({ code: 'custom', path: ['payerType'], message: '배당금·공탁금은 입금자 구분이 본인입니다 (R52)' })
    }
    if (court && v.prepayment) {
      ctx.addIssue({ code: 'custom', path: ['prepayment'], message: '배당금·공탁금에는 중도상환 요청을 함께 받지 않습니다' })
    }
  })
  .transform((v) => {
    const court = v.channel === 'AUCTION_DISTRIBUTION' || v.channel === 'COURT_DEPOSIT'
    return {
      ...v,
      prepaymentPrincipal:
        v.prepayment && v.prepaymentPrincipal && v.prepaymentPrincipal.trim() !== ''
          ? Number(v.prepaymentPrincipal.replace(/[^0-9]/g, ''))
          : null,
      legalActionId: court && v.legalActionId ? Number(v.legalActionId) : null,
    }
  })

export const unmatchedPaymentSchema = z.object({
  bankDate: date,
  amount: money,
  depositorName: z.string().max(50).optional(),
  bankTxId: z.string().max(40).optional(),
})

export const rateChangeSchema = z.object({
  rate: z.coerce.number().positive('이율을 입력하세요').max(20, '법정 최고이자율(20%)을 넘을 수 없습니다'),
  validFrom: date,
  reason,
})

export const dueDayChangeSchema = z.object({ dueDay, reason })

/** 분할납부 약속 (R18): 매월 n일에 m원 */
export const promiseSchema = z.object({
  firstMonth: z.string().regex(/^\d{4}-\d{2}$/, 'yyyy-MM 형식으로 입력하세요'),
  day: z.coerce.number().int().min(1, '1~31일').max(31, '1~31일'),
  amount: money,
  months: z.coerce.number().int().min(1, '1회 이상').max(60, '최대 60회'),
  memo: z.string().max(200).optional(),
})

/** 감면액: 비워 두면 0 */
const waiver = z
  .union([z.string(), z.number()])
  .transform((v) => (typeof v === 'number' ? v : Number(v.replace(/[^0-9]/g, '') || '0')))
  .pipe(z.number().int().min(0, '0 이상이어야 합니다'))

/** 채무조정 (R9): 분할 최대 60회 */
export const workoutSchema = z.object({
  waiveLate: waiver,
  waiveNormal: waiver,
  waivePrincipal: waiver,
  installments: z.coerce.number().int().min(1, '1회 이상').max(60, '분할은 최대 60회입니다'),
  dueDay,
  reason,
})

export const correctionSchema = z
  .object({
    type: z.enum(['CANCEL', 'MOVE', 'AMOUNT_FIX']),
    targetAccountNo: z.string().optional(),
    correctedAmount: z.string().optional(),
    reason,
  })
  .superRefine((v, ctx) => {
    if (v.type === 'MOVE' && !v.targetAccountNo?.trim()) {
      ctx.addIssue({ code: 'custom', path: ['targetAccountNo'], message: '옮길 계약번호를 입력하세요' })
    }
    if (v.type === 'AMOUNT_FIX') {
      const n = Number((v.correctedAmount ?? '').replace(/[^0-9]/g, ''))
      if (!Number.isInteger(n) || n <= 0) {
        ctx.addIssue({ code: 'custom', path: ['correctedAmount'], message: '올바른 금액을 입력하세요' })
      }
    }
  })

export const residentNo = z.string().regex(/^\d{6}-?\d{7}$/, '주민등록번호 13자리를 입력하세요')

export const customerSchema = z.object({
  name: z.string().trim().min(1, '이름을 입력하세요').max(50),
  residentNo,
  birthDate: date,
  phone: z.string().optional(),
  address: z.string().optional(),
})

export const openLoanSchema = z.object({
  productCode: z.enum(['P1', 'P2', 'P3', 'P4', 'P5']),
  principal: money,
  rate: z.coerce.number().positive().max(20),
  termMonths: z.coerce.number().int().positive('기간을 입력하세요'),
  graceMonths: z.coerce.number().int().min(0),
  dueDay,
  disbursedOn: z.union([date, z.literal('')]),
  assignedStaffId: z.string().optional(),
})

/** 일괄 입금 배분 (R47): 새 입금이면 입금일·입금액 필수, 미확인 입금이면 그 금액을 쓴다 */
export const splitSchema = z
  .object({
    method: z.enum(['OLDEST_DELINQUENCY_FIRST', 'PROPORTIONAL', 'MANUAL']),
    bankDate: z.string().optional(),
    amount: z.string().optional(),
    depositorName: z.string().max(50).optional(),
    bankTxId: z.string().max(40).optional(),
    payerType: z.enum(PAYER_TYPES).default('CUSTOMER'),
    payerName: z.string().max(50).optional(),
    payerRef: z.string().max(100).optional(),
    fromUnmatched: z.boolean().default(false),
  })
  .superRefine((v, ctx) => payerRules(v, ctx))
  .superRefine((v, ctx) => {
    if (v.fromUnmatched) return
    if (!v.bankDate || !/^\d{4}-\d{2}-\d{2}$/.test(v.bankDate)) {
      ctx.addIssue({ code: 'custom', path: ['bankDate'], message: 'yyyy-MM-dd 형식으로 입력하세요' })
    }
    const n = Number((v.amount ?? '').replace(/[^0-9]/g, ''))
    if (!Number.isInteger(n) || n <= 0) {
      ctx.addIssue({ code: 'custom', path: ['amount'], message: '0보다 커야 합니다' })
    }
  })
  .transform((v) => ({ ...v, amount: v.amount ? Number(v.amount.replace(/[^0-9]/g, '')) : undefined }))

/** 법조치 등록 (R51) */
export const legalActionSchema = z.object({
  type: z.enum(['PAYMENT_ORDER', 'SEIZURE', 'AUCTION']),
  filedOn: date,
  courtName: z.string().max(100).optional(),
  caseRef: z.string().max(50).optional(),
  targetDesc: z.string().max(200).optional(),
  claimAmount: z
    .string()
    .optional()
    .transform((v) => (v && v.trim() !== '' ? Number(v.replace(/[^0-9]/g, '')) : null)),
  memo: z.string().max(500).optional(),
})

/** 법조치 단계 변경 (R51) */
export const legalStageSchema = z.object({
  stage: z.string().min(1, '단계를 고르세요'),
  date,
  memo: z.string().max(200).optional(),
})

/** 법조치 비용 (R52) */
export const legalCostSchema = z.object({
  costType: z.enum(['COURT_FEE', 'SERVICE_FEE', 'EXECUTION_FEE', 'AGENT_FEE', 'OTHER']),
  amount: money,
  incurredOn: date,
  memo: z.string().max(200).optional(),
})

export type LoginForm = z.infer<typeof loginSchema>
export type PasswordChangeForm = z.infer<typeof passwordChangeSchema>
export type CorrectionForm = z.infer<typeof correctionSchema>
