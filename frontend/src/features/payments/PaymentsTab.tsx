import { useQuery } from '@tanstack/react-query'
import { endpoints, queryKeys } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatMoney, statusLabel } from '@/lib/format'
import { CorrectionDialog } from './CorrectionDialog'

export function PaymentsTab({ loanId }: { loanId: number }) {
  const { can } = useAuth()
  const q = useQuery({ queryKey: queryKeys.loanPayments(loanId), queryFn: () => endpoints.payments(loanId) })
  const canCorrect = can('payment.register') || can('payment.cancel.closed')
  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  return (
    <div className="space-y-3">
      <Table>
        <THead>
          <TR>
            <TH>입금 #</TH>
            <TH>입금일(은행)</TH>
            <TH>등록 업무일</TH>
            <TH className="text-right">금액</TH>
            <TH>입금자</TH>
            <TH>입금자 구분</TH>
            <TH>채널</TH>
            <TH>거래고유번호</TH>
            <TH>상태</TH>
            <TH>취소·정정</TH>
          </TR>
        </THead>
        <TBody>
          {q.data.length === 0 && <EmptyRow colSpan={10} />}
          {q.data.map((p) => (
            <TR key={p.id}>
              <TD>
                {p.id}
                {!!p.parentPaymentId && (
                  <span className="ml-1 text-[11px] text-muted-foreground">(일괄배분 #{p.parentPaymentId})</span>
                )}
              </TD>
              <TD>{formatDate(p.valueDate)}</TD>
              <TD>{formatDate(p.businessDate)}</TD>
              <TD className="num">{formatMoney(p.amount)}</TD>
              <TD>{p.depositorName ?? '-'}</TD>
              <TD>
                {statusLabel(p.payerType)}
                {p.payerType !== 'CUSTOMER' && p.payerNameMasked && (
                  <span className="ml-1 text-muted-foreground">{p.payerNameMasked}</span>
                )}
              </TD>
              <TD>{statusLabel(p.channel)}</TD>
              <TD className="text-muted-foreground">{p.bankTxId}</TD>
              <TD>
                <StatusBadge status={p.status} />
              </TD>
              <TD>
                {canCorrect && p.status === 'ALLOCATED' && !p.parentPaymentId && (
                  <div className="flex gap-1">
                    <CorrectionDialog loanId={loanId} payment={p} type="CANCEL" />
                    <CorrectionDialog loanId={loanId} payment={p} type="MOVE" />
                    <CorrectionDialog loanId={loanId} payment={p} type="AMOUNT_FIX" />
                  </div>
                )}
                {canCorrect && p.status === 'ALLOCATED' && !!p.parentPaymentId && (
                  <CorrectionDialog loanId={loanId} payment={p} type="CANCEL" groupParentId={p.parentPaymentId ?? undefined} />
                )}
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
      <SubrogationCard loanId={loanId} />
    </div>
  )
}

/** 대위변제 기록 (R49) */
function SubrogationCard({ loanId }: { loanId: number }) {
  const q = useQuery({ queryKey: queryKeys.loanSubrogations(loanId), queryFn: () => endpoints.subrogations(loanId) })
  if (!q.data || q.data.length === 0) return <ErrorMessage error={q.error} />
  return (
    <Card>
      <CardHeader>
        <CardTitle>대위변제 기록 (보증인·제3자 입금)</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-xs text-muted-foreground">
          구상권 대상 금액 = 입금액. 회사는 기록만 남기며, 입금이 취소·정정되면 함께 취소됩니다 (R49).
        </p>
        <Table>
          <THead>
            <TR>
              <TH>입금 #</TH>
              <TH>입금일</TH>
              <TH>구분</TH>
              <TH>입금자</TH>
              <TH>참조</TH>
              <TH className="text-right">구상권 대상</TH>
              <TH>상태</TH>
              <TH>취소 사유</TH>
            </TR>
          </THead>
          <TBody>
            {q.data.map((c) => (
              <TR key={c.id}>
                <TD>{c.paymentId}</TD>
                <TD>{formatDate(c.valueDate)}</TD>
                <TD>{statusLabel(c.payerType)}</TD>
                <TD>{c.payerNameMasked}</TD>
                <TD>{c.payerRef ?? '-'}</TD>
                <TD className="num">{formatMoney(c.amount)}</TD>
                <TD>
                  <StatusBadge status={c.status} />
                </TD>
                <TD className="text-muted-foreground">{c.cancelReason ?? '-'}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}
