import type * as React from 'react'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { formatNumber } from '@/lib/format'
import { PAGE_SIZES, type PagingProps } from '@/lib/usePaging'
import { cn } from '@/lib/utils'

/** 현재 페이지 주변으로 보여 줄 번호 (처음·끝은 항상, 사이가 비면 null = …) */
export function pageWindow(page: number, totalPages: number, around = 2): (number | null)[] {
  if (totalPages <= 0) return []
  const set = new Set<number>([0, totalPages - 1])
  for (let p = page - around; p <= page + around; p++) if (p >= 0 && p < totalPages) set.add(p)
  const pages = [...set].sort((a, b) => a - b)
  const out: (number | null)[] = []
  pages.forEach((p, i) => {
    if (i > 0 && p - pages[i - 1] > 1) out.push(null)
    out.push(p)
  })
  return out
}

/** 리스트 우측 상단: 한 페이지 건수 */
export function PageSizeSelect({
  size,
  onSize,
  sizes = PAGE_SIZES,
}: {
  size: number
  onSize: (size: number) => void
  sizes?: readonly number[]
}) {
  return (
    <label className="flex items-center gap-1 text-xs text-muted-foreground">
      한 페이지
      <Select aria-label="한 페이지 건수" value={size} onChange={(e) => onSize(Number(e.target.value))}>
        {sizes.map((s) => (
          <option key={s} value={s}>
            {s}건
          </option>
        ))}
      </Select>
    </label>
  )
}

/** 리스트 중앙 하단: 총 N건 + 페이지 이동. 건수를 바꾸면 첫 페이지로 (같은 번호가 다른 행을 가리키므로) */
export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
}: Pick<PagingProps, 'page' | 'totalPages' | 'totalElements' | 'onPage'>) {
  const last = Math.max(totalPages - 1, 0)
  return (
    <nav className="flex flex-wrap items-center justify-center gap-3 text-xs" aria-label="페이지 이동">
      <span className="text-muted-foreground">총 {formatNumber(totalElements)}건</span>
      {totalPages > 1 && (
        <div className="flex items-center gap-1">
          <Button size="sm" variant="outline" disabled={page <= 0} onClick={() => onPage(0)} aria-label="첫 페이지">
            «
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={page <= 0}
            onClick={() => onPage(page - 1)}
            aria-label="이전 페이지"
          >
            ‹
          </Button>
          {pageWindow(page, totalPages).map((p, i) =>
            p === null ? (
              <span key={`gap-${i}`} className="px-1 text-muted-foreground">
                …
              </span>
            ) : (
              <Button
                key={p}
                size="sm"
                variant={p === page ? 'default' : 'outline'}
                aria-current={p === page ? 'page' : undefined}
                aria-label={`${p + 1}페이지`}
                onClick={() => onPage(p)}
              >
                {p + 1}
              </Button>
            ),
          )}
          <Button
            size="sm"
            variant="outline"
            disabled={page >= last}
            onClick={() => onPage(page + 1)}
            aria-label="다음 페이지"
          >
            ›
          </Button>
          <Button size="sm" variant="outline" disabled={page >= last} onClick={() => onPage(last)} aria-label="마지막 페이지">
            »
          </Button>
        </div>
      )}
    </nav>
  )
}

/**
 * 페이징 리스트 틀 (ADR 0011): 우측 상단 '한 페이지 N건', 가운데 리스트, 중앙 하단 '총 N건 + 페이지 이동'.
 * toolbar를 주면 같은 줄 왼쪽에 둔다(일괄 작업 바 등).
 */
export function PagedList({
  paging,
  toolbar,
  className,
  children,
}: {
  paging: PagingProps
  toolbar?: React.ReactNode
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={cn('space-y-2', className)}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0 flex-1">{toolbar}</div>
        <PageSizeSelect size={paging.size} onSize={paging.onSize} />
      </div>
      {children}
      <Pagination
        page={paging.page}
        totalPages={paging.totalPages}
        totalElements={paging.totalElements}
        onPage={paging.onPage}
      />
    </div>
  )
}
