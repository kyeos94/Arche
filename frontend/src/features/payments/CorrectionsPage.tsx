import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { endpoints, queryKeys } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { useInvalidateLoan } from '@/features/loans/hooks'
import { formatDate, formatMoney } from '@/lib/format'

const typeLabels: Record<string, string> = { CANCEL: '취소', MOVE: '계약 이동', AMOUNT_FIX: '금액 정정' }

/** 마감 후 정정 승인 (R28: 시스템관리자 요청 → 채권팀장 승인) */
export function CorrectionsPage() {
  const { can } = useAuth()
  const [status, setStatus] = useState('REQUESTED')
  const invalidate = useInvalidateLoan()
  const q = useQuery({ queryKey: queryKeys.corrections(status), queryFn: () => endpoints.corrections(status) })
  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: number; approve: boolean }) => endpoints.approveCorrection(id, approve),
    onSuccess: () => invalidate(),
  })
  const canApprove = can('payment.cancel.closed.approve')

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">입금 취소·정정</h1>
        <Select className="w-32" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="REQUESTED">승인대기</option>
          <option value="APPLIED">처리완료</option>
          <option value="REJECTED">반려</option>
        </Select>
      </div>
      <ErrorMessage error={q.error ?? decide.error} />
      <Table>
        <THead>
          <TR>
            <TH>정정번호</TH>
            <TH>유형</TH>
            <TH>원 입금 #</TH>
            <TH>대상 계약</TH>
            <TH className="text-right">정정 금액</TH>
            <TH>사유</TH>
            <TH>마감 후</TH>
            <TH>요청일</TH>
            <TH>상태</TH>
            <TH />
          </TR>
        </THead>
        <TBody>
          {q.data?.length === 0 && <EmptyRow colSpan={10} />}
          {q.data?.map((c) => (
            <TR key={c.id}>
              <TD>{c.correctionNo}</TD>
              <TD>{typeLabels[c.type] ?? c.type}</TD>
              <TD>{c.originalPaymentId}</TD>
              <TD>{c.targetAccountId ?? '-'}</TD>
              <TD className="num">{formatMoney(c.correctedAmount)}</TD>
              <TD>{c.reason}</TD>
              <TD>{c.afterClose ? '예' : '아니오'}</TD>
              <TD>{formatDate(c.requestedOn)}</TD>
              <TD>
                <StatusBadge status={c.status} />
              </TD>
              <TD>
                {canApprove && c.status === 'REQUESTED' && (
                  <div className="flex gap-1">
                    <Button
                      size="sm"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ id: c.id, approve: true })}
                    >
                      승인
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ id: c.id, approve: false })}
                    >
                      반려
                    </Button>
                  </div>
                )}
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </div>
  )
}
