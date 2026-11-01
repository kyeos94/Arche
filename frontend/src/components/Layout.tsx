import { useQuery } from '@tanstack/react-query'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { GlobalSearch } from '@/components/GlobalSearch'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/auth'
import { formatDate } from '@/lib/format'
import { THEME_LABEL, THEME_ORDER, setPreferences, usePreferences } from '@/lib/preferences'
import { cn } from '@/lib/utils'

const nav: { to: string; label: string; permission?: string; anyOf?: string[] }[] = [
  { to: '/loans', label: '계약 목록', permission: 'loan.read' },
  { to: '/loans/new', label: '계약 등록', permission: 'loan.create' },
  { to: '/alerts', label: '추심 알림', permission: 'loan.read' },
  { to: '/legal-actions', label: '법조치 현황', permission: 'loan.read' },
  { to: '/unmatched', label: '미확인 입금', permission: 'payment.register' },
  { to: '/payment-splits', label: '일괄 입금 배분', permission: 'payment.register' },
  { to: '/corrections', label: '정정 승인', permission: 'payment.cancel.closed.approve' },
  { to: '/charge-off', label: '상각 후보', permission: 'loan.chargeoff.confirm' },
  { to: '/adjustments', label: '조정 결재', permission: 'loan.adjust.request' },
  { to: '/bulk', label: '입금 일괄 등록', permission: 'payment.register' },
  { to: '/reports', label: '보고서', permission: 'report.read' },
  {
    to: '/withdrawals',
    label: '출금 작성·승인',
    anyOf: ['withdrawal.create', 'withdrawal.approve.small', 'withdrawal.approve.large'],
  },
  { to: '/admin', label: '운영 관리', permission: 'admin.manage' },
  { to: '/message-templates', label: '문자 템플릿', permission: 'admin.manage' },
  { to: '/credit-reports', label: '신용정보 보고', permission: 'admin.manage' },
  { to: '/account', label: '내 계정' },
]

export function Layout() {
  const { me, can, logout } = useAuth()
  const navigate = useNavigate()
  const { data: bd } = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })
  const prefs = usePreferences()
  const top = prefs.nav === 'top'
  const items = nav.filter(
    (n) => (!n.permission || can(n.permission)) && (!n.anyOf || n.anyOf.some((p) => can(p))),
  )
  const nextTheme = THEME_ORDER[(THEME_ORDER.indexOf(prefs.theme) + 1) % THEME_ORDER.length]

  const links = items.map((n) => (
    <li key={n.to}>
      <NavLink
        to={n.to}
        end={n.to === '/loans'}
        className={({ isActive }) =>
          cn(
            'block rounded px-2 py-1.5 text-xs whitespace-nowrap',
            isActive ? 'bg-primary text-primary-foreground' : 'hover:bg-muted',
          )
        }
      >
        {n.label}
      </NavLink>
    </li>
  ))

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
      {top && (
        <nav aria-label="주 메뉴" className="border-b bg-muted/50 px-2 py-1">
          <ul className="flex flex-wrap gap-0.5">{links}</ul>
        </nav>
      )}
      <div className="flex flex-1">
        {!top && (
          <nav aria-label="주 메뉴" className="w-40 shrink-0 border-r bg-muted/50 p-2">
            <ul className="space-y-0.5">{links}</ul>
          </nav>
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
