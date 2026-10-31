import type * as React from 'react'
import { cn } from '@/lib/utils'

const fieldClass =
  'flex w-full rounded-md border bg-background px-2 text-xs focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-50 aria-invalid:border-destructive'

export function Input({ className, ...props }: React.ComponentProps<'input'>) {
  return <input className={cn(fieldClass, 'h-8', className)} {...props} />
}

export function Textarea({ className, ...props }: React.ComponentProps<'textarea'>) {
  return <textarea className={cn(fieldClass, 'min-h-16 py-1', className)} {...props} />
}

export function Select({ className, ...props }: React.ComponentProps<'select'>) {
  return <select className={cn(fieldClass, 'h-8', className)} {...props} />
}

export function Label({ className, ...props }: React.ComponentProps<'label'>) {
  return <label className={cn('text-xs font-medium text-muted-foreground', className)} {...props} />
}

/** 라벨 + 입력 + 오류 메시지 */
export function Field({
  label,
  error,
  children,
  htmlFor,
}: {
  label: string
  error?: string
  children: React.ReactNode
  htmlFor?: string
}) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  )
}
