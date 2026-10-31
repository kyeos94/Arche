import { statusLabel } from '@/lib/format'
import { cn } from '@/lib/utils'

export const LOAN_STATUSES = ['NORMAL', 'DELINQUENT', 'ACCELERATED', 'PAID_OFF'] as const

/** 상태 탭 + 건수. value가 ''이면 전체 */
export function StatusTabs({
  counts,
  value,
  onChange,
}: {
  counts: Record<string, number> | undefined
  value: string
  onChange: (status: string) => void
}) {
  const total = counts ? Object.values(counts).reduce((a, b) => a + b, 0) : undefined
  const tabs = [{ key: '', label: '전체', count: total }].concat(
    LOAN_STATUSES.map((s) => ({ key: s, label: statusLabel(s), count: counts?.[s] })),
  )
  return (
    <div role="tablist" className="flex gap-1 border-b">
      {tabs.map((t) => (
        <button
          key={t.key || 'ALL'}
          role="tab"
          type="button"
          aria-selected={value === t.key}
          onClick={() => onChange(t.key)}
          className={cn(
            'h-8 border-b-2 px-3 text-xs',
            value === t.key ? 'border-primary font-semibold' : 'border-transparent text-muted-foreground',
          )}
        >
          {t.label} <span className="tabular-nums">{t.count ?? '-'}</span>
        </button>
      ))}
    </div>
  )
}
