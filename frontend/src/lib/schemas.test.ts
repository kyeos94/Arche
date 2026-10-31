import { describe, expect, it } from 'vitest'
import { correctionSchema, dueDayChangeSchema, openLoanSchema, paymentSchema, promiseSchema, rateChangeSchema, workoutSchema } from './schemas'

describe('schemas', () => {
  it('약정일은 5·10·15·20·25일만', () => {
    expect(dueDayChangeSchema.safeParse({ dueDay: '25', reason: '고객 요청' }).success).toBe(true)
    const bad = dueDayChangeSchema.safeParse({ dueDay: '7', reason: '고객 요청' })
    expect(bad.success).toBe(false)
  })

  it('계약 등록 약정일 검증', () => {
    const base = {
      productCode: 'P1',
      principal: '10,000,000',
      rate: '19.9',
      termMonths: '12',
      graceMonths: '0',
      disbursedOn: '2026-01-10',
    }
    const ok = openLoanSchema.safeParse({ ...base, dueDay: '10' })
    expect(ok.success).toBe(true)
    if (ok.success) expect(ok.data.principal).toBe(10_000_000)
    expect(openLoanSchema.safeParse({ ...base, dueDay: '31' }).success).toBe(false)
  })

  it('입금액은 쉼표를 허용하고 정수로 바꾼다', () => {
    const r = paymentSchema.safeParse({ bankDate: '2026-05-26', amount: '1,135,616', prepayment: false })
    expect(r.success).toBe(true)
    if (r.success) {
      expect(r.data.amount).toBe(1135616)
      expect(r.data.prepaymentPrincipal).toBeNull()
    }
    expect(paymentSchema.safeParse({ bankDate: '2026/05/26', amount: '1' }).success).toBe(false)
    expect(paymentSchema.safeParse({ bankDate: '2026-05-26', amount: '0' }).success).toBe(false)
  })

  it('중도상환 원금 지정', () => {
    const r = paymentSchema.safeParse({
      bankDate: '2026-08-01',
      amount: '2,071,713',
      prepayment: true,
      prepaymentPrincipal: '2,000,000',
    })
    expect(r.success && r.data.prepaymentPrincipal).toBe(2_000_000)
  })

  it('금리는 법정 최고이자율 20%를 넘을 수 없다', () => {
    expect(rateChangeSchema.safeParse({ rate: '17.9', validFrom: '2026-06-25', reason: '인하' }).success).toBe(true)
    expect(rateChangeSchema.safeParse({ rate: '21', validFrom: '2026-06-25', reason: '인상' }).success).toBe(false)
  })

  it('계약 이동은 대상 계약번호, 금액 정정은 금액 필수', () => {
    expect(correctionSchema.safeParse({ type: 'MOVE', reason: '다른 고객 입금' }).success).toBe(false)
    expect(
      correctionSchema.safeParse({ type: 'MOVE', targetAccountNo: 'L00001002', reason: '다른 고객 입금' }).success,
    ).toBe(true)
    expect(correctionSchema.safeParse({ type: 'AMOUNT_FIX', correctedAmount: '', reason: '오입력' }).success).toBe(
      false,
    )
    expect(correctionSchema.safeParse({ type: 'CANCEL', reason: '' }).success).toBe(false)
  })
})

describe('workoutSchema', () => {
  const base = { waiveLate: '21,204', waiveNormal: '', waivePrincipal: '0', installments: '24', dueDay: '25', reason: '생계 곤란' }

  it('감면액 쉼표 허용, 비우면 0', () => {
    const v = workoutSchema.parse(base)
    expect(v).toMatchObject({ waiveLate: 21204, waiveNormal: 0, waivePrincipal: 0, installments: 24, dueDay: 25 })
  })

  it('분할은 최대 60회 (R9)', () => {
    expect(workoutSchema.safeParse({ ...base, installments: '61' }).success).toBe(false)
  })
})

describe('promiseSchema', () => {
  it('매월 7일 300,000원 × 5회 (G19)', () => {
    const v = promiseSchema.parse({ firstMonth: '2026-08', day: '7', amount: '300,000', months: '5' })
    expect(v).toMatchObject({ firstMonth: '2026-08', day: 7, amount: 300000, months: 5 })
  })

  it('약속일은 1~31일', () => {
    expect(promiseSchema.safeParse({ firstMonth: '2026-08', day: '32', amount: '1', months: '1' }).success).toBe(false)
  })
})
