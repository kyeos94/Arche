import { describe, expect, it } from 'vitest'
import { DEFAULT_MENU, NAV_GROUPS, REQUIRED_PATHS, SCREENS, activeItem, toNavGroups, visibleGroups } from './navMenu'

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
    expect(groups[2].items.map((i) => i.label)).toEqual(['내 계정', '쪽지함'])
  })

  it('anyOf 권한은 하나만 있어도 보인다', () => {
    const groups = visibleGroups(only('withdrawal.approve.small'))
    expect(groups.find((g) => g.label === '입출금관리')?.items.map((i) => i.label)).toEqual(['출금 작성·승인'])
  })

  it('현재 경로는 가장 길게 맞는 메뉴로 판정한다', () => {
    const groups = visibleGroups(() => true)
    expect(activeItem(groups, '/loans/12')?.item.label).toBe('계약 목록')
    expect(activeItem(groups, '/loans/new')?.item.label).toBe('계약 등록')
    expect(activeItem(groups, '/applications/3')?.group.label).toBe('대출신청관리')
    expect(activeItem(groups, '/loansx')).toBeNull()
    expect(activeItem(groups, '/admin/menus')?.item.label).toBe('메뉴 관리')
  })

  it('서버 메뉴의 이름·순서를 쓰고 코드에 없는 화면은 버린다', () => {
    const groups = toNavGroups([
      { id: 7, label: '경영정보', items: [{ path: '/reports', label: '경영 보고서' }, { path: '/gone', label: '없는 화면' }] },
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0].key).toBe('g7')
    expect(groups[0].items.map((i) => [i.to, i.label, i.permission])).toEqual([['/reports', '경영 보고서', 'report.read']])
  })

  it('기본 메뉴는 화면 목록을 빠짐없이 담고 필수 화면을 포함한다', () => {
    const paths = DEFAULT_MENU.flatMap((g) => g.items.map((i) => i.path))
    expect(paths.sort()).toEqual(Object.keys(SCREENS).sort())
    REQUIRED_PATHS.forEach((p) => expect(paths).toContain(p))
  })
})
