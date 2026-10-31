import { describe, expect, it } from 'vitest'
import { formatDate, formatMoney, formatNumber, formatRate, parseMoney, statusLabel } from './format'

describe('format', () => {
  it('금액은 천 단위 구분 + 원', () => {
    expect(formatMoney(1218465)).toBe('1,218,465원')
    expect(formatMoney(0)).toBe('0원')
    expect(formatMoney(-1135616)).toBe('-1,135,616원')
    expect(formatMoney(null)).toBe('-')
  })

  it('숫자는 천 단위 구분만', () => {
    expect(formatNumber(1000)).toBe('1,000')
    expect(formatNumber(undefined)).toBe('-')
  })

  it('날짜는 yyyy-MM-dd', () => {
    expect(formatDate('2026-06-12')).toBe('2026-06-12')
    expect(formatDate('2026-06-12T09:30:00+09:00')).toBe('2026-06-12')
    expect(formatDate(null)).toBe('-')
  })

  it('이율', () => {
    expect(formatRate(19.9)).toBe('19.9%')
    expect(formatRate('15.00')).toBe('15%')
  })

  it('입력 금액 파싱', () => {
    expect(parseMoney('1,135,616')).toBe(1135616)
    expect(Number.isNaN(parseMoney(''))).toBe(true)
  })

  it('상태 라벨', () => {
    expect(statusLabel('DELINQUENT')).toBe('연체')
    expect(statusLabel('UNKNOWN_CODE')).toBe('UNKNOWN_CODE')
  })
})
