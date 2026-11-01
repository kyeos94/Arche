/**
 * 표 머리글을 눌러 정렬 (ADR 0011). 화면에 이미 받은 행을 브라우저에서 정렬한다.
 * 서버에서 페이지로 나눠 받는 목록(계약 목록)은 서버 정렬을 쓰고, 같은 SortTH 머리글만 공유한다.
 *
 * 누를 때마다: 오름차순 → 내림차순 → 정렬 해제(원래 순서).
 */
import { useState } from 'react'

export type SortDirection = 'asc' | 'desc'

export interface SortState {
  key: string
  direction: SortDirection
}

export type SortValue = string | number | boolean | null | undefined

/** 머리글(SortTH)에 넘길 값 */
export interface SortHeaderProps {
  sortKey: string
  sort: SortState | null
  onSort: (key: string) => void
}

/** 오름차순 → 내림차순 → 해제 */
export function nextSort(current: SortState | null, key: string): SortState | null {
  if (!current || current.key !== key) return { key, direction: 'asc' }
  if (current.direction === 'asc') return { key, direction: 'desc' }
  return null
}

const collator = new Intl.Collator('ko', { numeric: true, sensitivity: 'base' })

/** 빈 값은 방향과 관계없이 항상 뒤로. 숫자는 숫자로, 글자는 한글 순서(숫자 섞인 글자는 자연 정렬)로 */
export function compareValues(a: SortValue, b: SortValue, direction: SortDirection): number {
  const emptyA = a === null || a === undefined || a === ''
  const emptyB = b === null || b === undefined || b === ''
  if (emptyA || emptyB) return emptyA === emptyB ? 0 : emptyA ? 1 : -1
  const sign = direction === 'asc' ? 1 : -1
  if (typeof a === 'number' && typeof b === 'number') return (a - b) * sign
  if (typeof a === 'boolean' && typeof b === 'boolean') return (Number(a) - Number(b)) * sign
  return collator.compare(String(a), String(b)) * sign
}

export function sortRows<T>(
  rows: readonly T[],
  sort: SortState | null,
  accessors: Record<string, (row: T) => SortValue>,
): T[] {
  const get = sort ? accessors[sort.key] : undefined
  if (!sort || !get) return [...rows]
  // 같은 값끼리는 원래 순서를 지킨다 (Array.prototype.sort는 안정 정렬)
  return [...rows].sort((x, y) => compareValues(get(x), get(y), sort.direction))
}

/**
 * 브라우저 정렬 훅.
 *
 * @example
 * const { sorted, header } = useSort(rows, { name: (r) => r.name, amount: (r) => r.amount })
 * <SortTH {...header('amount')}>금액</SortTH>
 * {sorted.map(...)}
 */
export function useSort<T>(
  rows: readonly T[] | undefined,
  accessors: Record<string, (row: T) => SortValue>,
  initial: SortState | null = null,
) {
  const [sort, setSort] = useState<SortState | null>(initial)
  const sorted = sortRows(rows ?? [], sort, accessors)
  const header = (key: string): SortHeaderProps => ({
    sortKey: key,
    sort,
    onSort: (k) => setSort((s) => nextSort(s, k)),
  })
  return { sorted, sort, setSort, header }
}
