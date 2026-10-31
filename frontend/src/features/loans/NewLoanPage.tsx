import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import type { z } from 'zod'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { CustomerView } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select } from '@/components/ui/input'
import { formatMoney, formatRate } from '@/lib/format'
import { customerSchema, DUE_DAYS, openLoanSchema } from '@/lib/schemas'
import { useInvalidateLoan } from './hooks'

type CustomerInput = z.input<typeof customerSchema>
type LoanInput = z.input<typeof openLoanSchema>
type LoanOutput = z.output<typeof openLoanSchema>

/** 계약 직접 등록 (심사 없이 바로 실행, MVP 1장). 고객 등록 → 계약 생성 2단계 */
export function NewLoanPage() {
  const navigate = useNavigate()
  const invalidate = useInvalidateLoan()
  const [customer, setCustomer] = useState<CustomerView | null>(null)
  const products = useQuery({ queryKey: queryKeys.products, queryFn: endpoints.products })
  const bd = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })

  const customerForm = useForm<CustomerInput>({
    resolver: zodResolver(customerSchema),
    defaultValues: { name: '', residentNo: '', birthDate: '', phone: '', address: '' },
  })
  const registerCustomer = useMutation({ mutationFn: endpoints.registerCustomer, onSuccess: setCustomer })

  const loanForm = useForm<LoanInput, unknown, LoanOutput>({
    resolver: zodResolver(openLoanSchema),
    defaultValues: {
      productCode: 'P1',
      principal: '',
      rate: 19.9,
      termMonths: 12,
      graceMonths: 0,
      dueDay: 25,
      disbursedOn: '',
      assignedStaffId: '',
    },
  })
  const productCode = loanForm.watch('productCode')
  const product = products.data?.find((p) => p.code === productCode)
  const open = useMutation({
    mutationFn: (v: LoanOutput) =>
      endpoints.openLoan({
        customerId: customer!.id,
        productCode: v.productCode,
        principal: v.principal,
        rate: v.rate,
        termMonths: v.termMonths,
        graceMonths: v.graceMonths,
        dueDay: v.dueDay,
        disbursedOn: v.disbursedOn || bd.data?.businessDate,
        assignedStaffId: v.assignedStaffId ? Number(v.assignedStaffId) : null,
      }),
    onSuccess: async (ref) => {
      await invalidate(ref.id)
      navigate(`/loans/${ref.id}`)
    },
  })
  const ce = customerForm.formState.errors
  const le = loanForm.formState.errors

  return (
    <div className="grid max-w-4xl gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle>1. 고객</CardTitle>
        </CardHeader>
        <CardContent>
          {customer ? (
            <Alert tone="success">
              {customer.name} ({customer.customerNo}, {customer.residentNoMasked})
              <Button size="sm" variant="link" onClick={() => setCustomer(null)}>
                다시 입력
              </Button>
            </Alert>
          ) : (
            <form className="space-y-2" onSubmit={customerForm.handleSubmit((v) => registerCustomer.mutate(v))}>
              <Field label="이름" htmlFor="cname" error={ce.name?.message}>
                <Input id="cname" {...customerForm.register('name')} />
              </Field>
              <Field label="주민등록번호" htmlFor="residentNo" error={ce.residentNo?.message}>
                <Input id="residentNo" placeholder="900101-1234567" {...customerForm.register('residentNo')} />
              </Field>
              <Field label="생년월일" htmlFor="birthDate" error={ce.birthDate?.message}>
                <Input id="birthDate" type="date" {...customerForm.register('birthDate')} />
              </Field>
              <Field label="연락처" htmlFor="phone">
                <Input id="phone" {...customerForm.register('phone')} />
              </Field>
              <Field label="주소" htmlFor="address">
                <Input id="address" {...customerForm.register('address')} />
              </Field>
              <p className="text-[11px] text-muted-foreground">
                같은 주민등록번호의 고객이 있으면 그 고객을 사용합니다 (블라인드 인덱스 검색).
              </p>
              <ErrorMessage error={registerCustomer.error} />
              <Button type="submit" disabled={registerCustomer.isPending}>
                고객 확인·등록
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>2. 계약 조건</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="space-y-2" onSubmit={loanForm.handleSubmit((v) => open.mutate(v))}>
            <Field label="상품" htmlFor="productCode" error={le.productCode?.message}>
              <Select id="productCode" {...loanForm.register('productCode')}>
                {products.data
                  ?.filter((p) => p.code !== 'N1')
                  .map((p) => (
                    <option key={p.id} value={p.code}>
                      {p.code} {p.name}
                    </option>
                  ))}
              </Select>
            </Field>
            {product && (
              <p className="text-[11px] text-muted-foreground">
                한도 {formatMoney(product.minAmount)} ~ {formatMoney(product.maxAmount)} · 기간 {product.minTermMonths}~
                {product.maxTermMonths}개월 · 기본 금리 {formatRate(product.defaultRate)} (최대 3.0%p 인하)
                {product.maxGraceMonths > 0 && ` · 거치 ${product.minGraceMonths}~${product.maxGraceMonths}개월`}
              </p>
            )}
            <div className="grid grid-cols-2 gap-2">
              <Field label="원금" htmlFor="principal" error={le.principal?.message}>
                <Input id="principal" inputMode="numeric" {...loanForm.register('principal')} />
              </Field>
              <Field label="연이율(%)" htmlFor="loanRate" error={le.rate?.message}>
                <Input id="loanRate" type="number" step="0.01" {...loanForm.register('rate')} />
              </Field>
              <Field label="분할 기간(개월)" htmlFor="termMonths" error={le.termMonths?.message}>
                <Input id="termMonths" type="number" {...loanForm.register('termMonths')} />
              </Field>
              <Field label="거치(개월)" htmlFor="graceMonths" error={le.graceMonths?.message}>
                <Input id="graceMonths" type="number" {...loanForm.register('graceMonths')} />
              </Field>
              <Field label="약정일" htmlFor="loanDueDay" error={le.dueDay?.message}>
                <Select id="loanDueDay" {...loanForm.register('dueDay')}>
                  {DUE_DAYS.map((d) => (
                    <option key={d} value={d}>
                      {d}일
                    </option>
                  ))}
                </Select>
              </Field>
              <Field label="실행일 (비우면 업무일자)" htmlFor="disbursedOn" error={le.disbursedOn?.message}>
                <Input id="disbursedOn" type="date" {...loanForm.register('disbursedOn')} />
              </Field>
              <Field label="담당자 ID" htmlFor="assignedStaffId">
                <Input id="assignedStaffId" inputMode="numeric" {...loanForm.register('assignedStaffId')} />
              </Field>
            </div>
            <ErrorMessage error={open.error} />
            <Button type="submit" disabled={!customer || open.isPending}>
              계약 생성
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
