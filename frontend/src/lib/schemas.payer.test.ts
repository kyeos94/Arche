import { describe, expect, it } from 'vitest'
import { legalActionSchema, legalCostSchema, paymentSchema, splitSchema } from './schemas'

describe('입금자 구분·배분·법조치 스키마 (R47~R52)', () => {
  const base = { bankDate: '2026-11-01', amount: '100,000', prepayment: false }

  it('보증인·제3자 입금은 이름이 필수다 (R49)', () => {
    expect(paymentSchema.safeParse({ ...base, payerType: 'GUARANTOR' }).success).toBe(false)
    const ok = paymentSchema.safeParse({ ...base, payerType: 'THIRD_PARTY', payerName: '박제삼' })
    expect(ok.success).toBe(true)
    if (ok.success) expect(ok.data.payerType).toBe('THIRD_PARTY')
  })

  it('입금자 구분 기본값은 본인', () => {
    const r = paymentSchema.safeParse(base)
    expect(r.success).toBe(true)
    if (r.success) {
      expect(r.data.payerType).toBe('CUSTOMER')
      expect(r.data.channel).toBe('BANK_TRANSFER')
      expect(r.data.legalActionId).toBeNull()
    }
  })

  it('배당금·공탁금은 본인 구분만, 법조치 id는 이 채널에서만 넘긴다 (R52)', () => {
    expect(
      paymentSchema.safeParse({ ...base, channel: 'AUCTION_DISTRIBUTION', payerType: 'GUARANTOR', payerName: '김' })
        .success,
    ).toBe(false)
    const court = paymentSchema.safeParse({ ...base, channel: 'COURT_DEPOSIT', legalActionId: '7' })
    expect(court.success && court.data.legalActionId).toBe(7)
    const bank = paymentSchema.safeParse({ ...base, legalActionId: '7' })
    expect(bank.success && bank.data.legalActionId).toBeNull()
  })

  it('새 입금 배분은 입금일·입금액 필수, 미확인 입금 배분은 생략 (R47)', () => {
    expect(splitSchema.safeParse({ method: 'PROPORTIONAL', amount: '' }).success).toBe(false)
    const ok = splitSchema.safeParse({ method: 'MANUAL', bankDate: '2026-11-01', amount: '300,000' })
    expect(ok.success && ok.data.amount).toBe(300_000)
    expect(splitSchema.safeParse({ method: 'OLDEST_DELINQUENCY_FIRST', fromUnmatched: true }).success).toBe(true)
  })

  it('법조치 청구금액은 비워도 되고 비용은 양수 (R51·R52)', () => {
    const a = legalActionSchema.safeParse({ type: 'AUCTION', filedOn: '2026-11-01', claimAmount: '' })
    expect(a.success && a.data.claimAmount).toBeNull()
    expect(legalCostSchema.safeParse({ costType: 'COURT_FEE', amount: '0', incurredOn: '2026-11-01' }).success).toBe(
      false,
    )
    expect(legalCostSchema.safeParse({ costType: 'COURT_FEE', amount: '50,000', incurredOn: '2026-11-01' }).success).toBe(
      true,
    )
  })
})
