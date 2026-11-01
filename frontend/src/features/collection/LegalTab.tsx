import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { LegalAction, LoanDetail } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { Can } from '@/features/auth/auth'
import { formatDate, formatMoney, legalLabel } from '@/lib/format'
import { legalActionSchema, legalCostSchema, legalStageSchema } from '@/lib/schemas'

const TERMINAL = ['CLOSED', 'WITHDRAWN', 'DISMISSED', 'RELEASED']

/** 법조치 상태 배지: 진행 중 / 끝남 */
export function LegalStatusBadge({ status }: { status: string }) {
  const done = TERMINAL.includes(status)
  return (
    <Badge
      className={
        done ? 'bg-slate-100 text-slate-600 border-slate-200' : 'bg-sky-50 text-sky-700 border-sky-200'
      }
    >
      {legalLabel(status)}
    </Badge>
  )
}

/** 계약 상세의 법조치 탭 (지급명령·압류·경매, R51·R52) */
export function LegalTab({ loan }: { loan: LoanDetail }) {
  const q = useQuery({ queryKey: queryKeys.loanLegalActions(loan.id), queryFn: () => endpoints.loanLegalActions(loan.id) })
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-xs text-muted-foreground">
          신청은 소멸시효를 중단하고(지급명령 신청·압류·경매 신청), 지급명령 확정은 10년으로 연장합니다. 취하·각하하면
          중단 기록을 무효로 합니다. 비용은 계약 비용으로 적재돼 가장 먼저 충당됩니다 (R51·R52).
        </p>
        <Can permission="collection.legal">
          {loan.status !== 'PAID_OFF' && <OpenLegalActionDialog loan={loan} />}
        </Can>
      </div>
      <ErrorMessage error={q.error} />
      {q.data?.length === 0 && <p className="text-xs text-muted-foreground">등록된 법조치가 없습니다.</p>}
      {q.data?.map((a) => <LegalActionCard key={a.id} action={a} loan={loan} />)}
    </div>
  )
}

function useInvalidateLegal(loanId: number) {
  const client = useQueryClient()
  return async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ['loan', loanId] }),
      client.invalidateQueries({ queryKey: ['legal-actions'] }),
    ])
  }
}

