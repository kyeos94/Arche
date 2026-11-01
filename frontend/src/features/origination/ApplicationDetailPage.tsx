import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router'
import type { z } from 'zod'
import { type Application, type ApplicationStatus, origination, originationKeys } from '@/api/origination'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input, Select, Textarea } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatMoney, formatRate } from '@/lib/format'
import { ApplicationStatusBadge } from './components'
import { ChannelTab, DocumentsTab, HistoryTab, ScreeningTab } from './detailTabs'
import {
  APPLICATION_STATUS_LABEL,
  APPROVAL_THRESHOLD,
  CHANNEL_LABEL,
  REJECT_REASON_LABEL,
  approvalPermission,
  approvalSchema,
  nextSteps,
} from './labels'

export function useInvalidateApplication(id: number) {
  const client = useQueryClient()
  return async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ['application', id] }),
      client.invalidateQueries({ queryKey: ['applications'] }),
      client.invalidateQueries({ queryKey: originationKeys.underwriters }),
    ])
  }
}

function RejectDialog({ app, onDone }: { app: Application; onDone: () => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [code, setCode] = useState('UNDERWRITING_DECLINE')
  const reject = useMutation({
    mutationFn: () => origination.transition(app.id, { to: 'REJECTED', reason, reasonCode: code }),
    onSuccess: async () => {
      await onDone()
      setOpen(false)
    },
  })
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="destructive">
          거절
        </Button>
      </DialogTrigger>
      <DialogContent title="심사 거절" description="거절 사유는 필수입니다. 플랫폼 접수 건은 결과가 플랫폼에 통보됩니다.">
        <div className="space-y-2">
          <Field label="사유 코드" htmlFor="rejCode">
            <Select id="rejCode" value={code} onChange={(e) => setCode(e.target.value)}>
              <option value="UNDERWRITING_DECLINE">심사 거절</option>
              <option value="INCOME_UNVERIFIED">소득 확인 불가</option>
              <option value="DOCUMENT_FRAUD">서류 위·변조 의심</option>
              <option value="DEBT_RATIO">부채 과다</option>
            </Select>
          </Field>
          <Field label="사유" htmlFor="rejReason">
            <Textarea id="rejReason" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <ErrorMessage error={reject.error} />
          <DialogFooter>
            <Button variant="destructive" disabled={!reason.trim() || reject.isPending} onClick={() => reject.mutate()}>
              거절 확정
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  )
}

type ApprovalInput = z.input<typeof approvalSchema>
type ApprovalOutput = z.output<typeof approvalSchema>

function ApprovalForm({ app, onDone }: { app: Application; onDone: () => Promise<void> }) {
  const { can } = useAuth()
  const form = useForm<ApprovalInput, unknown, ApprovalOutput>({
    resolver: zodResolver(approvalSchema),
    defaultValues: { amount: String(app.requestedAmount), rate: '' },
  })
  const amount = Number(String(form.watch('amount') ?? '').replace(/[^0-9]/g, ''))
  const allowed = can(approvalPermission(amount))
  const approve = useMutation({
    mutationFn: (v: ApprovalOutput) => origination.approve(app.id, v),
    onSuccess: onDone,
  })
  const e = form.formState.errors
  return (
    <form className="flex flex-wrap items-end gap-2" onSubmit={form.handleSubmit((v) => approve.mutate(v))}>
      <Field label="승인 금액" htmlFor="apAmount" error={e.amount?.message}>
        <Input id="apAmount" inputMode="numeric" {...form.register('amount')} />
      </Field>
      <Field label="승인 금리(%)" htmlFor="apRate" error={e.rate?.message}>
        <Input id="apRate" type="number" step="0.01" placeholder="기본 금리 − 최대 3.0%p" {...form.register('rate')} />
      </Field>
      <Button type="submit" disabled={approve.isPending || !allowed}>
        승인
      </Button>
      {!allowed && amount > 0 && (
        <span className="text-[11px] text-amber-700">
          {amount > APPROVAL_THRESHOLD ? '1,000만원 초과는 심사팀장 전결입니다' : '승인 권한이 없습니다'}
        </span>
      )}
      <div className="w-full">
        <ErrorMessage error={approve.error} />
      </div>
    </form>
  )
}

