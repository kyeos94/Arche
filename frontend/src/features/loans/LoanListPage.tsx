import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatMoney, formatNumber } from '@/lib/format'
import { StatusTabs } from './StatusTabs'

/** 계약 목록: 검색 조건은 URL 쿼리스트링과 동기화 (뒤로 가기·새로고침·링크 공유) */
export function LoanListPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const status = params.get('status') ?? ''
  const q = params.get('q') ?? ''
  const page = Number(params.get('page') ?? 0)
  const [draft, setDraft] = useState(q)

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
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">계약 목록</h1>
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
      </div>
      <StatusTabs counts={counts.data} value={status} onChange={(s) => update({ status: s, page: '' })} />
      <ErrorMessage error={list.error} />
      <Table>
        <THead>
          <TR>
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
          {data?.content.length === 0 && <EmptyRow colSpan={8} />}
          {data?.content.map((r) => (
            <TR key={r.id} className="cursor-pointer" onClick={() => navigate(`/loans/${r.id}`)}>
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
