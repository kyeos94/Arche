import { useQuery } from '@tanstack/react-query'
import { endpoints, queryKeys } from '@/api/endpoints'
import { PagedList } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatMoney, statusLabel } from '@/lib/format'
import { usePaging } from '@/lib/usePaging'
import { useSort } from '@/lib/useSort'
import { CorrectionDialog } from './CorrectionDialog'

export function PaymentsTab({ loanId }: { loanId: number }) {
  const { can } = useAuth()
  const q = useQuery({ queryKey: queryKeys.loanPayments(loanId), queryFn: () => endpoints.payments(loanId) })
  const canCorrect = can('payment.register') || can('payment.cancel.closed')
  const { sorted, header } = useSort(q.data, {
    id: (p) => p.id,
    valueDate: (p) => p.valueDate,
    businessDate: (p) => p.businessDate,
    amount: (p) => p.amount,
    depositor: (p) => p.depositorName,
    payerType: (p) => statusLabel(p.payerType),
    channel: (p) => statusLabel(p.channel),
    bankTxId: (p) => p.bankTxId,
    status: (p) => statusLabel(p.status),
  })
  const paging = usePaging(sorted)
  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  return (
    <div className="space-y-3">
      <PagedList paging={paging.props}>
        <Table>
          <THead>
            <TR>
              <SortTH {...header('id')}>입금 #</SortTH>
              <SortTH {...header('valueDate')}>입금일(은행)</SortTH>
              <SortTH {...header('businessDate')}>등록 업무일</SortTH>
              <SortTH {...header('amount')} className="text-right">
                금액
              </SortTH>
              <SortTH {...header('depositor')}>입금자</SortTH>
              <SortTH {...header('payerType')}>입금자 구분</SortTH>
              <SortTH {...header('channel')}>채널</SortTH>
              <SortTH {...header('bankTxId')}>거래고유번호</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              <TH>취소·정정</TH>
            </TR>
          </THead>
          <TBody>
            {q.data.length === 0 && <EmptyRow colSpan={10} />}
            {paging.rows.map((p) => (
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
                    <CorrectionDialog
                      loanId={loanId}
                      payment={p}
                      type="CANCEL"
                      groupParentId={p.parentPaymentId ?? undefined}
                    />
                  )}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </PagedList>
      <SubrogationCard loanId={loanId} />
    </div>
  )
}

/** 대위변제 기록 (R49) */
function SubrogationCard({ loanId }: { loanId: number }) {
  const q = useQuery({ queryKey: queryKeys.loanSubrogations(loanId), queryFn: () => endpoints.subrogations(loanId) })
  const { sorted, header } = useSort(q.data, {
    paymentId: (c) => c.paymentId,
    valueDate: (c) => c.valueDate,
    payerType: (c) => statusLabel(c.payerType),
    payer: (c) => c.payerNameMasked,
    ref: (c) => c.payerRef,
    amount: (c) => c.amount,
    status: (c) => statusLabel(c.status),
    cancelReason: (c) => c.cancelReason,
  })
  const paging = usePaging(sorted)
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
        <PagedList paging={paging.props}>
          <Table>
            <THead>
              <TR>
                <SortTH {...header('paymentId')}>입금 #</SortTH>
                <SortTH {...header('valueDate')}>입금일</SortTH>
                <SortTH {...header('payerType')}>구분</SortTH>
                <SortTH {...header('payer')}>입금자</SortTH>
                <SortTH {...header('ref')}>참조</SortTH>
                <SortTH {...header('amount')} className="text-right">
                  구상권 대상
                </SortTH>
                <SortTH {...header('status')}>상태</SortTH>
                <SortTH {...header('cancelReason')}>취소 사유</SortTH>
              </TR>
            </THead>
            <TBody>
              {paging.rows.map((c) => (
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
        </PagedList>
      </CardContent>
    </Card>
  )
}
