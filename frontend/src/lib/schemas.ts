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

export const paymentSchema = z
  .object({
    bankDate: date,
    amount: money,
    depositorName: z.string().max(50).optional(),
    bankTxId: z.string().max(40).optional(),
    prepayment: z.boolean().default(false),
    prepaymentPrincipal: z.string().optional(),
  })
  .transform((v) => ({
    ...v,
    prepaymentPrincipal:
      v.prepayment && v.prepaymentPrincipal && v.prepaymentPrincipal.trim() !== ''
        ? Number(v.prepaymentPrincipal.replace(/[^0-9]/g, ''))
        : null,
  }))

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

export type LoginForm = z.infer<typeof loginSchema>
export type PasswordChangeForm = z.infer<typeof passwordChangeSchema>
export type CorrectionForm = z.infer<typeof correctionSchema>
