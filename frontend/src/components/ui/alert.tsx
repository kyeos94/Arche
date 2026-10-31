import type * as React from 'react'
import { ApiError } from '@/api/client'
import { cn } from '@/lib/utils'

export function Alert({
  tone = 'info',
  className,
  ...props
}: React.ComponentProps<'div'> & { tone?: 'info' | 'warn' | 'error' | 'success' }) {
  const tones = {
    info: 'border-sky-200 bg-sky-50 text-sky-800',
    warn: 'border-amber-200 bg-amber-50 text-amber-800',
    error: 'border-red-200 bg-red-50 text-red-700',
    success: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  }
  return <div role="alert" className={cn('rounded-md border px-3 py-2 text-xs', tones[tone], className)} {...props} />
}

/** mutation·query 오류를 서버 메시지 그대로 보여준다 */
export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof ApiError || error instanceof Error ? error.message : String(error)
  return <Alert tone="error">{message}</Alert>
}