function AssignControls({ app, onDone }: { app: Application; onDone: () => Promise<void> }) {
  const { can } = useAuth()
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const uws = useQuery({
    queryKey: originationKeys.underwriters,
    queryFn: origination.underwriters,
    enabled: can('application.assign'),
  })
  const reassigning = app.status === 'ASSIGNED' || app.status === 'UNDERWRITING'
  const auto = useMutation({ mutationFn: () => origination.transition(app.id, { to: 'ASSIGNED' }), onSuccess: onDone })
  const manual = useMutation({
    mutationFn: () =>
      reassigning
        ? origination.reassign(app.id, { underwriterId: Number(target), reason })
        : origination.transition(app.id, { to: 'ASSIGNED', underwriterId: Number(target), reason }),
    onSuccess: async () => {
      setTarget('')
      setReason('')
      await onDone()
    },
  })
  const candidates = uws.data?.underwriters.filter(
    (u) => u.available && (u.allProducts || u.productCodes.includes(app.productCode)),
  )
  return (
    <div className="space-y-2">
      {app.status === 'PRE_SCREENING' && (
        <Button size="sm" onClick={() => auto.mutate()} disabled={auto.isPending}>
          자동 배정 ({uws.data?.strategy ?? '처리 건수 균등'})
        </Button>
      )}
      {can('application.assign') && (app.status === 'PRE_SCREENING' || reassigning) && (
        <div className="flex flex-wrap items-end gap-2">
          <Field label={reassigning ? '재배정 대상' : '수동 배정'} htmlFor="asTarget">
            <Select id="asTarget" value={target} onChange={(e) => setTarget(e.target.value)}>
              <option value="">선택</option>
              {candidates?.map((u) => (
                <option key={u.staffId} value={u.staffId}>
                  {u.name} ({u.role === 'UNDERWRITER' ? '심사역' : '심사팀장'}, 미처리 {u.pending})
                </option>
              ))}
            </Select>
          </Field>
          <Field label="사유" htmlFor="asReason">
            <Input id="asReason" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <Button
            size="sm"
            variant="outline"
            disabled={!target || (reassigning && !reason.trim()) || manual.isPending}
            onClick={() => manual.mutate()}
          >
            {reassigning ? '재배정' : '배정'}
          </Button>
        </div>
      )}
      <ErrorMessage error={auto.error ?? manual.error} />
    </div>
  )
}

function Actions({ app, onDone }: { app: Application; onDone: () => Promise<void> }) {
  const move = useMutation({
    mutationFn: (to: ApplicationStatus) => origination.transition(app.id, { to }),
    onSuccess: onDone,
  })
  const disburse = useMutation({ mutationFn: () => origination.disburse(app.id), onSuccess: onDone })
  const steps = nextSteps(app.status).filter((s) => s !== 'REJECTED' && s !== 'ASSIGNED')
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        {steps.map((s) => (
          <Button
            key={s}
            size="sm"
            variant={s === 'WITHDRAWN' ? 'outline' : 'default'}
            disabled={move.isPending}
            onClick={() => move.mutate(s)}
          >
            {s === 'PRE_SCREENING' ? '사전심사 실행 (자동 거절 규칙)' : `${APPLICATION_STATUS_LABEL[s]}(으)로`}
          </Button>
        ))}
        {nextSteps(app.status).includes('REJECTED') && <RejectDialog app={app} onDone={onDone} />}
        {app.status === 'CONTRACTED' && (
          <Button size="sm" disabled={disburse.isPending} onClick={() => disburse.mutate()}>
            실행 (송금 확인 → 계약 이관)
          </Button>
        )}
      </div>
      <ErrorMessage error={move.error ?? disburse.error} />
      {(app.status === 'PRE_SCREENING' || app.status === 'ASSIGNED' || app.status === 'UNDERWRITING') && (
        <AssignControls app={app} onDone={onDone} />
      )}
      {app.status === 'UNDERWRITING' && <ApprovalForm app={app} onDone={onDone} />}
    </div>
  )
}

