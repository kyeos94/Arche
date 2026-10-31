import { useQuery } from '@tanstack/react-query'
import { NavLink, Outlet } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/auth'
import { formatDate } from '@/lib/format'
import { cn } from '@/lib/utils'

const nav: { to: string; label: string; permission?: string }[] = [
  { to: '/loans', label: '계약 목록', permission: 'loan.read' },
  { to: '/loans/new', label: '계약 등록', permission: 'loan.create' },
  { to: '/alerts', label: '추심 알림', permission: 'loan.read' },
  { to: '/unmatched', label: '미확인 입금', permission: 'payment.register' },
  { to: '/corrections', label: '정정 승인', permission: 'payment.cancel.closed.approve' },
  { to: '/bulk', label: '입금 일괄 등록', permission: 'payment.register' },
  { to: '/admin', label: '운영 관리', permission: 'admin.manage' },
  { to: '/account', label: '내 계정' },
]

export function Layout() {
  const { me, can, logout } = useAuth()
  const { data: bd } = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })

  return (
    <div className="flex min-h-screen flex-col">
      <header className="flex h-11 items-center justify-between border-b bg-primary px-4 text-primary-foreground">
        <div className="flex items-center gap-4">
          <span className="text-sm font-bold">Arche 여신관리</span>
          <span className="rounded bg-white/15 px-2 py-0.5 text-xs" data-testid="business-date">
            업무일자 {formatDate(bd?.businessDate)}
          </span>
        </div>
        <div className="flex items-center gap-3 text-xs">
          <span>
            {me?.name} · {me?.roleLabel}
          </span>
          <Button size="sm" variant="outline" className="text-foreground" onClick={() => void logout()}>
            로그아웃
          </Button>
        </div>
      </header>
      <div className="flex flex-1">
        <nav className="w-40 shrink-0 border-r bg-muted/50 p-2">
          <ul className="space-y-0.5">
            {nav
              .filter((n) => !n.permission || can(n.permission))
              .map((n) => (
                <li key={n.to}>
                  <NavLink
                    to={n.to}
                    end={n.to === '/loans'}
                    className={({ isActive }) =>
                      cn(
                        'block rounded px-2 py-1.5 text-xs',
                        isActive ? 'bg-primary text-primary-foreground' : 'hover:bg-muted',
                      )
                    }
                  >
                    {n.label}
                  </NavLink>
                </li>
              ))}
          </ul>
        </nav>
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
