import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatMoney, formatNumber, statusLabel } from '@/lib/format'
import { useAuth } from '@/features/auth/auth'
import { ExcelDownloadDialog } from '@/features/reports/ExcelDownloadDialog'
import { StatusTabs } from './StatusTabs'
import type { LoanListItem } from '@/api/types'
import { BulkActionBar } from './bulk/BulkActionBar'

/** 계약 목록: 검색 조건은 URL 쿼리스트링과 동기화 (뒤로 가기·새로고침·링크 공유) */
export function LoanListPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const { can } = useAuth()
  const status = params.get('status') ?? ''
  const q = params.get('q') ?? ''
  const page = Number(params.get('page') ?? 0)
  const [draft, setDraft] = useState(q)
  // 일괄 작업용 선택 (ADR 0010): 페이지를 넘겨도 유지하고, 상태·검색어가 바뀌면 비운다
  const filterKey = `${status}|${q}`
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

  const search = { status, q, page, size: 50 }
  const counts = useQuery({ queryKey: queryKeys.statusCounts, queryFn: endpoints.statusCounts })
  const list = useQuery({
    queryKey: queryKeys.loans(search),
    queryFn: () => endpoints.loans(search),
    placeholderData: keepPreviousData,
  })

  const update = (next: Record<string, string>) => {
    const merged = new URLSearchParams(params)
    for (const [k, v] of Object.entries(next)) {
      if (v) merged.set(k, v)
      else merged.delete(k)
    }
    setParams(merged)
  }

  const data = list.data
  const pageRows = data?.content ?? []
  const pageAllSelected = pageRows.length > 0 && pageRows.every((r) => selectedMap.has(r.id))
  const pageSomeSelected = pageRows.some((r) => selectedMap.has(r.id))
  const togglePage = (on: boolean) => {
    const next = new Map(selectedMap)
    pageRows.forEach((r) => (on ? next.set(r.id, r) : next.delete(r.id)))
    setSelected(next)
  }
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">계약 목록</h1>
        <div className="flex gap-2">
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              update({ q: draft.trim(), page: '' })
            }}
          >
            <Input
              className="w-64"
              placeholder="계약번호 또는 주민등록번호"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
            <Button type="submit" variant="outline">
              검색
            </Button>
          </form>
          {can('excel.download') && (
            <ExcelDownloadDialog
              title="계약 목록 엑셀 다운로드"
              summary={`현재 검색 조건 (상태: ${status ? statusLabel(status) : '전체'}${q ? ', 검색어 있음' : ''})의 계약 전체를 내려받습니다. 화면과 같은 조회 범위가 적용됩니다.`}
              onDownload={(reason) => endpoints.exportLoans({ status: status || undefined, q: q || undefined, reason })}
            />
          )}
        </div>
      </div>
      <StatusTabs counts={counts.data} value={status} onChange={(s) => update({ status: s, page: '' })} />
      <ErrorMessage error={list.error} />
      <BulkActionBar selected={selected} onClear={() => setSelected(new Map())} />
      <Table>
        <THead>
          <TR>
            <TH className="w-8">
              <input
                type="checkbox"
                aria-label="이 페이지 모두 선택"
                checked={pageAllSelected}
                ref={(el) => {
                  if (el) el.indeterminate = !pageAllSelected && pageSomeSelected
                }}
                onChange={(e) => togglePage(e.target.checked)}
                className="h-4 w-4 accent-primary"
              />
            </TH>
            <TH>계약번호</TH>
            <TH>고객명</TH>
            <TH>상품</TH>
            <TH>상태</TH>
            <TH className="text-right">원금 잔액</TH>
            <TH className="text-right">완제 금액</TH>
            <TH className="text-right">연체일수</TH>
            <TH>다음 납입일</TH>
          </TR>
        </THead>
        <TBody>
          {data?.content.length === 0 && <EmptyRow colSpan={9} />}
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
              <TD className="num">{formatMoney(r.principalBalance)}</TD>
              <TD className="num">{formatMoney(r.payoffAmount)}</TD>
              <TD className="num">{r.delinquencyDays > 0 ? `${formatNumber(r.delinquencyDays)}일` : '-'}</TD>
              <TD>{formatDate(r.nextDueDate)}</TD>
            </TR>
          ))}
        </TBody>
      </Table>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 text-xs">
          <Button
            size="sm"
            variant="outline"
            disabled={page <= 0}
            onClick={() => update({ page: String(page - 1) })}
          >
            이전
          </Button>
          <span>
            {page + 1} / {data.totalPages} (총 {formatNumber(data.totalElements)}건)
          </span>
          <Button
            size="sm"
            variant="outline"
            disabled={page + 1 >= data.totalPages}
            onClick={() => update({ page: String(page + 1) })}
          >
            다음
          </Button>
        </div>
      )}
    </div>
  )
}
