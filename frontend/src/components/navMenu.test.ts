import { describe, expect, it } from 'vitest'
import { NAV_GROUPS, activeItem, visibleGroups } from './navMenu'

const only = (...codes: string[]) => (p: string) => codes.includes(p)

describe('주 메뉴 대분류', () => {
  it('모든 메뉴는 정확히 하나의 대분류에 속한다', () => {
    const paths = NAV_GROUPS.flatMap((g) => g.items.map((i) => i.to))
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('권한이 없는 메뉴와 빈 대분류는 숨긴다', () => {
    const groups = visibleGroups(only('loan.read'))
    expect(groups.map((g) => g.label)).toEqual(['여신관리', '회수관리', '시스템설정'])
    expect(groups[0].items.map((i) => i.label)).toEqual(['계약 목록'])
    expect(groups[2].items.map((i) => i.label)).toEqual(['내 계정'])
  })

  it('anyOf 권한은 하나만 있어도 보인다', () => {
    const groups = visibleGroups(only('withdrawal.approve.small'))
    expect(groups.find((g) => g.key === 'payment')?.items.map((i) => i.label)).toEqual(['출금 작성·승인'])
  })

  it('현재 경로는 가장 길게 맞는 메뉴로 판정한다', () => {
    const groups = visibleGroups(() => true)
    expect(activeItem(groups, '/loans/12')?.item.label).toBe('계약 목록')
    expect(activeItem(groups, '/loans/new')?.item.label).toBe('계약 등록')
    expect(activeItem(groups, '/applications/3')?.group.label).toBe('대출신청관리')
    expect(activeItem(groups, '/loansx')).toBeNull()
  })
})
