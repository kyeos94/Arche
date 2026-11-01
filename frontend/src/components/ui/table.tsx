import type * as React from 'react'
import { cn } from '@/lib/utils'
import type { SortHeaderProps } from '@/lib/useSort'

export function Table({ className, ...props }: React.ComponentProps<'table'>) {
  return (
    <div className="w-full overflow-x-auto">
      <table className={cn('w-full caption-bottom text-xs', className)} {...props} />
    </div>
  )
}

export function THead({ className, ...props }: React.ComponentProps<'thead'>) {
  return <thead className={cn('bg-muted [&_tr]:border-b', className)} {...props} />
}

export function TBody({ className, ...props }: React.ComponentProps<'tbody'>) {
  return <tbody className={cn('[&_tr:last-child]:border-0', className)} {...props} />
}

export function TR({ className, ...props }: React.ComponentProps<'tr'>) {
  return <tr className={cn('border-b hover:bg-muted/50', className)} {...props} />
}

export function TH({ className, ...props }: React.ComponentProps<'th'>) {
  return (
    <th
      className={cn('h-7 px-2 text-left align-middle font-medium whitespace-nowrap text-muted-foreground', className)}
      {...props}
    />
  )
}

/**
 * 눌러서 정렬하는 머리글 (ADR 0011). useSort().header(key) 또는 서버 정렬 상태를 그대로 넘긴다.
 * 오름차순 ▲, 내림차순 ▼, 정렬하지 않으면 흐린 ↕. 스크린리더에는 aria-sort로 알린다.
 */
export function SortTH({
  sortKey,
  sort,
  onSort,
  className,
  children,
  ...props
}: Omit<React.ComponentProps<'th'>, 'onClick'> & SortHeaderProps) {
  const active = sort?.key === sortKey ? sort.direction : null
  const right = className?.includes('text-right')
  return (
    <th
      aria-sort={active === 'asc' ? 'ascending' : active === 'desc' ? 'descending' : 'none'}
      className={cn('h-7 px-2 text-left align-middle font-medium whitespace-nowrap text-muted-foreground', className)}
      {...props}
    >
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className={cn(
          'inline-flex items-center gap-0.5 hover:text-foreground',
          right && 'flex-row-reverse',
          active && 'text-foreground',
        )}
      >
        {children}
        <span aria-hidden className={cn('text-[10px]', !active && 'opacity-30')}>
          {active === 'asc' ? '▲' : active === 'desc' ? '▼' : '↕'}
        </span>
      </button>
    </th>
  )
}

export function TD({ className, ...props }: React.ComponentProps<'td'>) {
  return <td className={cn('px-2 py-1 align-middle whitespace-nowrap', className)} {...props} />
}

export function EmptyRow({ colSpan, children = '데이터가 없습니다' }: { colSpan: number; children?: React.ReactNode }) {
  return (
    <tr>
      <td colSpan={colSpan} className="py-6 text-center text-muted-foreground">
        {children}
      </td>
    </tr>
  )
}
