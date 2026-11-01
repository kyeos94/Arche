import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '@/api/types'
import { AuthProvider, useAuth } from './auth'

const head: Me = {
  id: 7,
  loginId: 'head',
  name: '오본부장',
  role: 'HEAD',
  roleLabel: '본부장',
  teamId: 9,
  permissions: ['loan.read'],
  passwordChangeRequired: false,
  passwordExpiresInDays: -1,
}

function json(body: unknown, status = 200) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function Screen() {
  const { me, loading, logout } = useAuth()
  if (loading) return <p>불러오는 중</p>
  if (!me) return <p>로그인 화면</p>
  return (
    <button type="button" onClick={() => void logout()}>
      {me.name} 로그아웃
    </button>
  )
}

describe('AuthProvider', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('로그아웃하면 로그인 화면으로 돌아간다 (캐시를 비워도 me 구독이 끊기지 않음)', async () => {
    let loggedIn = true
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/auth/csrf')) return json({ token: 't' })
        if (url.endsWith('/api/auth/logout')) {
          loggedIn = false
          return new Response(null, { status: 204 })
        }
        if (url.endsWith('/api/auth/me')) {
          return loggedIn ? json(head) : json({ code: 'UNAUTHENTICATED', message: '로그인이 필요합니다' }, 401)
        }
        return json({})
      }),
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <Screen />
        </AuthProvider>
      </QueryClientProvider>,
    )

    await userEvent.click(await screen.findByRole('button', { name: '오본부장 로그아웃' }))
    await waitFor(() => expect(screen.getByText('로그인 화면')).toBeInTheDocument())
  })
})
