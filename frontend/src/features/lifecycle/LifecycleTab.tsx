import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import { adjustmentTypeLabels, lifecycleApi, lifecycleKeys, type Lifecycle } from '@/api/lifecycle'
import type { LoanDetail } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, THead, TR, Table } from '@/components/ui/table'
import { Can } from '@/features/auth/auth'
import { formatDate, formatMoney, statusLabel } from '@/lib/format'
import { ADJUSTMENT_TYPES, adjustmentRequestSchema, saleSchema, transferSchema } from '@/lib/lifecycleSchemas'
import { useSort } from '@/lib/useSort'
import { ReasonDialog, RequestsTable, useInvalidateLifecycle } from './shared'

/** 생애주기 탭: 상각(R39)·매각(R40)·환매(R41)·양수인 이전, 수기 조정·상계·상태 되돌리기 요청(R42~R44) */
export function LifecycleTab({ loan }: { loan: LoanDetail }) {
  const q = useQuery({ queryKey: lifecycleKeys.lifecycle(loan.id), queryFn: () => lifecycleApi.lifecycle(loan.id) })
  if (q.error) return <ErrorMessage error={q.error} />
  if (!q.data) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  const info = q.data
  return (
    <div className="space-y-3">
      <StatusCard loan={loan} info={info} />
      <SalesCard info={info} />
      {info.setoffs.length > 0 && <SetoffsCard info={info} />}
      <Card>
        <CardHeader>
          <CardTitle>조정·상계·되돌리기 결재</CardTitle>
          <Can permission="loan.adjust.request">
            <RequestDialog loan={loan} info={info} />
          </Can>
        </CardHeader>
        <CardContent>
          <RequestsTable rows={info.requests} />
        </CardContent>
      </Card>
    </div>
  )
}

function Item({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={strong ? 'font-semibold tabular-nums' : 'tabular-nums'}>{value}</dd>
    </>
  )
}

function StatusCard({ loan, info }: { loan: LoanDetail; info: Lifecycle }) {
  const invalidate = useInvalidateLifecycle()
  const sold = info.status === 'SOLD'
  const sellable = info.status === 'ACCELERATED' || info.status === 'CHARGED_OFF'
  const activeSale = info.sales.find((s) => s.status === 'ACTIVE')
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          채권 상태 <StatusBadge status={info.status} />
        </CardTitle>
        <Can permission="loan.chargeoff.confirm">
          <div className="flex gap-1">
            {info.status === 'ACCELERATED' && (
              <ReasonDialog
                trigger="상각 확정"
                title="상각 확정 (R39)"
                description={`확정일(${info.businessDate})까지 이자를 정산하고 장부에서 제거합니다. 채권은 존속하며 이후 이자를 붙이지 않습니다.`}
                submitLabel="상각 확정"
                disabled={!info.chargeOffCandidate}
                onSubmit={async (reason) => {
                  await lifecycleApi.chargeOff(loan.id, reason)
                  await invalidate(loan.id)
                }}
              />
            )}
            {sellable && <SaleDialog loanId={loan.id} businessDate={info.businessDate} />}
            {sold && (
              <ReasonDialog
                trigger="환매"
                title="환매 (R41)"
                description={`매각가로 되사서 매각 직전 상태로 복원합니다. 마감일 ${formatDate(activeSale?.repurchaseDeadline)}. 하자 사유를 남기세요.`}
                submitLabel="환매 확정"
                onSubmit={async (reason) => {
                  await lifecycleApi.repurchase(loan.id, reason)
                  await invalidate(loan.id)
                }}
              />
            )}
            {sold && info.transferPending > 0 && (
              <TransferDialog loanId={loan.id} pending={info.transferPending} />
            )}
          </div>
        </Can>
      </CardHeader>
      <CardContent className="space-y-2">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs md:grid-cols-6">
          <Item label="기한이익상실일" value={formatDate(info.acceleratedOn)} />
          <Item label="상각 확정일" value={formatDate(info.chargedOffOn)} />
          <Item label="매각일" value={formatDate(info.soldOn)} />
          <Item label="청구 잔액" value={formatMoney(info.claim)} strong />
          <Item label="양수인 이전 대상" value={formatMoney(info.transferPending)} strong={info.transferPending > 0} />
          <Item label="마지막 입금일" value={formatDate(info.lastPaymentOn)} />
        </dl>
        {info.status === 'ACCELERATED' && (
          <Alert tone={info.chargeOffCandidate ? 'warn' : 'info'}>
            {info.chargeOffCandidate
              ? '상각 후보입니다 (기한이익상실 후 180일 경과, 최근 90일 입금 없음).'
              : `상각 후보 아님: ${info.chargeOffBlocker}`}
          </Alert>
        )}
        {sold && (
          <Alert tone="info">
            매각 채권입니다. 매각일 이후 입금은 충당하지 않고 양수인 이전 대상으로 남기며, 추심 연락·자동 문자를 하지 않습니다 (R40).
          </Alert>
        )}
      </CardContent>
    </Card>
  )
}

