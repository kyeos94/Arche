import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { LoanListItem } from '@/api/types'
import { PagedList } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { ExcelDownloadDialog } from '@/features/reports/ExcelDownloadDialog'
import { formatDate, formatMoney, formatNumber, statusLabel } from '@/lib/format'
import { type SortHeaderProps, type SortState, nextSort } from '@/lib/useSort'
import { BulkActionBar } from './bulk/BulkActionBar'
import { LoanFilterPanel } from './LoanFilterPanel'
import { DEFAULT_PAGE_SIZE, FILTER_KEYS, type FilterValues, activeCount, exportFilter, readFilter, readSearch } from './loanFilter'
import { StatusTabs } from './StatusTabs'

/**
 * 계약 목록 (ADR 0011): 상태 탭 + 상세 검색 + 서버 정렬 + 쪽 이동. 조건은 모두 URL 쿼리스트링에 둔다
 * (뒤로 가기·새로고침·링크 공유). 일괄 작업 선택은 쪽을 넘겨도 유지하고 검색 조건이 바뀌면 비운다.
 */
export function LoanListPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const { can } = useAuth()
  const search = readSearch(params)
  const filter = readFilter(params)
  const status = search.status ?? ''
  const page = search.page ?? 0
  const sort: SortState | null =
    search.sort && (search.dir === 'asc' || search.dir === 'desc') ? { key: search.sort, direction: search.dir } : null

  // 선택은 검색 조건(상태 + 상세 조건)마다 따로: 조건이 바뀌면 안 보이는 계약이 선택에 남지 않게
  const filterKey = `${status}|${FILTER_KEYS.map((k) => filter[k] ?? '').join('|')}`
  const [selection, setSelection] = useState<{ key: string; items: Map<number, LoanListItem> }>({
    key: filterKey,
    items: new Map(),
  })
  const selectedMap = selection.key === filterKey ? selection.items : new Map<number, LoanListItem>()
  const selected = [...selectedMap.values()]
  const setSelected = (next: Map<number, LoanListItem>) => setSelection({ key: filterKey, items: next })
  const toggle = (row: LoanListItem, on: boolean) => {
    const next = new Map(selectedMap)
    if (on) next.set(row.id, row)
    else next.delete(row.id)
    setSelected(next)
  }

  const counts = useQuery({ queryKey: queryKeys.statusCounts, queryFn: endpoints.statusCounts })
  const list = useQuery({
    queryKey: queryKeys.loans(search),
    queryFn: () => endpoints.loans(search),
    placeholderData: keepPreviousData,
  })

  const update = (next: Record<string, string | undefined>) => {
    const merged = new URLSearchParams(params)
    for (const [k, v] of Object.entries(next)) {
      if (v) merged.set(k, v)
      else merged.delete(k)
    }
    setParams(merged)
  }
  const applyFilter = (values: FilterValues) => {
    const next: Record<string, string | undefined> = { page: undefined, q: undefined }
    for (const k of FILTER_KEYS) next[k] = values[k]
    update(next)
  }
  const header = (key: string): SortHeaderProps => ({
    sortKey: key,
    sort,
    onSort: (k) => {
      const s = nextSort(sort, k)
      update({ sort: s?.key, dir: s?.direction, page: undefined })
    },
  })

  const data = list.data
  const pageRows = data?.content ?? []
  const pageAllSelected = pageRows.length > 0 && pageRows.every((r) => selectedMap.has(r.id))
  const pageSomeSelected = pageRows.some((r) => selectedMap.has(r.id))
  const togglePage = (on: boolean) => {
    const next = new Map(selectedMap)
    pageRows.forEach((r) => (on ? next.set(r.id, r) : next.delete(r.id)))
    setSelected(next)
  }
  const conditions = activeCount(filter)
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">
          계약 목록
          {conditions > 0 && (
            <span className="ml-2 text-xs font-normal text-muted-foreground">상세 조건 {conditions}개 적용 중</span>
          )}
        </h1>
        {can('excel.download') && (
          <ExcelDownloadDialog
            title="계약 목록 엑셀 다운로드"
            summary={`현재 검색 조건 (상태: ${status ? statusLabel(status) : '전체'}${conditions ? `, 상세 조건 ${conditions}개` : ''})의 계약 전체를 내려받습니다. 화면과 같은 조회 범위가 적용됩니다.`}
            onDownload={(reason) => endpoints.exportLoans({ filter: exportFilter(status, filter), reason })}
          />
        )}
      </div>
      <LoanFilterPanel key={FILTER_KEYS.map((k) => filter[k] ?? '').join('|')} initial={filter} onApply={applyFilter} />
      <StatusTabs counts={counts.data} value={status} onChange={(s) => update({ status: s, page: undefined })} />
      <ErrorMessage error={list.error} />
      <PagedList
        toolbar={<BulkActionBar selected={selected} onClear={() => setSelected(new Map())} />}
        paging={{
          page,
          totalPages: data?.totalPages ?? 1,
          totalElements: data?.totalElements ?? 0,
          size: search.size ?? DEFAULT_PAGE_SIZE,
          onPage: (p) => update({ page: p > 0 ? String(p) : undefined }),
          onSize: (s) => update({ size: String(s), page: undefined }),
        }}
      >
        <Table>
          <THead>
            <TR>
              <TH className="w-8">
                <input
                  type="checkbox"
                  aria-label="이 쪽 모두 선택"
                  checked={pageAllSelected}
                  ref={(el) => {
                    if (el) el.indeterminate = !pageAllSelected && pageSomeSelected
                  }}
                  onChange={(e) => togglePage(e.target.checked)}
                  className="h-4 w-4 accent-primary"
                />
              </TH>
              <SortTH {...header('accountNo')}>계약번호</SortTH>
              <TH>고객명</TH>
              <SortTH {...header('productCode')}>상품</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              <SortTH {...header('initialPrincipal')} className="text-right">
                대출금액
              </SortTH>
              <SortTH {...header('principalBalance')} className="text-right">
                원금 잔액
              </SortTH>
              <SortTH {...header('payoffAmount')} className="text-right">
                완제 금액
              </SortTH>
              <SortTH {...header('delinquencyDays')} className="text-right">
                연체일수
              </SortTH>
              <SortTH {...header('nextDueDate')}>다음 납입일</SortTH>
              <SortTH {...header('disbursedOn')}>실행일</SortTH>
              <TH>담당자</TH>
            </TR>
          </THead>
          <TBody>
            {data?.content.length === 0 && <EmptyRow colSpan={12}>조건에 맞는 계약이 없습니다</EmptyRow>}
            {data?.content.map((r) => (
              <TR key={r.id} className="cursor-pointer" onClick={() => navigate(`/loans/${r.id}`)}>
                {/* 체크박스 칸을 눌러도 상세로 가지 않게 */}
                <TD onClick={(e) => e.stopPropagation()}>
                  <input
                    type="checkbox"
                    aria-label={`${r.accountNo} 선택`}
                    checked={selectedMap.has(r.id)}
                    onChange={(e) => toggle(r, e.target.checked)}
                    className="h-4 w-4 accent-primary"
                  />
                </TD>
                <TD className="font-medium text-primary">{r.accountNo}</TD>
                <TD>{r.customerName}</TD>
                <TD>{r.productCode}</TD>
                <TD>
                  <StatusBadge status={r.status} />
                </TD>
                <TD className="num">{formatMoney(r.initialPrincipal)}</TD>
                <TD className="num">{formatMoney(r.principalBalance)}</TD>
                <TD className="num">{formatMoney(r.payoffAmount)}</TD>
                <TD className="num">{r.delinquencyDays > 0 ? `${formatNumber(r.delinquencyDays)}일` : '-'}</TD>
                <TD>{formatDate(r.nextDueDate)}</TD>
                <TD>{formatDate(r.disbursedOn)}</TD>
                <TD>{r.assignedStaffName ?? <span className="text-muted-foreground">미배정</span>}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </PagedList>
    </div>
  )
}