function LegalActionCard({ action: a, loan }: { action: LegalAction; loan: LoanDetail }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          {a.actionNo} · {legalLabel(a.type)} <LegalStatusBadge status={a.status} />
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs md:grid-cols-4">
          <dt className="text-muted-foreground">법원</dt>
          <dd>{a.courtName ?? '-'}</dd>
          <dt className="text-muted-foreground">사건 참조</dt>
          <dd>{a.caseRef ?? '-'}</dd>
          <dt className="text-muted-foreground">대상</dt>
          <dd>{a.targetDesc ?? '-'}</dd>
          <dt className="text-muted-foreground">청구금액</dt>
          <dd className="tabular-nums">{formatMoney(a.claimAmount)}</dd>
          <dt className="text-muted-foreground">신청일</dt>
          <dd>{formatDate(a.filedOn)}</dd>
          <dt className="text-muted-foreground">종료일</dt>
          <dd>{formatDate(a.closedOn)}</dd>
          <dt className="text-muted-foreground">비용 합계</dt>
          <dd className="tabular-nums">{formatMoney(a.totalCost)}</dd>
          <dt className="text-muted-foreground">메모</dt>
          <dd>{a.memo ?? '-'}</dd>
        </dl>
        <div className="grid gap-3 md:grid-cols-2">
          <Table>
            <THead>
              <TR>
                <TH>단계</TH>
                <TH>일자</TH>
                <TH>메모</TH>
                <TH>소멸시효</TH>
              </TR>
            </THead>
            <TBody>
              {a.stages.map((s) => (
                <TR key={s.id}>
                  <TD>{legalLabel(s.stage)}</TD>
                  <TD>{formatDate(s.date)}</TD>
                  <TD>{s.memo ?? '-'}</TD>
                  <TD className="text-muted-foreground">{s.limitationEventId ? '사유 기록' : '-'}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
          <Table>
            <THead>
              <TR>
                <TH>비용</TH>
                <TH className="text-right">금액</TH>
                <TH>발생일</TH>
                <TH>적재일</TH>
              </TR>
            </THead>
            <TBody>
              {a.costs.length === 0 && <EmptyRow colSpan={4} />}
              {a.costs.map((c) => (
                <TR key={c.id}>
                  <TD>
                    {legalLabel(c.costType)}
                    {c.memo && <span className="ml-1 text-muted-foreground">{c.memo}</span>}
                  </TD>
                  <TD className="num">{formatMoney(c.amount)}</TD>
                  <TD>{formatDate(c.incurredOn)}</TD>
                  <TD>{formatDate(c.chargedOn)}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </div>
        <Can permission="collection.legal">
          <div className="flex gap-1">
            {a.nextStages.length > 0 && <StageDialog action={a} loan={loan} />}
            {loan.status !== 'PAID_OFF' && <CostDialog action={a} loan={loan} />}
          </div>
        </Can>
      </CardContent>
    </Card>
  )
}

type OpenInput = z.input<typeof legalActionSchema>
type OpenOutput = z.output<typeof legalActionSchema>

function OpenLegalActionDialog({ loan }: { loan: LoanDetail }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLegal(loan.id)
  const form = useForm<OpenInput, unknown, OpenOutput>({
    resolver: zodResolver(legalActionSchema),
    defaultValues: { type: 'PAYMENT_ORDER', filedOn: loan.businessDate, courtName: '', caseRef: '', targetDesc: '' },
  })
  const save = useMutation({
    mutationFn: (v: OpenOutput) =>
      endpoints.openLegalAction(loan.id, {
        type: v.type,
        filedOn: v.filedOn,
        courtName: v.courtName || undefined,
        caseRef: v.caseRef || undefined,
        targetDesc: v.targetDesc || undefined,
        claimAmount: v.claimAmount,
        memo: v.memo || undefined,
      }),
    onSuccess: async () => {
      await invalidate()
      setOpen(false)
      form.reset()
    },
  })
  const errors = form.formState.errors
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm">법조치 등록</Button>
      </DialogTrigger>
      <DialogContent title="법조치 등록" description="신청일에 소멸시효 중단 사유를 함께 기록합니다 (R51).">
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="종류" htmlFor="legalType">
              <Select id="legalType" {...form.register('type')}>
                <option value="PAYMENT_ORDER">지급명령</option>
                <option value="SEIZURE">압류</option>
                <option value="AUCTION">경매</option>
              </Select>
            </Field>
            <Field label="신청일" htmlFor="legalFiledOn" error={errors.filedOn?.message}>
              <Input id="legalFiledOn" type="date" {...form.register('filedOn')} />
            </Field>
            <Field label="법원 (자유 입력)" htmlFor="legalCourt">
              <Input id="legalCourt" placeholder="가상지방법원" {...form.register('courtName')} />
            </Field>
            <Field label="사건 참조 (자유 입력)" htmlFor="legalCase">
              <Input id="legalCase" {...form.register('caseRef')} />
            </Field>
            <Field label="대상 (압류·경매)" htmlFor="legalTarget">
              <Input id="legalTarget" {...form.register('targetDesc')} />
            </Field>
            <Field label="청구금액 (선택)" htmlFor="legalClaim">
              <Input id="legalClaim" inputMode="numeric" {...form.register('claimAmount')} />
            </Field>
          </div>
          <Field label="메모" htmlFor="legalMemo">
            <Input id="legalMemo" {...form.register('memo')} />
          </Field>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              등록
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type StageInput = z.input<typeof legalStageSchema>
type StageOutput = z.output<typeof legalStageSchema>

function StageDialog({ action, loan }: { action: LegalAction; loan: LoanDetail }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLegal(loan.id)
  const form = useForm<StageInput, unknown, StageOutput>({
    resolver: zodResolver(legalStageSchema),
    defaultValues: { stage: action.nextStages[0], date: loan.businessDate, memo: '' },
  })
  const stage = form.watch('stage')
  const save = useMutation({
    mutationFn: (v: StageOutput) =>
      endpoints.moveLegalAction(action.id, { stage: v.stage, date: v.date, memo: v.memo || undefined }),
    onSuccess: async () => {
      await invalidate()
      setOpen(false)
    },
  })
  const errors = form.formState.errors
  const voids = ['WITHDRAWN', 'DISMISSED'].includes(stage) || (stage === 'RELEASED' && action.status !== 'COLLECTED')
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          단계 변경
        </Button>
      </DialogTrigger>
      <DialogContent
        title={`${action.actionNo} 단계 변경`}
        description="단계는 앞으로만 진행하며, 앞 단계 날짜보다 이를 수 없습니다 (R51)."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="새 단계" htmlFor="legalStage" error={errors.stage?.message}>
              <Select id="legalStage" {...form.register('stage')}>
                {action.nextStages.map((s) => (
                  <option key={s} value={s}>
                    {legalLabel(s)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="일자" htmlFor="legalStageDate" error={errors.date?.message}>
              <Input id="legalStageDate" type="date" {...form.register('date')} />
            </Field>
          </div>
          <Field label="메모" htmlFor="legalStageMemo">
            <Input id="legalStageMemo" {...form.register('memo')} />
          </Field>
          {voids && <Alert tone="warn">이 신청이 남긴 소멸시효 중단 기록을 무효로 하고 완성 예정일을 다시 계산합니다.</Alert>}
          {action.type === 'PAYMENT_ORDER' && stage === 'CONFIRMED' && (
            <Alert tone="warn">확정일부터 소멸시효 10년이 적용됩니다.</Alert>
          )}
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              저장
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type CostInput = z.input<typeof legalCostSchema>
type CostOutput = z.output<typeof legalCostSchema>

function CostDialog({ action, loan }: { action: LegalAction; loan: LoanDetail }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLegal(loan.id)
  const form = useForm<CostInput, unknown, CostOutput>({
    resolver: zodResolver(legalCostSchema),
    defaultValues: { costType: 'COURT_FEE', amount: '', incurredOn: loan.businessDate, memo: '' },
  })
  const save = useMutation({
    mutationFn: (v: CostOutput) =>
      endpoints.chargeLegalCost(action.id, {
        costType: v.costType,
        amount: v.amount,
        incurredOn: v.incurredOn,
        memo: v.memo || undefined,
      }),
    onSuccess: async () => {
      await invalidate()
      setOpen(false)
      form.reset()
    },
  })
  const errors = form.formState.errors
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          비용 등록
        </Button>
      </DialogTrigger>
      <DialogContent
        title={`${action.actionNo} 비용 등록`}
        description="오늘 업무일자로 계약 비용(COST)에 적재하며, 다음 입금에서 가장 먼저 충당됩니다 (R52). 적재 후 취소는 수기 조정으로 합니다."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="비용 종류" htmlFor="legalCostType">
              <Select id="legalCostType" {...form.register('costType')}>
                {['COURT_FEE', 'SERVICE_FEE', 'EXECUTION_FEE', 'AGENT_FEE', 'OTHER'].map((c) => (
                  <option key={c} value={c}>
                    {legalLabel(c)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="금액" htmlFor="legalCostAmount" error={errors.amount?.message}>
              <Input id="legalCostAmount" inputMode="numeric" {...form.register('amount')} />
            </Field>
            <Field label="발생일" htmlFor="legalCostDate" error={errors.incurredOn?.message}>
              <Input id="legalCostDate" type="date" {...form.register('incurredOn')} />
            </Field>
            <Field label="메모" htmlFor="legalCostMemo">
              <Input id="legalCostMemo" {...form.register('memo')} />
            </Field>
          </div>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              적재
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
