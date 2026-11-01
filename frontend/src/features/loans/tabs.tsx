import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { InstallmentDto, LedgerDto } from '@/api/types'
import { ErrorMessage } from '@/components/ui/alert'
import { Badge, StatusBadge } from '@/components/ui/badge'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatDateTime, formatMoney, formatRate, statusLabel } from '@/lib/format'
import { cn } from '@/lib/utils'

// ------------------------------------------------------------------ 상환스케줄

function InstallmentTable({ rows }: { rows: InstallmentDto[] }) {
  return (
    <Table>
      <THead>
        <TR>
          <TH>회차</TH>
          <TH>납입일</TH>
          <TH>유예기한</TH>
          <TH className="text-right">원금</TH>
          <TH className="text-right">계획 이자</TH>
          <TH className="text-right">납입액</TH>
          <TH className="text-right">납부 원금</TH>
          <TH>상태</TH>
        </TR>
      </THead>
      <TBody>
        {rows.length === 0 && <EmptyRow colSpan={8} />}
        {rows.map((r) => (
          <TR key={`${r.scheduleVersion}-${r.seq}`}>
            <TD>{r.seq}</TD>
            <TD>{formatDate(r.dueDate)}</TD>
            <TD className={r.graceEnd !== r.dueDate ? 'text-amber-700' : 'text-muted-foreground'}>
              {formatDate(r.graceEnd)}
            </TD>
            <TD className="num">{formatMoney(r.principalDue)}</TD>
            <TD className="num">{formatMoney(r.plannedInterest)}</TD>
            <TD className="num">{formatMoney(r.plannedPayment)}</TD>
            <TD className="num">{formatMoney(r.principalPaid)}</TD>
            <TD>
              <StatusBadge status={r.status} />
            </TD>
          </TR>
        ))}
      </TBody>
    </Table>
  )
}

export function ScheduleTab({ loanId }: { loanId: number }) {
  const q = useQuery({ queryKey: queryKeys.loanSchedule(loanId), queryFn: () => endpoints.schedule(loanId) })
  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  const previous = Object.entries(q.data.previous).sort(([a], [b]) => Number(b) - Number(a))
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted-foreground">
        스케줄 버전 {q.data.currentVersion} · 이자는 납부 시점까지 실제 발생액으로 정산되며 표의 이자는 약정일 납부 가정
      </p>
      <InstallmentTable rows={q.data.current} />
      {previous.map(([version, rows]) => (
        <details key={version} className="rounded border p-2">
          <summary className="cursor-pointer text-xs">이전 스케줄 버전 {version} (재산출로 대체됨)</summary>
          <div className="mt-2">
            <InstallmentTable rows={rows} />
          </div>
        </details>
      ))}
    </div>
  )
}

// ------------------------------------------------------------------ 거래원장

const ledgerTypeLabels: Record<string, string> = {
  DISBURSE: '대출 실행',
  ACQUIRE: '채권 양수',
  REPAYMENT: '입금 충당',
  SUSPENSE_IN: '가수금 보관',
  SUSPENSE_APPLY: '가수금 충당',
  PREPAYMENT: '중도상환',
  COST_CHARGE: '비용 적재',
  REFUND: '반환 대기',
  REVERSAL: '역분개',
  WAIVER: '감면',
  ADJUSTMENT: '조정',
  CHARGE_OFF: '상각 (장부 제거)',
  SALE: '매각',
  REPURCHASE: '환매',
  SALE_TRANSFER: '양수인 이전 대상',
  SETOFF: '상계',
}

