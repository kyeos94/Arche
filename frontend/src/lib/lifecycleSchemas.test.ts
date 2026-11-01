import { describe, expect, it } from 'vitest'
import { adjustmentRequestSchema, collectionStopSchema, reliefSchema, saleSchema } from './lifecycleSchemas'

const empty = { principalDown: '', normalDown: '', lateDown: '', preAcqDown: '', costDelta: '', amount: '' }

describe('lifecycleSchemas', () => {
  it('매각은 양수인·매각가·양도 통지 발송일·사유가 모두 있어야 한다 (R40)', () => {
    expect(
      saleSchema.safeParse({ buyer: '가상자산관리(가상)', price: '1,500,000', noticeSentOn: '2027-02-26', reason: '매각' })
        .data?.price,
    ).toBe(1_500_000)
    expect(saleSchema.safeParse({ buyer: '', price: '1', noticeSentOn: '2027-02-26', reason: '매각' }).success).toBe(false)
    expect(saleSchema.safeParse({ buyer: 'x', price: '1', noticeSentOn: '', reason: '매각' }).success).toBe(false)
  })

  it('수기 조정은 금액이 하나라도 있어야 하고 비용은 음수(감액)를 허용한다 (R42)', () => {
    expect(adjustmentRequestSchema.safeParse({ type: 'ADJUSTMENT', ...empty, reason: '조정' }).success).toBe(false)
    const parsed = adjustmentRequestSchema.parse({ type: 'ADJUSTMENT', ...empty, costDelta: '-30,000', reason: '조정' })
    expect(parsed.costDelta).toBe(-30_000)
    expect(parsed.principalDown).toBe(0)
  })

  it('상계는 원천 계약·원천 종류·금액이 필요하다 (R44)', () => {
    const r = adjustmentRequestSchema.safeParse({ type: 'SETOFF', ...empty, reason: '상계' })
    expect(r.success).toBe(false)
    const paths = r.error?.issues.map((i) => i.path.join('.'))
    expect(paths).toEqual(expect.arrayContaining(['sourceAccountNo', 'setoffSource', 'amount']))
    expect(
      adjustmentRequestSchema.safeParse({
        type: 'SETOFF',
        ...empty,
        sourceAccountNo: 'L00001001',
        setoffSource: 'REFUND',
        amount: '50,000',
        reason: '상계',
      }).success,
    ).toBe(true)
  })

  it('상태 되돌리기는 사유만 있으면 된다 (상계 취소는 대상 필요, R43)', () => {
    expect(adjustmentRequestSchema.safeParse({ type: 'REVERT_PAYOFF', ...empty, reason: '부도' }).success).toBe(true)
    expect(adjustmentRequestSchema.safeParse({ type: 'REVERT_SETOFF', ...empty, reason: '부도' }).success).toBe(false)
  })

  it('추심 중지는 사유·발생일·근거, 조정 금리는 20% 이하 (R45·R46)', () => {
    expect(collectionStopSchema.safeParse({ reason: 'BANKRUPTCY', effectiveDate: '2026-10-01', memo: '' }).success).toBe(
      false,
    )
    expect(
      collectionStopSchema.safeParse({ reason: 'BANKRUPTCY', effectiveDate: '2026-10-01', memo: '선고 통지' }).success,
    ).toBe(true)
    expect(reliefSchema.safeParse({ rate: '21', validFrom: '2026-11-01' }).success).toBe(false)
    expect(reliefSchema.parse({ rate: '10.5', validFrom: '2026-11-01' }).rate).toBe(10.5)
  })
})
