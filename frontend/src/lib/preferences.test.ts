import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { STORAGE_KEY, getPreferences, initPreferences, resolveTheme, setPreferences } from './preferences'

function mockSystemDark(dark: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: dark && query.includes('dark'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
}

describe('preferences', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
    mockSystemDark(false)
    initPreferences()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('기본값은 시스템 테마 + 왼쪽 메뉴', () => {
    expect(getPreferences()).toEqual({ theme: 'system', nav: 'side' })
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('다크로 바꾸면 html에 dark 클래스가 붙고 저장된다', () => {
    setPreferences({ theme: 'dark' })
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toMatchObject({ theme: 'dark', nav: 'side' })
    setPreferences({ theme: 'light' })
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('메뉴 위치를 상단으로 바꾸면 다음 접속에도 유지된다', () => {
    setPreferences({ nav: 'top' })
    initPreferences()
    expect(getPreferences().nav).toBe('top')
  })

  it('시스템 테마는 OS 설정을 따른다', () => {
    mockSystemDark(true)
    expect(resolveTheme('system')).toBe('dark')
    expect(resolveTheme('light')).toBe('light')
  })

  it('저장된 값이 깨져 있으면 기본값', () => {
    localStorage.setItem(STORAGE_KEY, '{not json')
    initPreferences()
    expect(getPreferences()).toEqual({ theme: 'system', nav: 'side' })
  })
})
