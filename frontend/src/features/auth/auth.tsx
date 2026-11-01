import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, type ReactNode } from 'react'
import { ApiError, setUnauthorizedListener } from '@/api/client'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { Me } from '@/api/types'

interface AuthState {
  me: Me | null
  loading: boolean
  can: (permission: string) => boolean
  setMe: (me: Me | null) => void
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const { data, isPending } = useQuery({
    queryKey: queryKeys.me,
    queryFn: async () => {
      await endpoints.csrf().catch(() => undefined)
      try {
        return await endpoints.me()
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    staleTime: Infinity,
    retry: false,
  })

  useEffect(() => {
    setUnauthorizedListener(() => client.setQueryData(queryKeys.me, null))
    return () => setUnauthorizedListener(null)
  }, [client])

  const me = data ?? null
  const state: AuthState = {
    me,
    loading: isPending,
    can: (permission) => me?.permissions.includes(permission) ?? false,
    setMe: (next) => client.setQueryData(queryKeys.me, next),
    logout: async () => {
      await endpoints.logout().catch(() => undefined)
      // me를 먼저 null로 바꿔 화면을 로그인 창으로 돌린다. clear()로 me 쿼리까지 지우면 화면이 구독하던 쿼리가
      // 사라져 새로 넣은 null을 받지 못한다 (로그아웃 무반응 버그)
      client.setQueryData(queryKeys.me, null)
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== queryKeys.me[0] })
      await endpoints.csrf().catch(() => undefined)
    },
  }
  return <AuthContext value={state}>{children}</AuthContext>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('AuthProvider 밖에서 useAuth를 호출했습니다')
  return ctx
}

/** 권한이 있을 때만 children을 그린다 (화면 숨김은 편의일 뿐, 실제 통제는 서버) */
export function Can({ permission, children }: { permission: string; children: ReactNode }) {
  const { can } = useAuth()
  return can(permission) ? children : null
}
