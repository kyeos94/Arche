/**
 * 계약 목록 검색 조건 ↔ URL 쿼리스트링 (ADR 0011). 뒤로 가기·새로고침·링크 공유에도 같은 결과가 나오게 URL이 기준이다.
 */
import type { LoanSearch } from '@/api/endpoints'

/** 상세 검색 조건 (상태 탭·페이지·정렬 제외) */
export const FILTER_KEYS = [
  'productCode',
  'assignedStaffId',
  'unassigned',
  'principalMin',
  'principalMax',
  'payoffMin',
  'payoffMax',
  'delinquencyMin',
  'delinquencyMax',
  'nextDueFrom',
  'nextDueTo',
  'disbursedFrom',
  'disbursedTo',
  'keywordType',
  'keyword',
] as const

export type FilterKey = (typeof FILTER_KEYS)[number]
export type FilterValues = Partial<Record<FilterKey, string>>

export const KEYWORD_TYPES = [
  { code: 'ACCOUNT_NO', label: '계약번호' },
  { code: 'NAME', label: '고객 성명' },
  { code: 'RESIDENT_NO', label: '주민등록번호' },
  { code: 'PHONE', label: '휴대폰번호' },
] as const

export const PAGE_SIZES = [20, 50, 100, 200] as const
export const DEFAULT_PAGE_SIZE = 50

/** 서버가 정렬할 수 있는 칸 (LoanQuery.SORT_COLUMNS와 같음) */
export const SERVER_SORT_KEYS = [
  'accountNo',
  'productCode',
  'status',
  'initialPrincipal',
  'principalBalance',
  'payoffAmount',
  'delinquencyDays',
  'nextDueDate',
  'disbursedOn',
] as const

export function readFilter(params: URLSearchParams): FilterValues {
  const out: FilterValues = {}
  for (const k of FILTER_KEYS) {
    const v = params.get(k)
    if (v) out[k] = v
  }
  // 예전 링크(q=)는 상세 검색의 계약번호/주민번호 자동 판단으로 보여 준다
  if (!out.keyword && params.get('q')) {
    out.keyword = params.get('q') ?? undefined
  }
  return out
}

export function readSearch(params: URLSearchParams): LoanSearch {
  const size = Number(params.get('size') ?? DEFAULT_PAGE_SIZE)
  const filter = readFilter(params)
  return {
    ...filter,
    // q는 keyword로 옮겨 보냈으므로 keywordType이 없으면 서버가 AUTO로 판단하게 q로 보낸다
    ...(filter.keyword && !filter.keywordType ? { keyword: undefined, q: filter.keyword } : {}),
    status: params.get('status') ?? undefined,
    page: Number(params.get('page') ?? 0),
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_PAGE_SIZE,
    sort: params.get('sort') ?? undefined,
    dir: params.get('dir') ?? undefined,
  }
}

/** 걸린 상세 조건 수 (검색어는 한 개로 셈, 미배정과 담당자는 하나) */
export function activeCount(f: FilterValues): number {
  let n = 0
  if (f.keyword) n++
  if (f.productCode) n++
  if (f.unassigned || f.assignedStaffId) n++
  for (const [a, b] of [
    ['principalMin', 'principalMax'],
    ['payoffMin', 'payoffMax'],
    ['delinquencyMin', 'delinquencyMax'],
    ['nextDueFrom', 'nextDueTo'],
    ['disbursedFrom', 'disbursedTo'],
  ] as const) {
    if (f[a] || f[b]) n++
  }
  return n
}

/** 엑셀 다운로드 요청의 filter (서버 LoanFilter와 같은 이름·타입) */
export function exportFilter(status: string, f: FilterValues): Record<string, string | number | boolean | null> {
  const num = (v?: string) => (v ? Number(v) : null)
  return {
    status: status || null,
    productCode: f.productCode ?? null,
    assignedStaffId: num(f.assignedStaffId),
    unassigned: f.unassigned === 'true',
    principalMin: num(f.principalMin),
    principalMax: num(f.principalMax),
    payoffMin: num(f.payoffMin),
    payoffMax: num(f.payoffMax),
    delinquencyMin: num(f.delinquencyMin),
    delinquencyMax: num(f.delinquencyMax),
    nextDueFrom: f.nextDueFrom ?? null,
    nextDueTo: f.nextDueTo ?? null,
    disbursedFrom: f.disbursedFrom ?? null,
    disbursedTo: f.disbursedTo ?? null,
    keywordType: f.keyword ? (f.keywordType ?? 'AUTO') : null,
    keyword: f.keyword ?? null,
  }
}