type SaleInput = z.input<typeof saleSchema>
type SaleOutput = z.output<typeof saleSchema>

function SaleDialog({ loanId, businessDate }: { loanId: number; businessDate: string }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLifecycle()
  const form = useForm<SaleInput, unknown, SaleOutput>({
    resolver: zodResolver(saleSchema),
    defaultValues: { buyer: '', price: '', noticeSentOn: businessDate, reason: '' },
  })
  const save = useMutation({
    mutationFn: (v: SaleOutput) => lifecycleApi.sell(loanId, v),
    onSuccess: async () => {
      await invalidate(loanId)
      setOpen(false)
      form.reset()
    },
  })
  const errors = form.formState.errors
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          매각
        </Button>
      </DialogTrigger>
      <DialogContent
        title="매각 (R40)"
        description={`매각일 = 업무일자(${businessDate}). 잔액을 양수인에게 넘기고, 이후 입금은 양수인 이전 대상으로 남깁니다. 양도 통지 발송 이력이 있어야 합니다.`}
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="양수인" htmlFor="buyer" error={errors.buyer?.message}>
              <Input id="buyer" placeholder="예: 가상자산관리(가상)" {...form.register('buyer')} />
            </Field>
            <Field label="매각가" htmlFor="price" error={errors.price?.message}>
              <Input id="price" inputMode="numeric" {...form.register('price')} />
            </Field>
            <Field label="양도 통지 발송일" htmlFor="noticeSentOn" error={errors.noticeSentOn?.message}>
              <Input id="noticeSentOn" type="date" {...form.register('noticeSentOn')} />
            </Field>
            <Field label="사유" htmlFor="saleReason" error={errors.reason?.message}>
              <Input id="saleReason" {...form.register('reason')} />
            </Field>
          </div>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              매각 확정
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type TransferInput = z.input<typeof transferSchema>
type TransferOutput = z.output<typeof transferSchema>

function TransferDialog({ loanId, pending }: { loanId: number; pending: number }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLifecycle()
  const form = useForm<TransferInput, unknown, TransferOutput>({
    resolver: zodResolver(transferSchema),
    defaultValues: { amount: String(pending), memo: '' },
  })
  const save = useMutation({
    mutationFn: (v: TransferOutput) => lifecycleApi.transferToBuyer(loanId, v),
    onSuccess: async () => {
      await invalidate(loanId)
      setOpen(false)
    },
  })
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          양수인 이전
        </Button>
      </DialogTrigger>
      <DialogContent
        title="양수인 이전 완료 기록 (R40)"
        description={`이전 대상 ${formatMoney(pending)}. 실제 송금은 출금 절차로 하고, 여기서는 이전한 금액만 기록합니다.`}
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <Field label="이전 금액" htmlFor="transferAmount" error={form.formState.errors.amount?.message}>
            <Input id="transferAmount" inputMode="numeric" {...form.register('amount')} />
          </Field>
          <Field label="메모" htmlFor="transferMemo">
            <Input id="transferMemo" {...form.register('memo')} />
          </Field>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              기록
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function SalesCard({ info }: { info: Lifecycle }) {
  const { sorted, header } = useSort(info.sales, {
    soldOn: (s) => s.soldOn,
    buyer: (s) => s.buyer,
    salePrice: (s) => s.salePrice,
    noticeSentOn: (s) => s.noticeSentOn,
    deadline: (s) => s.repurchaseDeadline,
    status: (s) => statusLabel(s.status),
    repurchasedOn: (s) => s.repurchasedOn,
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>매각·환매 이력</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <THead>
            <TR>
              <SortTH {...header('soldOn')}>매각일</SortTH>
              <SortTH {...header('buyer')}>양수인</SortTH>
              <SortTH {...header('salePrice')} className="text-right">
                매각가
              </SortTH>
              <SortTH {...header('noticeSentOn')}>양도 통지</SortTH>
              <SortTH {...header('deadline')}>환매 마감</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              <SortTH {...header('repurchasedOn')}>환매</SortTH>
            </TR>
          </THead>
          <TBody>
            {info.sales.length === 0 && <EmptyRow colSpan={7} />}
            {sorted.map((s) => (
              <TR key={s.id}>
                <TD>{formatDate(s.soldOn)}</TD>
                <TD>{s.buyer}</TD>
                <TD className="num">{formatMoney(s.salePrice)}</TD>
                <TD>{formatDate(s.noticeSentOn)}</TD>
                <TD>{formatDate(s.repurchaseDeadline)}</TD>
                <TD>
                  <StatusBadge status={s.status} />
                </TD>
                <TD>
                  {s.repurchasedOn
                    ? `${formatDate(s.repurchasedOn)} · ${formatMoney(s.repurchasePrice)} · ${s.repurchaseReason ?? ''}`
                    : '-'}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

const setoffDirection = (direction: string) => (direction === 'IN' ? '받음 (이 계약 채무 충당)' : '보냄 (이 계약 잔액에서)')
const setoffSource = (source: string | null) =>
  source === 'REFUND' ? '반환 대기' : source === 'SUSPENSE' ? '가수금' : '-'

function SetoffsCard({ info }: { info: Lifecycle }) {
  const { sorted, header } = useSort(info.setoffs, {
    id: (s) => s.eventId,
    direction: (s) => setoffDirection(s.direction),
    date: (s) => s.date,
    amount: (s) => s.amount,
    counterpart: (s) => s.counterpartAccountNo,
    source: (s) => (s.source ? setoffSource(s.source) : null),
    status: (s) => statusLabel(s.status),
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>상계 내역 (R44)</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <THead>
            <TR>
              <SortTH {...header('id')}>#</SortTH>
              <SortTH {...header('direction')}>방향</SortTH>
              <SortTH {...header('date')}>상계일</SortTH>
              <SortTH {...header('amount')} className="text-right">
                금액
              </SortTH>
              <SortTH {...header('counterpart')}>상대 계약</SortTH>
              <SortTH {...header('source')}>원천</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
            </TR>
          </THead>
          <TBody>
            {sorted.map((s) => (
              <TR key={s.eventId}>
                <TD>{s.eventId}</TD>
                <TD>{setoffDirection(s.direction)}</TD>
                <TD>{formatDate(s.date)}</TD>
                <TD className="num">{formatMoney(s.amount)}</TD>
                <TD>{s.counterpartAccountNo ?? '-'}</TD>
                <TD>{setoffSource(s.source)}</TD>
                <TD>
                  <StatusBadge status={s.status} />
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

type RequestInput = z.input<typeof adjustmentRequestSchema>
type RequestOutput = z.output<typeof adjustmentRequestSchema>

/** 지금 상태에서 요청할 수 있는 유형 (서버가 다시 검사) */
export function allowedTypes(status: string): (typeof ADJUSTMENT_TYPES)[number][] {
  const types: (typeof ADJUSTMENT_TYPES)[number][] = []
  if (status !== 'PAID_OFF' && status !== 'SOLD') types.push('ADJUSTMENT', 'SETOFF')
  if (status === 'CHARGED_OFF') types.push('REVERT_CHARGE_OFF')
  if (status === 'SOLD') types.push('REVERT_SALE')
  types.push('REVERT_SETOFF')
  if (status === 'PAID_OFF' || status === 'SOLD' || status === 'CHARGED_OFF') types.push('REVERT_PAYOFF')
  return types
}

function RequestDialog({ loan, info }: { loan: LoanDetail; info: Lifecycle }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLifecycle()
  const types = allowedTypes(info.status)
  const setoffIns = info.setoffs.filter((s) => s.direction === 'IN' && s.status === 'ACTIVE')
  const form = useForm<RequestInput, unknown, RequestOutput>({
    resolver: zodResolver(adjustmentRequestSchema),
    defaultValues: {
      type: types[0],
      principalDown: '',
      normalDown: '',
      lateDown: '',
      preAcqDown: '',
      costDelta: '',
      amount: '',
      sourceAccountNo: '',
      reason: '',
    },
  })
  const type = form.watch('type')
  const save = useMutation({
    mutationFn: (v: RequestOutput) =>
      lifecycleApi.request({
        type: v.type,
        accountId: loan.id,
        principalDown: v.principalDown,
        normalDown: v.normalDown,
        lateDown: v.lateDown,
        preAcqDown: v.preAcqDown,
        costDelta: v.costDelta,
        sourceAccountNo: v.type === 'SETOFF' ? v.sourceAccountNo?.trim() : undefined,
        setoffSource: v.type === 'SETOFF' ? v.setoffSource : undefined,
        amount: v.amount,
        targetEventId: v.targetEventId ? Number(v.targetEventId) : undefined,
        reason: v.reason,
      }),
    onSuccess: async () => {
      await invalidate(loan.id)
      setOpen(false)
      form.reset()
    },
  })
  const errors = form.formState.errors
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          결재 요청
        </Button>
      </DialogTrigger>
      <DialogContent
        title="수기 조정·상계·상태 되돌리기 요청"
        description="요청만으로는 잔액이 바뀌지 않습니다. 본부장이 승인(작성자 ≠ 승인자)하면 승인일 기준으로 반영합니다 (R42~R44)."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <Field label="유형" htmlFor="reqType">
            <Select id="reqType" {...form.register('type')}>
              {types.map((t) => (
                <option key={t} value={t}>
                  {adjustmentTypeLabels[t]}
                </option>
              ))}
            </Select>
          </Field>
          {type === 'ADJUSTMENT' && (
            <div className="grid grid-cols-2 gap-3">
              <Field label="원금 감액 (마지막 회차부터)" htmlFor="principalDown" error={errors.principalDown?.message}>
                <Input id="principalDown" inputMode="numeric" {...form.register('principalDown')} />
              </Field>
              <Field label="정상이자 감액" htmlFor="normalDown" error={errors.normalDown?.message}>
                <Input id="normalDown" inputMode="numeric" {...form.register('normalDown')} />
              </Field>
              <Field label="가산이자 감액" htmlFor="lateDown" error={errors.lateDown?.message}>
                <Input id="lateDown" inputMode="numeric" {...form.register('lateDown')} />
              </Field>
              {loan.repaymentMethod === 'ACQUIRED' && (
                <Field label="양수 전 미수이자 감액" htmlFor="preAcqDown" error={errors.preAcqDown?.message}>
                  <Input id="preAcqDown" inputMode="numeric" {...form.register('preAcqDown')} />
                </Field>
              )}
              <Field label="비용 증감 (음수 = 감액)" htmlFor="costDelta" error={errors.costDelta?.message}>
                <Input id="costDelta" inputMode="numeric" {...form.register('costDelta')} />
              </Field>
            </div>
          )}
          {type === 'SETOFF' && (
            <div className="grid grid-cols-2 gap-3">
              <Field label="원천 계약번호 (같은 고객)" htmlFor="sourceAccountNo" error={errors.sourceAccountNo?.message}>
                <Input id="sourceAccountNo" {...form.register('sourceAccountNo')} />
              </Field>
              <Field label="원천" htmlFor="setoffSource" error={errors.setoffSource?.message}>
                <Select id="setoffSource" {...form.register('setoffSource')}>
                  <option value="">선택</option>
                  <option value="REFUND">반환 대기 과입금</option>
                  <option value="SUSPENSE">가수금</option>
                </Select>
              </Field>
              <Field label="상계액" htmlFor="setoffAmount" error={errors.amount?.message}>
                <Input id="setoffAmount" inputMode="numeric" {...form.register('amount')} />
              </Field>
            </div>
          )}
          {type === 'REVERT_SETOFF' && (
            <Field label="되돌릴 상계 (이 계약이 받은 것)" htmlFor="targetEventId" error={errors.targetEventId?.message}>
              <Select id="targetEventId" {...form.register('targetEventId')}>
                <option value="">선택</option>
                {setoffIns.map((s) => (
                  <option key={s.eventId} value={s.eventId}>
                    #{s.eventId} {formatDate(s.date)} {formatMoney(s.amount)} ← {s.counterpartAccountNo}
                  </option>
                ))}
              </Select>
            </Field>
          )}
          {type === 'REVERT_PAYOFF' && (
            <Alert tone="info">
              승인되면 이 계약({statusLabel(info.status)})의 입금 취소 1건을 R31과 무관하게 허용합니다. 취소 자체는 입금 취소·정정
              절차로 하세요 (R43).
            </Alert>
          )}
          <Field label="사유" htmlFor="reqReason" error={errors.reason?.message}>
            <Input id="reqReason" {...form.register('reason')} />
          </Field>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              요청
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
