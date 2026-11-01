import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { GlobalSearch } from '@/components/GlobalSearch'
import { type NavGroup, activeItem, visibleGroups } from '@/components/navMenu'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/auth'
import { formatDate } from '@/lib/format'
import { THEME_LABEL, THEME_ORDER, setPreferences, toggleCollapsed, usePreferences } from '@/lib/preferences'
import { cn } from '@/lib/utils'

export function Layout() {
  const { me, can, logout } = useAuth()
  const navigate = useNavigate()
  const { data: bd } = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })
  const prefs = usePreferences()
  const top = prefs.nav === 'top'
  const groups = visibleGroups(can)
  const { pathname } = useLocation()
  const active = activeItem(groups, pathname)
  const nextTheme = THEME_ORDER[(THEME_ORDER.indexOf(prefs.theme) + 1) % THEME_ORDER.length]

  return (
    <div className="flex min-h-screen flex-col">
      <header className="flex h-11 items-center justify-between border-b bg-primary px-4 text-primary-foreground">
        <div className="flex items-center gap-4">
          <span className="text-sm font-bold">Arche 여신관리</span>
          <span className="rounded bg-white/15 px-2 py-0.5 text-xs" data-testid="business-date">
            업무일자 {formatDate(bd?.businessDate)}
          </span>
        </div>
        <div className="flex items-center gap-2 text-xs">
          {can('loan.read') && <GlobalSearch />}
          <span className="mr-1">
            {me?.name} · {me?.roleLabel}
          </span>
          <Button
            size="sm"
            variant="outline"
            className="text-foreground"
            title={`테마: ${THEME_LABEL[prefs.theme]} (누르면 ${THEME_LABEL[nextTheme]})`}
            aria-label={`테마 전환, 현재 ${THEME_LABEL[prefs.theme]}`}
            onClick={() => setPreferences({ theme: nextTheme })}
          >
            {prefs.theme === 'dark' ? '🌙' : prefs.theme === 'light' ? '☀️' : '🖥️'} {THEME_LABEL[prefs.theme]}
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="text-foreground"
            aria-label={top ? '메뉴를 왼쪽으로' : '메뉴를 상단으로'}
            onClick={() => setPreferences({ nav: top ? 'side' : 'top' })}
          >
            {top ? '메뉴 왼쪽' : '메뉴 상단'}
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="text-foreground"
            onClick={async () => {
              await logout()
              navigate('/', { replace: true })
            }}
          >
            로그아웃
          </Button>
        </div>
      </header>
      {top && <TopNav groups={groups} activeTo={active?.item.to} activeGroup={active?.group.key} />}
      <div className="flex flex-1">
        {!top && (
          <SideNav
            groups={groups}
            collapsed={prefs.collapsed}
            activeTo={active?.item.to}
            activeGroup={active?.group.key}
          />
        )}
        <main className="min-w-0 flex-1 p-4">
          {me && me.passwordExpiresInDays >= 0 && (
            <Alert tone="warn" className="mb-3">
              비밀번호 만료까지 {me.passwordExpiresInDays}일 남았습니다. 내 계정에서 변경하세요.
            </Alert>
          )}
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function MenuLink({ to, label, active, onClick }: { to: string; label: string; active: boolean; onClick?: () => void }) {
  return (
    <NavLink
      to={to}
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'block rounded px-2 py-1.5 text-xs whitespace-nowrap',
        active ? 'bg-primary text-primary-foreground' : 'hover:bg-muted',
      )}
    >
      {label}
    </NavLink>
  )
}

/** 왼쪽 메뉴: 대분류 제목을 눌러 접고 펼침 (접은 상태는 브라우저에 저장). 지금 보는 화면의 대분류는 항상 펼침 */
function SideNav({
  groups,
  collapsed,
  activeTo,
  activeGroup,
}: {
  groups: NavGroup[]
  collapsed: string[]
  activeTo?: string
  activeGroup?: string
}) {
  return (
    <nav aria-label="주 메뉴" className="w-44 shrink-0 border-r bg-muted/50 p-2">
      <ul className="space-y-2">
        {groups.map((g) => {
          const open = g.key === activeGroup || !collapsed.includes(g.key)
          return (
            <li key={g.key}>
              <button
                type="button"
                aria-expanded={open}
                onClick={() => toggleCollapsed(g.key)}
                className={cn(
                  'flex w-full items-center justify-between rounded px-2 py-1 text-[11px] font-semibold text-muted-foreground hover:bg-muted',
                  g.key === activeGroup && 'text-foreground',
                )}
              >
                {g.label}
                <span aria-hidden>{open ? '▾' : '▸'}</span>
              </button>
              {open && (
                <ul className="mt-0.5 space-y-0.5 pl-1">
                  {g.items.map((n) => (
                    <li key={n.to}>
                      <MenuLink to={n.to} label={n.label} active={n.to === activeTo} />
                    </li>
                  ))}
                </ul>
              )}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

/** 상단 메뉴: 대분류를 누르면 하위 메뉴가 펼쳐짐. 메뉴를 고르거나 바깥을 누르거나 Esc로 닫음 */
function TopNav({ groups, activeTo, activeGroup }: { groups: NavGroup[]; activeTo?: string; activeGroup?: string }) {
  const [openKey, setOpenKey] = useState<string | null>(null)
  const ref = useRef<HTMLElement>(null)

  useEffect(() => {
    if (!openKey) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpenKey(null)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpenKey(null)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [openKey])

  return (
    <nav ref={ref} aria-label="주 메뉴" className="relative z-20 border-b bg-muted/50 px-2 py-1">
      <ul className="flex flex-wrap gap-0.5">
        {groups.map((g) => {
          const open = openKey === g.key
          return (
            <li key={g.key} className="relative">
              <button
                type="button"
                aria-expanded={open}
                aria-haspopup="true"
                onClick={() => setOpenKey(open ? null : g.key)}
                className={cn(
                  'rounded px-2.5 py-1.5 text-xs font-medium whitespace-nowrap hover:bg-muted',
                  g.key === activeGroup && 'bg-muted text-foreground',
                  open && 'bg-muted',
                )}
              >
                {g.label} <span aria-hidden>▾</span>
              </button>
              {open && (
                <ul className="absolute top-full left-0 mt-1 min-w-40 space-y-0.5 rounded border bg-background p-1 shadow-md">
                  {g.items.map((n) => (
                    <li key={n.to}>
                      <MenuLink
                        to={n.to}
                        label={n.label}
                        active={n.to === activeTo}
                        onClick={() => setOpenKey(null)}
                      />
                    </li>
                  ))}
                </ul>
              )}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
