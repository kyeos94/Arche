import { useSyncExternalStore } from 'react'

/** 화면 설정: 테마(라이트·다크·시스템), 메뉴 위치(왼쪽·상단), 접어 둔 메뉴 대분류. 브라우저(localStorage)에만 저장 */
export type Theme = 'light' | 'dark' | 'system'
export type NavPosition = 'side' | 'top'

export interface Preferences {
  theme: Theme
  nav: NavPosition
  /** 왼쪽 메뉴에서 접어 둔 대분류 key */
  collapsed: string[]
}

export const STORAGE_KEY = 'arche.preferences'
const DEFAULTS: Preferences = { theme: 'system', nav: 'side', collapsed: [] }

function read(): Preferences {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULTS
    const parsed = JSON.parse(raw) as Partial<Preferences>
    return {
      theme: parsed.theme === 'light' || parsed.theme === 'dark' || parsed.theme === 'system' ? parsed.theme : 'system',
      nav: parsed.nav === 'top' ? 'top' : 'side',
      collapsed: Array.isArray(parsed.collapsed) ? parsed.collapsed.filter((k) => typeof k === 'string') : [],
    }
  } catch {
    return DEFAULTS
  }
}

let current: Preferences = typeof window === 'undefined' ? DEFAULTS : read()
const listeners = new Set<() => void>()

function prefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches === true
}

/** 실제로 적용할 테마 (시스템이면 OS 설정) */
export function resolveTheme(theme: Theme): 'light' | 'dark' {
  return theme === 'system' ? (prefersDark() ? 'dark' : 'light') : theme
}

export function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', resolveTheme(theme) === 'dark')
}

export function getPreferences(): Preferences {
  return current
}

export function setPreferences(patch: Partial<Preferences>): void {
  current = { ...current, ...patch }
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(current))
  } catch {
    // 저장소를 못 쓰는 환경(사생활 보호 모드 등)에서는 이번 접속에만 적용
  }
  applyTheme(current.theme)
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function usePreferences(): Preferences {
  return useSyncExternalStore(subscribe, getPreferences, getPreferences)
}

/** 앱 시작 시 한 번: 저장된 테마 적용 + 시스템 설정이 바뀌면 따라감 */
export function initPreferences(): void {
  current = read()
  applyTheme(current.theme)
  window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', () => {
    if (current.theme === 'system') applyTheme('system')
  })
}

export const THEME_ORDER: Theme[] = ['light', 'dark', 'system']
export const THEME_LABEL: Record<Theme, string> = { light: '라이트', dark: '다크', system: '시스템' }

/** 왼쪽 메뉴 대분류 접기·펼치기 */
export function toggleCollapsed(key: string): void {
  const set = new Set(current.collapsed)
  if (set.has(key)) set.delete(key)
  else set.add(key)
  setPreferences({ collapsed: [...set] })
}
