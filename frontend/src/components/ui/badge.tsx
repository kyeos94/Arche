import type * as React from 'react'
import { statusLabel } from '@/lib/format'
import { cn } from '@/lib/utils'

const tones: Record<string, string> = {
  NORMAL: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  PAID: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  ALLOCATED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  APPLIED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  DELINQUENT: 'bg-amber-50 text-amber-800 border-amber-200',
  OVERDUE: 'bg-amber-50 text-amber-800 border-amber-200',
  PARTIALLY_PAID: 'bg-amber-50 text-amber-800 border-amber-200',
  REQUESTED: 'bg-amber-50 text-amber-800 border-amber-200',
  PENDING: 'bg-amber-50 text-amber-800 border-amber-200',
  UNMATCHED: 'bg-amber-50 text-amber-800 border-amber-200',
  OPEN: 'bg-amber-50 text-amber-800 border-amber-200',
  ACCELERATED: 'bg-red-50 text-red-700 border-red-200',
  EXECUTED: 'bg-red-50 text-red-700 border-red-200',
  CANCELLED: 'bg-slate-100 text-slate-600 border-slate-200',
  REPLACED: 'bg-slate-100 text-slate-600 border-slate-200',
  PAID_OFF: 'bg-sky-50 text-sky-700 border-sky-200',
  SPLIT: 'bg-sky-50 text-sky-700 border-sky-200',
}

export function Badge({ className, ...props }: React.ComponentProps<'span'>) {
  return (
    <span
      className={cn('inline-flex items-center rounded border px-1.5 py-0.5 text-[11px] font-medium', className)}
      {...props}
    />
  )
}

export function StatusBadge({ status }: { status: string }) {
  return <Badge className={tones[status] ?? 'bg-muted text-foreground'}>{statusLabel(status)}</Badge>
}