export function LedgerTab({ loanId }: { loanId: number }) {
  const [showReversed, setShowReversed] = useState(true)
  const q = useQuery({ queryKey: queryKeys.loanLedger(loanId), queryFn: () => endpoints.ledger(loanId) })
  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  const rows: LedgerDto[] = showReversed ? q.data : q.data.filter((r) => !r.superseded && r.type !== 'REVERSAL')
  return (
    <div className="space-y-2">
      <label className="flex items-center gap-2 text-xs">
        <input type="checkbox" checked={showReversed} onChange={(e) => setShowReversed(e.target.checked)} />
        취소된 거래와 역분개 표시
      </label>
      <Table>
        <THead>
          <TR>
            <TH>#</TH>
            <TH>유형</TH>
            <TH>기산일</TH>
            <TH>처리일</TH>
            <TH className="text-right">금액</TH>
            <TH className="text-right">원금</TH>
            <TH className="text-right">정상이자</TH>
            <TH className="text-right">가산이자</TH>
            <TH className="text-right">비용</TH>
            <TH className="text-right">수수료</TH>
            <TH className="text-right">가수금 증감</TH>
            <TH className="text-right">거래 후 잔액</TH>
            <TH>참조</TH>
          </TR>
        </THead>
        <TBody>
          {rows.length === 0 && <EmptyRow colSpan={13} />}
          {rows.map((r) => (
            <TR
              key={r.id}
              className={cn(r.superseded && 'text-muted-foreground line-through', r.type === 'REVERSAL' && 'bg-red-50/60')}
            >
              <TD>{r.id}</TD>
              <TD>
                {r.type === 'REVERSAL' ? (
                  <Badge className="border-red-200 bg-red-50 text-red-700">역분개 → #{r.reversalOfId}</Badge>
                ) : (
                  (ledgerTypeLabels[r.type] ?? r.type)
                )}
              </TD>
              <TD>{formatDate(r.valueDate)}</TD>
              <TD title={formatDateTime(r.postedAt)}>{formatDate(r.businessDate)}</TD>
              <TD className="num">{formatMoney(r.amount)}</TD>
              <TD className="num">{formatMoney(r.principal)}</TD>
              <TD className="num">{formatMoney(r.normalInterest)}</TD>
              <TD className="num">{formatMoney(r.lateInterest)}</TD>
              <TD className="num">{formatMoney(r.cost + r.preAcqInterest)}</TD>
              <TD className="num">{formatMoney(r.fee)}</TD>
              <TD className="num">{formatMoney(r.suspense)}</TD>
              <TD className="num">{formatMoney(r.principalBalanceAfter)}</TD>
              <TD className="text-muted-foreground">
                {r.sourceRef}
                {r.correctionId ? ` · 정정 #${r.correctionId}` : ''}
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </div>
  )
}

// ------------------------------------------------------------------ 조건변경이력

export function HistoryTab({ loanId }: { loanId: number }) {
  const q = useQuery({ queryKey: queryKeys.loanHistory(loanId), queryFn: () => endpoints.history(loanId) })
  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  const h = q.data
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <section>
        <h3 className="mb-1 text-xs font-semibold">금리 이력</h3>
        <Table>
          <THead>
            <TR>
              <TH>이율</TH>
              <TH>적용 시작</TH>
              <TH>적용 종료</TH>
              <TH>사유</TH>
              <TH>등록</TH>
            </TR>
          </THead>
          <TBody>
            {h.rateTerms.map((t) => (
              <TR key={t.validFrom}>
                <TD>{formatRate(t.rate)}</TD>
                <TD>{formatDate(t.validFrom)}</TD>
                <TD>{formatDate(t.validTo)}</TD>
                <TD>{t.reason}</TD>
                <TD>{formatDateTime(t.recordedAt)}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </section>
      <section>
        <h3 className="mb-1 text-xs font-semibold">약정일 이력</h3>
        <Table>
          <THead>
            <TR>
              <TH>약정일</TH>
              <TH>적용 시작</TH>
              <TH>적용 종료</TH>
              <TH>신청일</TH>
              <TH>사유</TH>
            </TR>
          </THead>
          <TBody>
            {h.dueDayTerms.map((t) => (
              <TR key={t.validFrom}>
                <TD>{t.dueDay}일</TD>
                <TD>{formatDate(t.validFrom)}</TD>
                <TD>{formatDate(t.validTo)}</TD>
                <TD>{formatDate(t.requestedOn)}</TD>
                <TD>{t.reason}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </section>
      <section className="lg:col-span-2">
        <h3 className="mb-1 text-xs font-semibold">기한이익상실 통지</h3>
        <Table>
          <THead>
            <TR>
              <TH>사유 발생일</TH>
              <TH>발송일</TH>
              <TH>도달일(간주)</TH>
              <TH>실제 도달일</TH>
              <TH>상실(예정)일</TH>
              <TH>상태</TH>
              <TH>종료일</TH>
            </TR>
          </THead>
          <TBody>
            {h.notices.length === 0 && <EmptyRow colSpan={7} />}
            {h.notices.map((n) => (
              <TR key={n.reasonDate}>
                <TD>{formatDate(n.reasonDate)}</TD>
                <TD>{formatDate(n.sentOn)}</TD>
                <TD>{formatDate(n.deemedArrivalOn)}</TD>
                <TD>{formatDate(n.actualArrivalOn)}</TD>
                <TD>{formatDate(n.accelerationDate)}</TD>
                <TD>
                  <StatusBadge status={n.status} />
                </TD>
                <TD>{formatDate(n.closedOn)}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </section>
      <section className="lg:col-span-2">
        <h3 className="mb-1 text-xs font-semibold">재생 이벤트 (입금·중도상환·약정일 변경·비용)</h3>
        <Table>
          <THead>
            <TR>
              <TH>참조</TH>
              <TH>유형</TH>
              <TH>일자</TH>
              <TH className="text-right">금액</TH>
              <TH>입금</TH>
              <TH>상태</TH>
              <TH>취소일·사유</TH>
              <TH>등록</TH>
            </TR>
          </THead>
          <TBody>
            {h.events.length === 0 && <EmptyRow colSpan={8} />}
            {h.events.map((e) => (
              <TR key={e.id} className={cn(e.status === 'CANCELLED' && 'text-muted-foreground line-through')}>
                <TD>{e.ref}</TD>
                <TD>{e.type}</TD>
                <TD>{formatDate(e.eventDate)}</TD>
                <TD className="num">
                  {e.newDueDay ? `${e.newDueDay}일` : e.amount === null ? '전액' : formatMoney(e.amount)}
                </TD>
                <TD>{e.paymentId ?? '-'}</TD>
                <TD>{statusLabel(e.status)}</TD>
                <TD>{e.cancelledOn ? `${formatDate(e.cancelledOn)} ${e.reason ?? ''}` : '-'}</TD>
                <TD>
                  {e.createdBy} {formatDateTime(e.createdAt)}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </section>
    </div>
  )
}
