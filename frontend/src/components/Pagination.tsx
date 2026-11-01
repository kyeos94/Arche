import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { formatNumber } from '@/lib/format'

/** 현재 쪽 주변으로 보여 줄 쪽 번호 (처음·끝은 항상, 사이가 비면 null = …) */
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

/**
 * 쪽 이동 + 한 쪽 건수 선택 (ADR 0011). page는 0부터.
 * 건수를 바꾸면 첫 쪽으로 돌아간다(같은 쪽 번호가 다른 행을 가리키므로).
 */
export function Pagination({
  page,
  totalPages,
  totalElements,
  size,
  sizes,
  onPage,
  onSize,
}: {
  page: number
  totalPages: number
  totalElements: number
  size: number
  sizes: readonly number[]
  onPage: (page: number) => void
  onSize: (size: number) => void
}) {
  const last = Math.max(totalPages - 1, 0)
  return (
    <nav className="flex flex-wrap items-center justify-between gap-2 text-xs" aria-label="쪽 이동">
      <div className="flex items-center gap-2 text-muted-foreground">
        <span>총 {formatNumber(totalElements)}건</span>
        <label className="flex items-center gap-1">
          한 쪽에
          <Select aria-label="한 쪽 건수" value={size} onChange={(e) => onSize(Number(e.target.value))}>
            {sizes.map((s) => (
              <option key={s} value={s}>
                {s}건
              </option>
            ))}
          </Select>
        </label>
      </div>
      {totalPages > 1 && (
        <div className="flex items-center gap-1">
          <Button size="sm" variant="outline" disabled={page <= 0} onClick={() => onPage(0)} aria-label="첫 쪽">
            «
          </Button>
          <Button size="sm" variant="outline" disabled={page <= 0} onClick={() => onPage(page - 1)} aria-label="이전 쪽">
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
                aria-label={`${p + 1}쪽`}
                onClick={() => onPage(p)}
              >
                {p + 1}
              </Button>
            ),
          )}
          <Button size="sm" variant="outline" disabled={page >= last} onClick={() => onPage(page + 1)} aria-label="다음 쪽">
            ›
          </Button>
          <Button size="sm" variant="outline" disabled={page >= last} onClick={() => onPage(last)} aria-label="마지막 쪽">
            »
          </Button>
        </div>
      )}
    </nav>
  )
}
