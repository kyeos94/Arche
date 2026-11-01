import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import type { z } from 'zod'
import { endpoints, queryKeys } from '@/api/endpoints'
import { origination, originationKeys } from '@/api/origination'
import type { CustomerView } from '@/api/types'
import { PagedList } from '@/components/Pagination'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDateTime, formatMoney } from '@/lib/format'
import { customerSchema, DUE_DAYS } from '@/lib/schemas'
import { usePaging } from '@/lib/usePaging'
import { useSort } from '@/lib/useSort'
import { ApplicationStatusBadge } from './components'
import { APPLICATION_STATUSES, APPLICATION_STATUS_LABEL, CHANNEL_LABEL, REJECT_REASON_LABEL, receiveSchema } from './labels'

type CustomerInput = z.input<typeof customerSchema>
type ReceiveInput = z.input<typeof receiveSchema>
type ReceiveOutput = z.output<typeof receiveSchema>

/** 직원 입력 접수 (상담 중 수기 등록): 고객 확인·등록 → 신청 조건 */
function NewApplicationDialog() {
  const [open, setOpen] = useState(false)
  const [customer, setCustomer] = useState<CustomerView | null>(null)
  const navigate = useNavigate()
  const client = useQueryClient()
  const products = useQuery({ queryKey: queryKeys.products, queryFn: endpoints.products })
  const customerForm = useForm<CustomerInput>({
    resolver: zodResolver(customerSchema),
    defaultValues: { name: '', residentNo: '', birthDate: '', phone: '', address: '' },
  })
  const registerCustomer = useMutation({ mutationFn: endpoints.registerCustomer, onSuccess: setCustomer })
  const form = useForm<ReceiveInput, unknown, ReceiveOutput>({
    resolver: zodResolver(receiveSchema),
    defaultValues: {
      productCode: 'P1',
      requestedAmount: '',
      termMonths: 12,
      graceMonths: 0,
      dueDay: 25,
      creditInquiryConsent: false,
      consentEvidence: '',
    },
  })
  const receive = useMutation({
    mutationFn: (v: ReceiveOutput) =>
      origination.receive({ ...v, customerId: customer!.id, consentEvidence: v.consentEvidence || undefined }),
    onSuccess: async (a) => {
      await client.invalidateQueries({ queryKey: ['applications'] })
      setOpen(false)
      navigate(`/applications/${a.id}`)
    },
  })
  const ce = customerForm.formState.errors
  const e = form.formState.errors
  const consent = form.watch('creditInquiryConsent')
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>신청 등록</Button>
      </DialogTrigger>
      <DialogContent title="신청 등록 (직원 입력)" description="상담 중 받은 신청을 등록합니다. 같은 주민등록번호의 고객이 있으면 그 고객을 씁니다.">
        {!customer ? (
          <form className="space-y-2" onSubmit={customerForm.handleSubmit((v) => registerCustomer.mutate(v))}>
            <div className="grid grid-cols-2 gap-2">
              <Field label="이름" htmlFor="naName" error={ce.name?.message}>
                <Input id="naName" {...customerForm.register('name')} />
              </Field>
              <Field label="주민등록번호" htmlFor="naResident" error={ce.residentNo?.message}>
                <Input id="naResident" placeholder="900101-1234567" {...customerForm.register('residentNo')} />
              </Field>
              <Field label="생년월일" htmlFor="naBirth" error={ce.birthDate?.message}>
                <Input id="naBirth" type="date" {...customerForm.register('birthDate')} />
              </Field>
              <Field label="연락처" htmlFor="naPhone">
                <Input id="naPhone" {...customerForm.register('phone')} />
              </Field>
            </div>
            <ErrorMessage error={registerCustomer.error} />
            <Button type="submit" disabled={registerCustomer.isPending}>
              고객 확인·등록
            </Button>
          </form>
        ) : (
          <form className="space-y-2" onSubmit={form.handleSubmit((v) => receive.mutate(v))}>
            <Alert tone="success">
              {customer.name} ({customer.customerNo}, {customer.residentNoMasked})
            </Alert>
            <div className="grid grid-cols-2 gap-2">
              <Field label="상품" htmlFor="naProduct" error={e.productCode?.message}>
                <Select id="naProduct" {...form.register('productCode')}>
                  {products.data
                    ?.filter((p) => p.code !== 'N1')
                    .map((p) => (
                      <option key={p.id} value={p.code}>
                        {p.code} {p.name}
                      </option>
                    ))}
                </Select>
              </Field>
              <Field label="신청 금액" htmlFor="naAmount" error={e.requestedAmount?.message}>
                <Input id="naAmount" inputMode="numeric" {...form.register('requestedAmount')} />
              </Field>
              <Field label="분할 기간(개월)" htmlFor="naTerm" error={e.termMonths?.message}>
                <Input id="naTerm" type="number" {...form.register('termMonths')} />
              </Field>
              <Field label="거치(개월)" htmlFor="naGrace" error={e.graceMonths?.message}>
                <Input id="naGrace" type="number" {...form.register('graceMonths')} />
              </Field>
              <Field label="약정일" htmlFor="naDue" error={e.dueDay?.message}>
                <Select id="naDue" {...form.register('dueDay')}>
                  {DUE_DAYS.map((d) => (
                    <option key={d} value={d}>
                      {d}일
                    </option>
                  ))}
                </Select>
              </Field>
            </div>
            <label className="flex items-center gap-2 text-xs">
              <input type="checkbox" {...form.register('creditInquiryConsent')} /> 신용조회 동의를 받았습니다 (서면·녹취)
            </label>
            {consent && (
              <Field label="동의 증빙 (동의서 번호 등)" htmlFor="naEvidence">
                <Input id="naEvidence" {...form.register('consentEvidence')} />
              </Field>
            )}
            <p className="text-[11px] text-muted-foreground">
              동의가 없으면 사전심사에서 신용조회를 하지 않습니다 (R53). 고객에게 본인인증 링크를 보내 동의를 받을 수도 있습니다.
            </p>
            <ErrorMessage error={receive.error} />
            <div className="flex gap-2">
              <Button type="submit" disabled={receive.isPending}>
                접수
              </Button>
              <Button type="button" variant="outline" onClick={() => setCustomer(null)}>
                고객 다시 입력
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

export function ApplicationListPage() {
  const { can } = useAuth()
  const [status, setStatus] = useState('')
  const q = useQuery({ queryKey: originationKeys.applications(status), queryFn: () => origination.applications(status) })
  const { sorted, header } = useSort(q.data, {
    no: (a) => a.applicationNo,
    received: (a) => a.receivedAt,
    customer: (a) => a.customerName,
    product: (a) => a.productCode,
    amount: (a) => a.requestedAmount,
    channel: (a) => CHANNEL_LABEL[a.channel] ?? a.channel,
    status: (a) => APPLICATION_STATUS_LABEL[a.status] ?? a.status,
    underwriter: (a) => a.underwriterName,
    reject: (a) => (a.rejectReasonCode ? (REJECT_REASON_LABEL[a.rejectReasonCode] ?? a.rejectReasonCode) : null),
  })
  const paging = usePaging(sorted)
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">신청 목록</h1>
        {can('application.review') && <NewApplicationDialog />}
      </div>
      <Card>
        <CardHeader>
          <CardTitle>상태</CardTitle>
        </CardHeader>
        <CardContent>
          <div role="tablist" aria-label="신청 상태" className="flex flex-wrap gap-1">
            {['', ...APPLICATION_STATUSES].map((s) => (
              <Button
                key={s || 'ALL'}
                role="tab"
                aria-selected={status === s}
                size="sm"
                variant={status === s ? 'default' : 'outline'}
                onClick={() => setStatus(s)}
              >
                {s ? APPLICATION_STATUS_LABEL[s as keyof typeof APPLICATION_STATUS_LABEL] : '전체'}
              </Button>
            ))}
          </div>
        </CardContent>
      </Card>
      <ErrorMessage error={q.error} />
      <PagedList paging={paging.props}>
        <Table>
          <THead>
            <TR>
              <SortTH {...header('no')}>신청번호</SortTH>
              <SortTH {...header('received')}>접수</SortTH>
              <SortTH {...header('customer')}>고객</SortTH>
              <SortTH {...header('product')}>상품</SortTH>
              <SortTH {...header('amount')} className="text-right">
                신청액
              </SortTH>
              <SortTH {...header('channel')}>채널</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              <SortTH {...header('underwriter')}>심사자</SortTH>
              <SortTH {...header('reject')}>거절 사유</SortTH>
            </TR>
          </THead>
          <TBody>
            {q.data?.length === 0 && <EmptyRow colSpan={9} />}
            {paging.rows.map((a) => (
              <TR key={a.id}>
                <TD>
                  <Link className="text-primary underline-offset-2 hover:underline" to={`/applications/${a.id}`}>
                    {a.applicationNo}
                  </Link>
                </TD>
                <TD>{formatDateTime(a.receivedAt)}</TD>
                <TD>{a.customerName}</TD>
                <TD>{a.productCode}</TD>
                <TD className="num">{formatMoney(a.requestedAmount)}</TD>
                <TD>
                  {CHANNEL_LABEL[a.channel] ?? a.channel}
                  {a.intakeSource && <span className="text-muted-foreground"> · {a.intakeSource}</span>}
                </TD>
                <TD>
                  <ApplicationStatusBadge status={a.status} />
                </TD>
                <TD>{a.underwriterName ?? '-'}</TD>
                <TD className="text-muted-foreground">
                  {a.rejectReasonCode ? (REJECT_REASON_LABEL[a.rejectReasonCode] ?? a.rejectReasonCode) : ''}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </PagedList>
    </div>
  )
}
