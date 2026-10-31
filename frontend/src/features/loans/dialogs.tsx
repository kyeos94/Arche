import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { LoanDetail } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input, Select } from '@/components/ui/input'
import { formatMoney } from '@/lib/format'
import { DUE_DAYS, dueDayChangeSchema, paymentSchema, rateChangeSchema } from '@/lib/schemas'
import { useInvalidateLoan } from './hooks'

// ------------------------------------------------------------------ 입금 등록 (+ 중도상환)

type PaymentInput = z.input<typeof paymentSchema>
type PaymentOutput = z.output<typeof paymentSchema>

export function PaymentDialog({ loan }: { loan: LoanDetail }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLoan()
  const form = useForm<PaymentInput, unknown, PaymentOutput>({
    resolver: zodResolver(paymentSchema),
    defaultValues: { bankDate: loan.businessDate, amount: '', depositorName: loan.customer.name, prepayment: false },
  })
  const prepayment = form.watch('prepayment')
  const principalInput = form.watch('prepaymentPrincipal')
  const principal = principalInput ? Number(principalInput.replace(/[^0-9]/g, '')) || null : null
  const quote = useQuery({
    queryKey: queryKeys.quote(loan.id, principal),
    queryFn: () => endpoints.quote(loan.id, principal),
    enabled: open && prepayment,
  })
  const save = useMutation({
    mutationFn: (v: PaymentOutput) =>
      endpoints.registerPayment({
        accountId: loan.id,
        bankDate: v.bankDate,
        amount: v.amount,
        depositorName: v.depositorName,
        bankTxId: v.bankTxId || undefined,
        prepayment: v.prepayment,
        prepaymentPrincipal: v.prepaymentPrincipal,
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
        <Button size="sm">입금 등록</Button>
      </DialogTrigger>
      <DialogContent
        title="입금 등록"
        description="입금일은 은행 거래일자입니다. 과거 날짜면 그날부터 소급 재계산합니다."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="입금일" htmlFor="bankDate" error={errors.bankDate?.message}>
              <Input id="bankDate" type="date" {...form.register('bankDate')} />
            </Field>
            <Field label="입금액" htmlFor="amount" error={errors.amount?.message}>
              <Input id="amount" inputMode="numeric" {...form.register('amount')} />
            </Field>
            <Field label="입금자명" htmlFor="depositorName">
              <Input id="depositorName" {...form.register('depositorName')} />
            </Field>
            <Field label="거래고유번호 (선택)" htmlFor="bankTxId">
              <Input id="bankTxId" {...form.register('bankTxId')} />
            </Field>
          </div>
          <label className="flex items-center gap-2 text-xs">
            <input type="checkbox" {...form.register('prepayment')} />
            중도상환 요청 (입금일 기준 원금 상환)
          </label>
          {prepayment && (
            <div className="space-y-2 rounded border p-2">
              <Field label="상환할 원금 (비우면 전액)" htmlFor="prepaymentPrincipal">
                <Input id="prepaymentPrincipal" inputMode="numeric" {...form.register('prepaymentPrincipal')} />
              </Field>
              {quote.data && (
                <div className="grid grid-cols-2 gap-x-4 text-xs tabular-nums">
                  <span>비용·가산이자</span>
                  <span className="text-right">{formatMoney(quote.data.cost + quote.data.lateInterest)}</span>
                  <span>정상이자 (업무일자까지)</span>
                  <span className="text-right">{formatMoney(quote.data.normalInterest)}</span>
                  <span>연체 원금</span>
                  <span className="text-right">{formatMoney(quote.data.overduePrincipal)}</span>
                  <span>상환 원금</span>
                  <span className="text-right">{formatMoney(quote.data.principal)}</span>
                  <span>중도상환수수료</span>
                  <span className="text-right">{formatMoney(quote.data.fee)}</span>
                  <span>가수금 차감</span>
                  <span className="text-right">-{formatMoney(quote.data.suspense)}</span>
                  <span className="font-semibold">필요 총액 ({quote.data.full ? '완제' : '일부'})</span>
                  <span className="text-right font-semibold">{formatMoney(quote.data.total)}</span>
                </div>
              )}
              <ErrorMessage error={quote.error} />
            </div>
          )}
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

// ------------------------------------------------------------------ 금리 변경 (R20)

type RateInput = z.input<typeof rateChangeSchema>
type RateOutput = z.output<typeof rateChangeSchema>

export function RateChangeDialog({ loan }: { loan: LoanDetail }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLoan()
  const form = useForm<RateInput, unknown, RateOutput>({
    resolver: zodResolver(rateChangeSchema),
    defaultValues: { rate: loan.currentRate ?? '', validFrom: loan.businessDate, reason: '' },
  })
  const save = useMutation({
    mutationFn: (v: RateOutput) => endpoints.changeRate(loan.id, v),
    onSuccess: async () => {
      await invalidate(loan.id)
      setOpen(false)
    },
  })
  const errors = form.formState.errors
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          금리 변경
        </Button>
      </DialogTrigger>
      <DialogContent
        title="금리 변경"
        description="적용일에서 이자 구간만 나누고, 원리금균등은 다음 회차부터 납입액을 다시 계산합니다 (R20)."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="새 이율(%)" htmlFor="rate" error={errors.rate?.message}>
              <Input id="rate" type="number" step="0.01" {...form.register('rate')} />
            </Field>
            <Field label="적용 시작일" htmlFor="validFrom" error={errors.validFrom?.message}>
              <Input id="validFrom" type="date" {...form.register('validFrom')} />
            </Field>
          </div>
          <Field label="사유" htmlFor="rateReason" error={errors.reason?.message}>
            <Input id="rateReason" {...form.register('reason')} />
          </Field>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              변경
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

// ------------------------------------------------------------------ 약정일 변경 (R21)

type DueInput = z.input<typeof dueDayChangeSchema>
type DueOutput = z.output<typeof dueDayChangeSchema>

export function DueDayChangeDialog({ loan }: { loan: LoanDetail }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLoan()
  const form = useForm<DueInput, unknown, DueOutput>({
    resolver: zodResolver(dueDayChangeSchema),
    defaultValues: { dueDay: loan.currentDueDay ?? 25, reason: '' },
  })
  const save = useMutation({
    mutationFn: (v: DueOutput) => endpoints.changeDueDay(loan.id, v),
    onSuccess: async () => {
      await invalidate(loan.id)
      setOpen(false)
    },
  })
  const errors = form.formState.errors
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          약정일 변경
        </Button>
      </DialogTrigger>
      <DialogContent
        title="약정일 변경"
        description="신청 후 처음 도래하는 약정일까지는 그대로, 그다음 회차부터 새 약정일. 6개월에 1회 (R21)."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <Field label="새 약정일" htmlFor="dueDay" error={errors.dueDay?.message}>
            <Select id="dueDay" {...form.register('dueDay')}>
              {DUE_DAYS.map((d) => (
                <option key={d} value={d}>
                  {d}일
                </option>
              ))}
            </Select>
          </Field>
          <Field label="사유" htmlFor="dueReason" error={errors.reason?.message}>
            <Input id="dueReason" {...form.register('reason')} />
          </Field>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              변경
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

// ------------------------------------------------------------------ 식별번호 원문 조회

export function RevealDialog({ loan }: { loan: LoanDetail }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const reveal = useMutation({ mutationFn: () => endpoints.revealResidentNo(loan.customer.id, reason) })
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (!o) reveal.reset()
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant="ghost">
          식별번호 원문
        </Button>
      </DialogTrigger>
      <DialogContent title="고객 식별번호 원문 조회" description="조회 사유와 함께 접근 기록이 남습니다.">
        <div className="space-y-3">
          <Field label="조회 사유" htmlFor="revealReason">
            <Input id="revealReason" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <ErrorMessage error={reveal.error} />
          {reveal.data && <Alert tone="info">{reveal.data.residentNo}</Alert>}
          <DialogFooter>
            <Button type="button" disabled={!reason.trim() || reveal.isPending} onClick={() => reveal.mutate()}>
              조회
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  )
}
