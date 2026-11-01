import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router'
import type { z } from 'zod'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { SplitMethod, SplitPart, SplitResult } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useInvalidateLoan } from '@/features/loans/hooks'
import { formatDate, formatMoney, statusLabel } from '@/lib/format'
import { PAYER_TYPES, splitSchema } from '@/lib/schemas'

type SplitInput = z.input<typeof splitSchema>
type SplitOutput = z.output<typeof splitSchema>

const METHODS: { value: SplitMethod; label: string; help: string }[] = [
  {
    value: 'OLDEST_DELINQUENCY_FIRST',
    label: '연체 우선',
    help: '연체가 오래된 계약의 연체금부터 → 다음 약정일 예정액 → 완제 금액까지. 다 갚고 남으면 첫 계약(반환 대기)',
  },
  { value: 'PROPORTIONAL', label: '잔액 비례', help: '계약별 완제 금액 비율로 나누고 원 미만 절사, 차액은 가장 큰 계약에' },
  { value: 'MANUAL', label: '수동 지정', help: '계약별 금액을 직접 입력. 합계가 입금액과 같아야 합니다' },
]

/** 한 고객의 여러 계약 일괄 입금 배분 (R47·R48) */
export function SplitPage() {
  const [params] = useSearchParams()
  const unmatchedId = params.get('unmatchedId') ? Number(params.get('unmatchedId')) : null
  const unmatchedAmount = params.get('amount') ? Number(params.get('amount')) : null
  const [accountNoInput, setAccountNoInput] = useState(params.get('accountNo') ?? '')
  const [accountNo, setAccountNo] = useState(params.get('accountNo') ?? '')
  const [manual, setManual] = useState<Record<number, string>>({})
  const [preview, setPreview] = useState<SplitPart[] | null>(null)
  const [done, setDone] = useState<SplitResult | null>(null)
  const invalidate = useInvalidateLoan()
  const bd = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })

  const targets = useQuery({
    queryKey: queryKeys.splitTargets(accountNo),
    queryFn: () => endpoints.splitTargets(accountNo),
    enabled: accountNo !== '',
  })

  const form = useForm<SplitInput, unknown, SplitOutput>({
    resolver: zodResolver(splitSchema),
    values: {
      method: 'OLDEST_DELINQUENCY_FIRST',
      bankDate: bd.data?.businessDate ?? '',
      amount: '',
      depositorName: '',
      bankTxId: '',
      payerType: 'CUSTOMER',
      payerName: '',
      payerRef: '',
      fromUnmatched: unmatchedId !== null,
    },
  })
  const method = form.watch('method')
  const payerType = form.watch('payerType')
  const errors = form.formState.errors

  const manualParts = () =>
    Object.entries(manual)
      .map(([id, v]) => ({ accountId: Number(id), amount: Number(v.replace(/[^0-9]/g, '')) || 0 }))
      .filter((p) => p.amount > 0)

  const total = (v: SplitOutput) => (unmatchedId !== null ? (unmatchedAmount ?? 0) : (v.amount ?? 0))

  const previewMutation = useMutation({
    mutationFn: (v: SplitOutput) =>
      endpoints.previewSplit({
        customerId: targets.data!.customerId,
        method: v.method,
        amount: total(v),
        manual: v.method === 'MANUAL' ? manualParts() : undefined,
      }),
    onSuccess: setPreview,
  })

  const execute = useMutation({
    mutationFn: (v: SplitOutput) =>
      endpoints.split({
        customerId: targets.data!.customerId,
        method: v.method,
        manual: v.method === 'MANUAL' ? manualParts() : undefined,
        unmatchedId: unmatchedId ?? undefined,
        bankDate: unmatchedId !== null ? undefined : v.bankDate,
        amount: unmatchedId !== null ? undefined : v.amount,
        depositorName: v.depositorName || undefined,
        bankTxId: v.bankTxId || undefined,
        payerType: v.payerType,
        payerName: v.payerType === 'CUSTOMER' ? undefined : v.payerName,
        payerRef: v.payerType === 'CUSTOMER' ? undefined : v.payerRef || undefined,
      }),
    onSuccess: async (r) => {
      setDone(r)
      setPreview(null)
      await Promise.all(r.parts.map((p) => invalidate(p.accountId ?? undefined)))
    },
  })

  const previewOf = (accountId: number) => preview?.find((p) => p.accountId === accountId)?.amount
  const manualSum = manualParts().reduce((s, p) => s + p.amount, 0)

  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">일괄 입금 배분</h1>
      <p className="text-xs text-muted-foreground">
        입금 1건을 같은 고객의 여러 계약에 나눠 충당합니다. 계약마다 원 입금의 은행 거래일자로 일반 입금과 같이
        처리하고(R13·4장), 취소는 원 입금 단위로만 합니다 (R47·R48).
      </p>
      {unmatchedId !== null && (
        <Alert tone="warn">
          미확인 입금 #{unmatchedId} ({formatMoney(unmatchedAmount)})을 나눠 매칭합니다.
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>고객 찾기</CardTitle>
        </CardHeader>
        <CardContent>
          <form
            className="flex items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              setPreview(null)
              setDone(null)
              setManual({})
              setAccountNo(accountNoInput.trim())
            }}
          >
            <Field label="고객의 계약번호 하나" htmlFor="splitAccountNo">
              <Input
                id="splitAccountNo"
                placeholder="L00001001"
                value={accountNoInput}
                onChange={(e) => setAccountNoInput(e.target.value)}
              />
            </Field>
            <Button type="submit" disabled={!accountNoInput.trim()}>
              조회
            </Button>
          </form>
          <ErrorMessage error={targets.error} />
        </CardContent>
      </Card>

      {targets.data && (
        <Card>
          <CardHeader>
            <CardTitle>
              {targets.data.customerName} ({targets.data.customerNo}) · 완제 전 계약 {targets.data.accounts.length}건 ·
              기준 {formatDate(targets.data.businessDate)}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <Table>
              <THead>
                <TR>
                  <TH>계약번호</TH>
                  <TH>상품</TH>
                  <TH>상태</TH>
                  <TH>연체 시작</TH>
                  <TH>다음 약정일</TH>
                  <TH className="text-right">연체금</TH>
                  <TH className="text-right">다음 예정액</TH>
                  <TH className="text-right">완제 금액</TH>
                  {method === 'MANUAL' && <TH className="text-right">배분액 입력</TH>}
                  <TH className="text-right">배분 미리보기</TH>
                </TR>
              </THead>
              <TBody>
                {targets.data.accounts.length === 0 && <EmptyRow colSpan={10} />}
                {targets.data.accounts.map((a) => (
                  <TR key={a.accountId}>
                    <TD>
                      <Link to={`/loans/${a.accountId}`} className="text-primary hover:underline">
                        {a.accountNo}
                      </Link>
                    </TD>
                    <TD>{a.productCode}</TD>
                    <TD>
                      <StatusBadge status={a.status} />
                    </TD>
                    <TD>{formatDate(a.delinquentSince)}</TD>
                    <TD>{formatDate(a.nextDueDate)}</TD>
                    <TD className="num">{formatMoney(a.overdue)}</TD>
                    <TD className="num">{formatMoney(a.nextDue)}</TD>
                    <TD className="num">{formatMoney(a.payoff)}</TD>
                    {method === 'MANUAL' && (
                      <TD>
                        <Input
                          aria-label={`${a.accountNo} 배분액`}
                          inputMode="numeric"
                          className="text-right"
                          value={manual[a.accountId] ?? ''}
                          onChange={(e) => {
                            setPreview(null)
                            setManual({ ...manual, [a.accountId]: e.target.value })
                          }}
                        />
                      </TD>
                    )}
                    <TD className="num font-semibold">{formatMoney(previewOf(a.accountId) ?? null)}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            {method === 'MANUAL' && (
              <p className="text-xs text-muted-foreground">수동 지정 합계 {formatMoney(manualSum)}</p>
            )}

            <form className="space-y-3" onSubmit={form.handleSubmit((v) => execute.mutate(v))}>
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                <Field label="배분 방식" htmlFor="splitMethod">
                  <Select
                    id="splitMethod"
                    {...form.register('method', { onChange: () => setPreview(null) })}
                  >
                    {METHODS.map((m) => (
                      <option key={m.value} value={m.value}>
                        {m.label}
                      </option>
                    ))}
                  </Select>
                </Field>
                {unmatchedId === null && (
                  <>
                    <Field label="입금일(은행)" htmlFor="splitBankDate" error={errors.bankDate?.message}>
                      <Input id="splitBankDate" type="date" {...form.register('bankDate')} />
                    </Field>
                    <Field label="입금액" htmlFor="splitAmount" error={errors.amount?.message}>
                      <Input
                        id="splitAmount"
                        inputMode="numeric"
                        {...form.register('amount', { onChange: () => setPreview(null) })}
                      />
                    </Field>
                    <Field label="입금자명" htmlFor="splitDepositor">
                      <Input id="splitDepositor" {...form.register('depositorName')} />
                    </Field>
                    <Field label="거래고유번호 (선택)" htmlFor="splitTx">
                      <Input id="splitTx" {...form.register('bankTxId')} />
                    </Field>
                  </>
                )}
                <Field label="입금자 구분" htmlFor="splitPayerType">
                  <Select id="splitPayerType" {...form.register('payerType')}>
                    {PAYER_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {statusLabel(t)}
                      </option>
                    ))}
                  </Select>
                </Field>
                {payerType !== 'CUSTOMER' && (
                  <>
                    <Field label="실제 입금자 이름" htmlFor="splitPayerName" error={errors.payerName?.message}>
                      <Input id="splitPayerName" {...form.register('payerName')} />
                    </Field>
                    <Field label="관계·참조 메모" htmlFor="splitPayerRef">
                      <Input id="splitPayerRef" {...form.register('payerRef')} />
                    </Field>
                  </>
                )}
              </div>
              <p className="text-xs text-muted-foreground">{METHODS.find((m) => m.value === method)?.help}</p>
              <ErrorMessage error={previewMutation.error ?? execute.error} />
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  disabled={previewMutation.isPending || targets.data.accounts.length === 0}
                  onClick={form.handleSubmit((v) => previewMutation.mutate(v))}
                >
                  배분 미리보기
                </Button>
                <Button type="submit" disabled={execute.isPending || !preview}>
                  배분 실행
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}

      {done && (
        <Alert tone="success">
          원 입금 #{done.parent.id}({formatMoney(done.parent.amount)})를 {done.parts.length}개 계약에 충당했습니다:{' '}
          {done.parts.map((p) => (
            <Link key={p.id} to={`/loans/${p.accountId}`} className="mr-2 text-primary hover:underline">
              #{p.id} {formatMoney(p.amount)}
            </Link>
          ))}
        </Alert>
      )}
    </div>
  )
}
