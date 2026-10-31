import { useQuery } from '@tanstack/react-query'
import { endpoints, queryKeys } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatMoney } from '@/lib/format'
import { CorrectionDialog } from './CorrectionDialog'

export function PaymentsTab({ loanId }: { loanId: number }) {
  const { can } = useAuth()
  const q = useQuery({ queryKey: queryKeys.loanPayments(loanId), queryFn: () => endpoints.payments(loanId) })
  const canCorrect = can('payment.register') || can('payment.cancel.closed')
  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  return (
    <Table>
      <THead>
        <TR>
          <TH>입금 #</TH>
          <TH>입금일(은행)</TH>
          <TH>등록 업무일</TH>
          <TH className="text-right">금액</TH>
          <TH>입금자</TH>
          <TH>채널</TH>
          <TH>거래고유번호</TH>
          <TH>상태</TH>
          <TH>취소·정정</TH>
        </TR>
      </THead>
      <TBody>
        {q.data.length === 0 && <EmptyRow colSpan={9} />}
        {q.data.map((p) => (
          <TR key={p.id}>
            <TD>{p.id}</TD>
            <TD>{formatDate(p.valueDate)}</TD>
            <TD>{formatDate(p.businessDate)}</TD>
            <TD className="num">{formatMoney(p.amount)}</TD>
            <TD>{p.depositorName ?? '-'}</TD>
            <TD>{p.channel}</TD>
            <TD className="text-muted-foreground">{p.bankTxId}</TD>
            <TD>
              <StatusBadge status={p.status} />
            </TD>
            <TD>
              {canCorrect && p.status === 'ALLOCATED' && (
                <div className="flex gap-1">
                  <CorrectionDialog loanId={loanId} payment={p} type="CANCEL" />
                  <CorrectionDialog loanId={loanId} payment={p} type="MOVE" />
                  <CorrectionDialog loanId={loanId} payment={p} type="AMOUNT_FIX" />
                </div>
              )}
            </TD>
          </TR>
        ))}
      </TBody>
    </Table>
  )
}
