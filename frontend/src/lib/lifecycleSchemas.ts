import { z } from 'zod'
import { money } from './schemas'

/** 채권 생애주기 입력 (정책서 R39~R46). 서버가 다시 검증하므로 여기서는 형식과 명백한 오류만 막는다 */

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'yyyy-MM-dd 형식으로 입력하세요')
const reason = z.string().trim().min(1, '사유를 입력하세요').max(200)

/** 비워 두면 0, 0 이상 */
const nonNegative = z
  .union([z.string(), z.number()])
  .transform((v) => (typeof v === 'number' ? v : Number(v.replace(/[^0-9]/g, '') || '0')))
  .pipe(z.number().int().min(0, '0 이상이어야 합니다'))

/** 비워 두면 0, 음수 허용 (비용 증감) */
const signed = z
  .union([z.string(), z.number()])
  .transform((v) => {
    if (typeof v === 'number') return v
    const t = v.replace(/[^0-9-]/g, '')
    return t === '' || t === '-' ? 0 : Number(t)
  })
  .pipe(z.number().int('정수로 입력하세요'))

export const reasonSchema = z.object({ reason })

/** 매각 (R40): 양도 통지 발송일 필수 */
export const saleSchema = z.object({
  buyer: z.string().trim().min(1, '양수인을 입력하세요').max(100),
  price: money,
  noticeSentOn: date,
  reason,
})

/** 양수인 이전 (R40) */
export const transferSchema = z.object({ amount: money, memo: z.string().max(200).optional() })

export const ADJUSTMENT_TYPES = [
  'ADJUSTMENT',
  'SETOFF',
  'REVERT_CHARGE_OFF',
  'REVERT_SALE',
  'REVERT_SETOFF',
  'REVERT_PAYOFF',
] as const

/** 수기 조정·상계·상태 되돌리기 요청 (R42~R44) */
export const adjustmentRequestSchema = z
  .object({
    type: z.enum(ADJUSTMENT_TYPES),
    principalDown: nonNegative,
    normalDown: nonNegative,
    lateDown: nonNegative,
    preAcqDown: nonNegative,
    costDelta: signed,
    sourceAccountNo: z.string().optional(),
    setoffSource: z.enum(['REFUND', 'SUSPENSE']).optional(),
    amount: nonNegative,
    targetEventId: z.string().optional(),
    reason,
  })
  .superRefine((v, ctx) => {
    if (
      v.type === 'ADJUSTMENT' &&
      v.principalDown + v.normalDown + v.lateDown + v.preAcqDown === 0 &&
      v.costDelta === 0
    ) {
      ctx.addIssue({ code: 'custom', path: ['costDelta'], message: '조정할 금액을 입력하세요' })
    }
    if (v.type === 'SETOFF') {
      if (!v.sourceAccountNo?.trim()) {
        ctx.addIssue({ code: 'custom', path: ['sourceAccountNo'], message: '원천 계약번호를 입력하세요' })
      }
      if (!v.setoffSource) {
        ctx.addIssue({ code: 'custom', path: ['setoffSource'], message: '원천(반환 대기·가수금)을 고르세요' })
      }
      if (v.amount <= 0) {
        ctx.addIssue({ code: 'custom', path: ['amount'], message: '상계액을 입력하세요' })
      }
    }
    if (v.type === 'REVERT_SETOFF' && !v.targetEventId) {
      ctx.addIssue({ code: 'custom', path: ['targetEventId'], message: '되돌릴 상계를 고르세요' })
    }
  })

export const STOP_REASONS = ['DECEASED', 'REHABILITATION', 'BANKRUPTCY', 'PUBLIC_WORKOUT'] as const

/** 추심 중지 (R45) */
export const collectionStopSchema = z.object({
  reason: z.enum(STOP_REASONS),
  effectiveDate: date,
  memo: z.string().trim().min(1, '근거(통지 수령 내용 등)를 입력하세요').max(200),
})

/** 공적 채무조정(가상) 확정 금리 (R46) */
export const reliefSchema = z.object({
  rate: z.coerce.number().min(0, '0 이상').max(20, '법정 최고이자율(20%)을 넘을 수 없습니다'),
  validFrom: date,
})