export function ApplicationDetailPage() {
  const id = Number(useParams().id)
  const [tab, setTab] = useState('screening')
  const invalidate = useInvalidateApplication(id)
  const review = useQuery({ queryKey: originationKeys.review(id), queryFn: () => origination.review(id) })
  if (review.error) return <ErrorMessage error={review.error} />
  if (!review.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  const { application: a, customer } = review.data
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Link to="/applications" className="text-xs text-muted-foreground hover:underline">
          ← 신청 목록
        </Link>
        <h1 className="text-base font-semibold">{a.applicationNo}</h1>
        <ApplicationStatusBadge status={a.status} />
        <span className="text-xs text-muted-foreground">
          {CHANNEL_LABEL[a.channel]}
          {a.intakeSource ? ` · ${a.intakeSource}` : ''}
          {a.externalRef ? ` · ${a.externalRef}` : ''}
        </span>
      </div>
      <Card>
        <CardContent className="grid grid-cols-2 gap-x-6 gap-y-1 pt-3 text-xs sm:grid-cols-4">
          <div>
            <span className="text-muted-foreground">고객 </span>
            {customer.name} ({customer.customerNo}, {customer.residentNoMasked})
          </div>
          <div>
            <span className="text-muted-foreground">상품 </span>
            {a.productCode}
          </div>
          <div>
            <span className="text-muted-foreground">신청 </span>
            {formatMoney(a.requestedAmount)} · {a.termMonths}개월
            {a.graceMonths > 0 ? ` (거치 ${a.graceMonths})` : ''} · {a.dueDay}일
          </div>
          <div>
            <span className="text-muted-foreground">심사자 </span>
            {a.underwriterName ?? '-'}
          </div>
          {a.approvedAmount != null && (
            <div>
              <span className="text-muted-foreground">승인 </span>
              {formatMoney(a.approvedAmount)} · {formatRate(a.approvedRate)}
            </div>
          )}
          {a.decidedOn && (
            <div>
              <span className="text-muted-foreground">결정일 </span>
              {formatDate(a.decidedOn)}
            </div>
          )}
          {a.rejectReasonCode && (
            <div>
              <span className="text-muted-foreground">거절 사유 </span>
              {REJECT_REASON_LABEL[a.rejectReasonCode] ?? a.rejectReasonCode}
            </div>
          )}
          {a.disbursedOn && (
            <div>
              <span className="text-muted-foreground">실행일 </span>
              {formatDate(a.disbursedOn)}
            </div>
          )}
        </CardContent>
      </Card>
      {review.data.missingRequiredDocuments.length > 0 && a.status === 'UNDERWRITING' && (
        <Alert tone="warn">
          확인되지 않은 필수 서류: {review.data.missingRequiredDocuments.join(', ')} — 모두 확인해야 승인할 수 있습니다 (R54)
        </Alert>
      )}
      {!review.data.creditConsentValid && (a.status === 'RECEIVED' || a.status === 'PRE_SCREENING') && (
        <Alert tone="info">
          유효한 신용조회 동의가 없어 사전심사에서 신용조회를 하지 않습니다. 서면 동의를 기록하거나 고객에게 본인인증 링크를 보내세요.
        </Alert>
      )}
      <Card>
        <CardHeader>
          <CardTitle>처리</CardTitle>
        </CardHeader>
        <CardContent>
          <Actions app={a} onDone={invalidate} />
        </CardContent>
      </Card>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="screening">심사·신용조회</TabsTrigger>
          <TabsTrigger value="documents">서류 ({review.data.documents.length})</TabsTrigger>
          <TabsTrigger value="channel">채널·본인인증</TabsTrigger>
          <TabsTrigger value="history">이력</TabsTrigger>
        </TabsList>
        <TabsContent value="screening">
          <ScreeningTab review={review.data} onDone={invalidate} />
        </TabsContent>
        <TabsContent value="documents">
          <DocumentsTab review={review.data} onDone={invalidate} />
        </TabsContent>
        <TabsContent value="channel">
          <ChannelTab app={a} onDone={invalidate} />
        </TabsContent>
        <TabsContent value="history">
          <HistoryTab id={a.id} />
        </TabsContent>
      </Tabs>
    </div>
  )
}
