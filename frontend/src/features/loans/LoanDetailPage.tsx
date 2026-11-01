import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { LoanDetail } from '@/api/types'
import { ErrorMessage } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Can } from '@/features/auth/auth'
import { LegalTab } from '@/features/collection/LegalTab'
import { PaymentsTab } from '@/features/payments/PaymentsTab'
import { formatDate, formatMoney, formatRate } from '@/lib/format'
import { CollateralTab } from '@/features/collateral/CollateralTab'
import { TimelinePanel } from '@/features/communication/TimelinePanel'
import { CollectionTab } from './CollectionTab'
import { DueDayChangeDialog, PaymentDialog, RateChangeDialog, RevealDialog, WorkoutDialog } from './dialogs'
import { SummaryBar } from './SummaryBar'
import { HistoryTab, LedgerTab, ScheduleTab } from './tabs'

function Row({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right tabular-nums">{value}</dd>
    </>
  )
}

function Terms({ loan }: { loan: LoanDetail }) {
  return (
    <Card>
      <CardContent>
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs sm:grid-cols-[auto_1fr_auto_1fr_auto_1fr]">
          <Row label="고객번호" value={loan.customer.customerNo} />
          <Row label="주민등록번호" value={loan.customer.residentNoMasked} />
          <Row label="연락처" value={loan.customer.phoneMasked ?? '-'} />
          <Row label="실행(양수)일" value={formatDate(loan.disbursedOn)} />
          <Row label="만기일" value={formatDate(loan.maturityDate)} />
          <Row label="실행 원금" value={formatMoney(loan.initialPrincipal)} />
          <Row label="회차 / 거치" value={`${loan.termMonths} / ${loan.graceMonths}`} />
          <Row label="약정일" value={loan.currentDueDay ? `${loan.currentDueDay}일` : '-'} />
          <Row label="월 납입액" value={formatMoney(loan.installmentAmount)} />
          <Row label="미납 정상이자" value={formatMoney(loan.outstandingNormalInterest)} />
          <Row label="미납 가산이자" value={formatMoney(loan.outstandingLateInterest)} />
          <Row label="가수금" value={formatMoney(loan.suspenseBalance)} />
          <Row label="미회수 비용" value={formatMoney(loan.costBalance)} />
          <Row label="중도상환수수료(오늘)" value={formatMoney(loan.prepaymentFeeToday)} />
          <Row label="마지막 이자 정산일" value={formatDate(loan.lastSettledOn)} />
          <Row label="가상계좌" value={loan.virtualAccountNo} />
          <Row label="누적 입금" value={formatMoney(loan.totalPaid)} />
          <Row label="반환 대기" value={formatMoney(loan.refundPending)} />
          {loan.acceleratedOn && <Row label="기한이익상실일" value={formatDate(loan.acceleratedOn)} />}
          {loan.paidOffOn && <Row label="완제일" value={formatDate(loan.paidOffOn)} />}
          {loan.acquisition && (
            <>
              <Row label="양수일 / 양도인" value={`${formatDate(loan.acquisition.acquiredOn)} / ${loan.acquisition.seller}`} />
              <Row label="취득가" value={formatMoney(loan.acquisition.acquisitionPrice)} />
              <Row
                label="적용이율 / 이자"
                value={`${formatRate(loan.acquisition.appliedRate)} / ${loan.acquisition.interestMode === 'CHARGE' ? '부과' : '중단'}`}
              />
              <Row label="양수 전 미수이자" value={formatMoney(loan.preAcqInterest)} />
              <Row label="누적 회수율" value={`${loan.acquisition.recoveryRate}%`} />
              <Row
                label="마지막 입금 후 경과"
                value={
                  loan.acquisition.daysSinceLastPayment === null ? '-' : `${loan.acquisition.daysSinceLastPayment}일`
                }
              />
            </>
          )}
        </dl>
      </CardContent>
    </Card>
  )
}

export function LoanDetailPage() {
  const id = Number(useParams().id)
  const [tab, setTab] = useState('schedule')
  const q = useQuery({ queryKey: queryKeys.loan(id), queryFn: () => endpoints.loan(id) })

  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  const loan = q.data
  const open = loan.status !== 'PAID_OFF'

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <Link to="/loans" className="text-xs text-primary hover:underline">
          ← 계약 목록
        </Link>
        <div className="flex gap-1">
          <Can permission="payment.register">{open && <PaymentDialog loan={loan} />}</Can>
          <Can permission="loan.term.change">
            {open && loan.repaymentMethod !== 'ACQUIRED' && (
              <>
                <RateChangeDialog loan={loan} />
                <DueDayChangeDialog loan={loan} />
              </>
            )}
          </Can>
          <Can permission="workout.waive.late">
            {open && loan.repaymentMethod !== 'ACQUIRED' && <WorkoutDialog loan={loan} />}
          </Can>
          <Can permission="customer.id.reveal">
            <RevealDialog loan={loan} />
          </Can>
        </div>
      </div>
      <SummaryBar loan={loan} />
      {/* 3단 구성 (guide 13.2): 요약 바 / 중앙 탭 / 우측 연락 타임라인 */}
      <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="min-w-0 space-y-3">
          <Terms loan={loan} />
          <Tabs value={tab} onValueChange={setTab}>
            <TabsList>
              <TabsTrigger value="schedule">상환스케줄</TabsTrigger>
              <TabsTrigger value="ledger">거래원장</TabsTrigger>
              <TabsTrigger value="payments">입금내역</TabsTrigger>
              <TabsTrigger value="history">조건변경이력</TabsTrigger>
              <TabsTrigger value="collection">추심</TabsTrigger>
              <TabsTrigger value="legal">법조치</TabsTrigger>
              <TabsTrigger value="collateral">담보·보증인</TabsTrigger>
            </TabsList>
            {/* 탭은 선택될 때만 마운트 → 지연 로딩 */}
            <TabsContent value="schedule">{tab === 'schedule' && <ScheduleTab loanId={id} />}</TabsContent>
            <TabsContent value="ledger">{tab === 'ledger' && <LedgerTab loanId={id} />}</TabsContent>
            <TabsContent value="payments">{tab === 'payments' && <PaymentsTab loanId={id} />}</TabsContent>
            <TabsContent value="history">{tab === 'history' && <HistoryTab loanId={id} />}</TabsContent>
            <TabsContent value="collection">{tab === 'collection' && <CollectionTab loan={loan} />}</TabsContent>
            <TabsContent value="legal">{tab === 'legal' && <LegalTab loan={loan} />}</TabsContent>
            <TabsContent value="collateral">{tab === 'collateral' && <CollateralTab loan={loan} />}</TabsContent>
          </Tabs>
        </div>
        <TimelinePanel loanId={id} />
      </div>
    </div>
  )
}
