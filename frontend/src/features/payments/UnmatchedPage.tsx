import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import type { z } from 'zod'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { PaymentView, UnmatchedDeposit } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useInvalidateLoan } from '@/features/loans/hooks'
import { formatDate, formatMoney } from '@/lib/format'
import { unmatchedPaymentSchema } from '@/lib/schemas'
import { useSort } from '@/lib/useSort'

type Input_ = z.input<typeof unmatchedPaymentSchema>
type Output_ = z.output<typeof unmatchedPaymentSchema>

function RegisterUnmatched() {
  const invalidate = useInvalidateLoan()
  const bd = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })
  const form = useForm<Input_, unknown, Output_>({
    resolver: zodResolver(unmatchedPaymentSchema),
    values: { bankDate: bd.data?.businessDate ?? '', amount: '', depositorName: '', bankTxId: '' },
  })
  const save = useMutation({
    mutationFn: (v: Output_) =>
      endpoints.registerPayment({
        bankDate: v.bankDate,
        amount: v.amount,
        depositorName: v.depositorName,
        bankTxId: v.bankTxId || undefined,
      }),
    onSuccess: async () => {
      await invalidate()
      form.reset()
    },
  })
  const errors = form.formState.errors
  return (
    <form className="flex flex-wrap items-end gap-2" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
      <Field label="입금일(은행)" htmlFor="uBankDate" error={errors.bankDate?.message}>
        <Input id="uBankDate" type="date" {...form.register('bankDate')} />
      </Field>
      <Field label="입금액" htmlFor="uAmount" error={errors.amount?.message}>
        <Input id="uAmount" inputMode="numeric" {...form.register('amount')} />
      </Field>
      <Field label="입금자명" htmlFor="uDepositor">
        <Input id="uDepositor" {...form.register('depositorName')} />
      </Field>
      <Field label="거래고유번호" htmlFor="uTx">
        <Input id="uTx" {...form.register('bankTxId')} />
      </Field>
      <Button type="submit" disabled={save.isPending}>
        미확인 입금 등록
      </Button>
      <div className="w-full">
        <ErrorMessage error={save.error} />
      </div>
    </form>
  )
}

function MatchDialog({ deposit }: { deposit: UnmatchedDeposit }) {
  const [open, setOpen] = useState(false)
  const [accountNo, setAccountNo] = useState('')
  const invalidate = useInvalidateLoan()
  const [done, setDone] = useState<PaymentView | null>(null)
  const match = useMutation({
    mutationFn: () => endpoints.match(deposit.id, accountNo.trim()),
    onSuccess: async (p) => {
      setDone(p)
      await invalidate(p.accountId ?? undefined)
    },
  })
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (!o) {
          setDone(null)
          match.reset()
        }
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm">계약 매칭</Button>
      </DialogTrigger>
      <DialogContent
        title="미확인 입금 매칭"
        description="기산일은 원래 입금일입니다. 매칭 시점까지의 이자·연체를 소급 재계산합니다 (G11)."
      >
        <p className="mb-2 text-xs">
          {formatDate(deposit.valueDate)} · {formatMoney(deposit.amount)} · {deposit.depositorName ?? '입금자명 없음'}
        </p>
        {done ? (
          <div className="space-y-2">
            <Alert tone="success">계약에 충당했습니다.</Alert>
            <DialogFooter>
              <Button asChild variant="outline">
                <Link to={`/loans/${done.accountId}`}>계약 상세 보기</Link>
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="space-y-3">
            <Field label="계약번호" htmlFor="matchAccountNo">
              <Input id="matchAccountNo" value={accountNo} onChange={(e) => setAccountNo(e.target.value)} />
            </Field>
            <ErrorMessage error={match.error} />
            <DialogFooter>
              <Button disabled={!accountNo.trim() || match.isPending} onClick={() => match.mutate()}>
                매칭
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

export function UnmatchedPage() {
  const q = useQuery({ queryKey: queryKeys.unmatched, queryFn: endpoints.unmatched })
  const total = q.data?.reduce((s, d) => s + d.amount, 0) ?? 0
  const { sorted, header } = useSort(q.data, {
    id: (d) => d.id,
    valueDate: (d) => d.valueDate,
    businessDate: (d) => d.businessDate,
    amount: (d) => d.amount,
    depositor: (d) => d.depositorName,
    bankTxId: (d) => d.bankTxId,
  })
  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">미확인 입금</h1>
      <Card>
        <CardHeader>
          <CardTitle>입금자를 특정하지 못한 입금 등록</CardTitle>
        </CardHeader>
        <CardContent>
          <RegisterUnmatched />
        </CardContent>
      </Card>
      <ErrorMessage error={q.error} />
      <p className="text-xs text-muted-foreground">
        미처리 {q.data?.length ?? 0}건 · 잔액(가수금) {formatMoney(total)}
      </p>
      <Table>
        <THead>
          <TR>
            <SortTH {...header('id')}>#</SortTH>
            <SortTH {...header('valueDate')}>입금일(은행)</SortTH>
            <SortTH {...header('businessDate')}>등록 업무일</SortTH>
            <SortTH {...header('amount')} className="text-right">
              금액
            </SortTH>
            <SortTH {...header('depositor')}>입금자</SortTH>
            <SortTH {...header('bankTxId')}>거래고유번호</SortTH>
            <TH />
          </TR>
        </THead>
        <TBody>
          {q.data?.length === 0 && <EmptyRow colSpan={7} />}
          {sorted.map((d) => (
            <TR key={d.id}>
              <TD>{d.id}</TD>
              <TD>{formatDate(d.valueDate)}</TD>
              <TD>{formatDate(d.businessDate)}</TD>
              <TD className="num">{formatMoney(d.amount)}</TD>
              <TD>{d.depositorName ?? '-'}</TD>
              <TD className="text-muted-foreground">{d.bankTxId}</TD>
              <TD>
                <div className="flex gap-1">
                  <MatchDialog deposit={d} />
                  <Button asChild size="sm" variant="outline">
                    <Link to={`/payment-splits?unmatchedId=${d.id}&amount=${d.amount}`}>여러 계약에 배분</Link>
                  </Button>
                </div>
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </div>
  )
}
