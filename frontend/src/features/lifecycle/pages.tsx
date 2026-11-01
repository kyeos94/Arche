import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { lifecycleApi, lifecycleKeys } from '@/api/lifecycle'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Select } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatMoney } from '@/lib/format'
import { ReasonDialog, RequestsTable, useInvalidateLifecycle } from './shared'

/** 상각 후보 (R39): 기한이익상실 후 180일 경과 + 기준일 포함 직전 90일 입금 없음. 본부장이 확정 */
export function ChargeOffCandidatesPage() {
  const q = useQuery({ queryKey: lifecycleKeys.candidates, queryFn: lifecycleApi.candidates })
  const invalidate = useInvalidateLifecycle()
  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">상각 후보</h1>
      <Alert tone="info">
        업무일자 기준으로 기한이익상실 후 180일이 지나고 최근 90일(당일 포함) 입금이 없는 계약입니다. 확정하면 확정일까지 이자를
        정산하고 장부에서 제거하며, 채권은 존속해 회수는 계속합니다 (R39).
      </Alert>
      <ErrorMessage error={q.error} />
      <Table>
        <THead>
          <TR>
            <TH>계약번호</TH>
            <TH>상품</TH>
            <TH>기한이익상실일</TH>
            <TH className="text-right">경과일</TH>
            <TH>마지막 입금일</TH>
            <TH className="text-right">청구 잔액</TH>
            <TH />
          </TR>
        </THead>
        <TBody>
          {q.data?.length === 0 && <EmptyRow colSpan={7} />}
          {q.data?.map((c) => (
            <TR key={c.accountId}>
              <TD>
                <Link className="text-primary hover:underline" to={`/loans/${c.accountId}`}>
                  {c.accountNo}
                </Link>
              </TD>
              <TD>{c.productCode}</TD>
              <TD>{formatDate(c.acceleratedOn)}</TD>
              <TD className="num">{c.daysSinceAcceleration}일</TD>
              <TD>{formatDate(c.lastPaymentOn)}</TD>
              <TD className="num">{formatMoney(c.claim)}</TD>
              <TD>
                <ReasonDialog
                  trigger="상각 확정"
                  title={`${c.accountNo} 상각 확정`}
                  description="확정일 = 업무일자. 결재 사유를 남기세요."
                  submitLabel="상각 확정"
                  onSubmit={async (reason) => {
                    await lifecycleApi.chargeOff(c.accountId, reason)
                    await invalidate(c.accountId)
                  }}
                />
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </div>
  )
}

/** 수기 조정·상계·상태 되돌리기 결재 (R42~R44): 채권팀장·본부장 요청 → 본부장 승인 (작성자 ≠ 승인자) */
export function AdjustmentsPage() {
  const [status, setStatus] = useState('REQUESTED')
  const q = useQuery({ queryKey: lifecycleKeys.requests(status), queryFn: () => lifecycleApi.requests(status) })
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">조정·상계·되돌리기 결재</h1>
        <Select className="w-32" aria-label="상태" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="REQUESTED">승인대기</option>
          <option value="APPLIED">처리완료</option>
          <option value="CONSUMED">사용됨</option>
          <option value="REJECTED">반려</option>
          <option value="ALL">전체</option>
        </Select>
      </div>
      <ErrorMessage error={q.error} />
      {q.data && <RequestsTable rows={q.data} showAccount />}
    </div>
  )
}
