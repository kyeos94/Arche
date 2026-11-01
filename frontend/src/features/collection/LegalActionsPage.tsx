import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatMoney, legalLabel } from '@/lib/format'
import { LegalStatusBadge } from './LegalTab'

/** 법조치 현황 (담당 범위 안, 최근 200건) */
export function LegalActionsPage() {
  const [openOnly, setOpenOnly] = useState(true)
  const q = useQuery({ queryKey: queryKeys.legalActions(openOnly), queryFn: () => endpoints.legalActions(openOnly) })
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">법조치 현황</h1>
        <label className="flex items-center gap-2 text-xs">
          <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} />
          진행 중만
        </label>
      </div>
      <ErrorMessage error={q.error} />
      <Table>
        <THead>
          <TR>
            <TH>법조치 번호</TH>
            <TH>계약번호</TH>
            <TH>종류</TH>
            <TH>단계</TH>
            <TH>법원</TH>
            <TH>사건 참조</TH>
            <TH>신청일</TH>
            <TH className="text-right">청구금액</TH>
            <TH className="text-right">비용 합계</TH>
          </TR>
        </THead>
        <TBody>
          {q.data?.length === 0 && <EmptyRow colSpan={9} />}
          {q.data?.map((a) => (
            <TR key={a.id}>
              <TD>{a.actionNo}</TD>
              <TD>
                <Link to={`/loans/${a.accountId}`} className="text-primary hover:underline">
                  {a.accountNo}
                </Link>
              </TD>
              <TD>{legalLabel(a.type)}</TD>
              <TD>
                <LegalStatusBadge status={a.status} />
              </TD>
              <TD>{a.courtName ?? '-'}</TD>
              <TD>{a.caseRef ?? '-'}</TD>
              <TD>{formatDate(a.filedOn)}</TD>
              <TD className="num">{formatMoney(a.claimAmount)}</TD>
              <TD className="num">{formatMoney(a.totalCost)}</TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </div>
  )
}
