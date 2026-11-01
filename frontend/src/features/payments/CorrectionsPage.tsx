import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { endpoints, queryKeys } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { useInvalidateLoan } from '@/features/loans/hooks'
import { formatDate, formatMoney, statusLabel } from '@/lib/format'
import { useSort } from '@/lib/useSort'

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
  const { sorted, header } = useSort(q.data, {
    correctionNo: (c) => c.correctionNo,
    type: (c) => typeLabels[c.type] ?? c.type,
    paymentId: (c) => c.originalPaymentId,
    target: (c) => c.targetAccountId,
    amount: (c) => c.correctedAmount,
    reason: (c) => c.reason,
    afterClose: (c) => (c.afterClose ? '예' : '아니오'),
    requestedOn: (c) => c.requestedOn,
    status: (c) => statusLabel(c.status),
  })

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
            <SortTH {...header('correctionNo')}>정정번호</SortTH>
            <SortTH {...header('type')}>유형</SortTH>
            <SortTH {...header('paymentId')}>원 입금 #</SortTH>
            <SortTH {...header('target')}>대상 계약</SortTH>
            <SortTH {...header('amount')} className="text-right">
              정정 금액
            </SortTH>
            <SortTH {...header('reason')}>사유</SortTH>
            <SortTH {...header('afterClose')}>마감 후</SortTH>
            <SortTH {...header('requestedOn')}>요청일</SortTH>
            <SortTH {...header('status')}>상태</SortTH>
            <TH />
          </TR>
        </THead>
        <TBody>
          {q.data?.length === 0 && <EmptyRow colSpan={10} />}
          {sorted.map((c) => (
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
