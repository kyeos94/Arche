import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import type { z } from 'zod'
import { type Broker, type Commission, origination, originationKeys } from '@/api/origination'
import { PagedList } from '@/components/Pagination'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatMoney, formatRate } from '@/lib/format'
import { usePaging } from '@/lib/usePaging'
import { useSort } from '@/lib/useSort'
import { brokerRateSchema, brokerSchema } from './labels'

/** API 키는 발급 직후 한 번만 보여준다 (서버는 해시만 보관) */
function IssuedKey({ apiKey }: { apiKey: string }) {
  return (
    <Alert tone="warn">
      API 키 (지금 한 번만 표시): <code className="break-all select-all">{apiKey}</code>
    </Alert>
  )
}

type BrokerInput = z.input<typeof brokerSchema>
type BrokerOutput = z.output<typeof brokerSchema>

function RegisterBrokerDialog() {
  const client = useQueryClient()
  const [key, setKey] = useState<string | null>(null)
  const form = useForm<BrokerInput, unknown, BrokerOutput>({
    resolver: zodResolver(brokerSchema),
    defaultValues: { name: '', registrationNo: '', allowedIps: '', rate: '3.00' },
  })
  const register = useMutation({
    mutationFn: (v: BrokerOutput) => origination.registerBroker({ ...v, allowedIps: v.allowedIps || undefined }),
    onSuccess: async (r) => {
      setKey(r.apiKey)
      form.reset()
      await client.invalidateQueries({ queryKey: originationKeys.brokers })
    },
  })
  const e = form.formState.errors
  return (
    <Dialog onOpenChange={(o) => !o && setKey(null)}>
      <DialogTrigger asChild>
        <Button>중개업자 등록</Button>
      </DialogTrigger>
      <DialogContent title="중개업자 등록" description="등록하면 API 키를 발급합니다. 수수료율은 등록일부터 적용됩니다 (R56).">
        {key ? (
          <IssuedKey apiKey={key} />
        ) : (
          <form className="space-y-2" onSubmit={form.handleSubmit((v) => register.mutate(v))}>
            <Field label="이름" htmlFor="brName" error={e.name?.message}>
              <Input id="brName" {...form.register('name')} />
            </Field>
            <Field label="등록 번호 (가상)" htmlFor="brReg" error={e.registrationNo?.message}>
              <Input id="brReg" {...form.register('registrationNo')} />
            </Field>
            <Field label="허용 IP (쉼표 구분, 비우면 제한 없음)" htmlFor="brIps" error={e.allowedIps?.message}>
              <Input id="brIps" {...form.register('allowedIps')} />
            </Field>
            <Field label="수수료율(%)" htmlFor="brRate" error={e.rate?.message}>
              <Input id="brRate" type="number" step="0.01" {...form.register('rate')} />
            </Field>
            <ErrorMessage error={register.error} />
            <Button type="submit" disabled={register.isPending}>
              등록
            </Button>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

function commissionTypeLabel(c: Commission) {
  return c.entryType === 'SETTLEMENT' ? '정산' : c.reasonCode === 'EARLY_PAYOFF' ? '환수(조기 완제)' : '환수(불완전판매)'
}

type RateInput = z.input<typeof brokerRateSchema>
type RateOutput = z.output<typeof brokerRateSchema>

function BrokerDetail({ broker }: { broker: Broker }) {
  const { can } = useAuth()
  const client = useQueryClient()
  const manage = can('broker.manage')
  const [key, setKey] = useState<string | null>(null)
  const q = useQuery({ queryKey: originationKeys.broker(broker.id), queryFn: () => origination.broker(broker.id) })
  const refresh = () => client.invalidateQueries({ queryKey: originationKeys.brokers })
  const rateForm = useForm<RateInput, unknown, RateOutput>({
    resolver: zodResolver(brokerRateSchema),
    defaultValues: { rate: '', validFrom: '', reason: '' },
  })
  const changeRate = useMutation({
    mutationFn: (v: RateOutput) => origination.changeBrokerRate(broker.id, v),
    onSuccess: async () => {
      rateForm.reset()
      await refresh()
    },
  })
  const toggle = useMutation({
    mutationFn: () =>
      origination.updateBroker(broker.id, {
        status: broker.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
        allowedIps: broker.allowedIps,
      }),
    onSuccess: refresh,
  })
  const rotate = useMutation({ mutationFn: () => origination.rotateBrokerKey(broker.id), onSuccess: (r) => setKey(r.apiKey) })
  const d = q.data
  const re = rateForm.formState.errors
  const rates = useSort(d?.rates, {
    rate: (r) => r.rate,
    from: (r) => r.validFrom,
    // 종료일이 없으면 현재 적용 중 = 가장 늦은 날로 본다
    to: (r) => r.validTo ?? '9999-12-31',
    reason: (r) => r.reason,
  })
  const ratePaging = usePaging(rates.sorted)
  const commissions = useSort(d?.commissions, {
    date: (c) => c.occurredOn,
    application: (c) => c.applicationNo,
    type: commissionTypeLabel,
    base: (c) => c.baseAmount,
    rate: (c) => c.rate,
    amount: (c) => c.amount,
    reason: (c) => c.reason,
  })
  const commissionPaging = usePaging(commissions.sorted)
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {broker.name} ({broker.brokerNo}) · {broker.status === 'ACTIVE' ? '거래 중' : '정지'}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <ErrorMessage error={q.error} />
        {manage && (
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" onClick={() => toggle.mutate()}>
              {broker.status === 'ACTIVE' ? '거래 정지' : '거래 재개'}
            </Button>
            <Button size="sm" variant="outline" onClick={() => rotate.mutate()}>
              API 키 재발급
            </Button>
          </div>
        )}
        {key && <IssuedKey apiKey={key} />}
        <ErrorMessage error={toggle.error ?? rotate.error} />
        <div>
          <h3 className="mb-1 text-xs font-semibold">수수료율 이력 [시작일, 종료일)</h3>
          <PagedList paging={ratePaging.props}>
            <Table aria-label="수수료율 이력">
              <THead>
                <TR>
                  <SortTH {...rates.header('rate')} className="text-right">
                    수수료율
                  </SortTH>
                  <SortTH {...rates.header('from')}>시작일</SortTH>
                  <SortTH {...rates.header('to')}>종료일</SortTH>
                  <SortTH {...rates.header('reason')}>사유</SortTH>
                </TR>
              </THead>
              <TBody>
                {ratePaging.rows.map((r) => (
                  <TR key={r.id}>
                    <TD className="num">{formatRate(r.rate)}</TD>
                    <TD>{formatDate(r.validFrom)}</TD>
                    <TD>{r.validTo ? formatDate(r.validTo) : '현재'}</TD>
                    <TD className="text-muted-foreground">{r.reason}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </PagedList>
          {manage && (
            <form className="mt-2 flex flex-wrap items-end gap-2" onSubmit={rateForm.handleSubmit((v) => changeRate.mutate(v))}>
              <Field label="새 수수료율(%)" htmlFor="crRate" error={re.rate?.message}>
                <Input id="crRate" type="number" step="0.01" {...rateForm.register('rate')} />
              </Field>
              <Field label="시작일 (업무일자 이후)" htmlFor="crFrom" error={re.validFrom?.message}>
                <Input id="crFrom" type="date" {...rateForm.register('validFrom')} />
              </Field>
              <Field label="사유" htmlFor="crReason" error={re.reason?.message}>
                <Input id="crReason" {...rateForm.register('reason')} />
              </Field>
              <Button type="submit" size="sm" disabled={changeRate.isPending}>
                변경
              </Button>
              <div className="w-full">
                <ErrorMessage error={changeRate.error} />
              </div>
            </form>
          )}
        </div>
        <div>
          <h3 className="mb-1 text-xs font-semibold">
            정산·환수 (정산 {formatMoney(d?.settledTotal)} · 환수 {formatMoney(d?.clawbackTotal)} · 순액{' '}
            {formatMoney(d ? d.settledTotal + d.clawbackTotal : null)})
          </h3>
          <PagedList paging={commissionPaging.props}>
            <Table aria-label="정산·환수">
              <THead>
                <TR>
                  <SortTH {...commissions.header('date')}>일자</SortTH>
                  <SortTH {...commissions.header('application')}>신청</SortTH>
                  <SortTH {...commissions.header('type')}>구분</SortTH>
                  <SortTH {...commissions.header('base')} className="text-right">
                    실행금액
                  </SortTH>
                  <SortTH {...commissions.header('rate')} className="text-right">
                    수수료율
                  </SortTH>
                  <SortTH {...commissions.header('amount')} className="text-right">
                    금액
                  </SortTH>
                  <SortTH {...commissions.header('reason')}>사유</SortTH>
                </TR>
              </THead>
              <TBody>
                {d?.commissions.length === 0 && <EmptyRow colSpan={7} />}
                {commissionPaging.rows.map((c) => (
                  <TR key={c.id}>
                    <TD>{formatDate(c.occurredOn)}</TD>
                    <TD>
                      <Link className="text-primary hover:underline" to={`/applications/${c.applicationId}`}>
                        {c.applicationNo}
                      </Link>
                    </TD>
                    <TD>{commissionTypeLabel(c)}</TD>
                    <TD className="num">{formatMoney(c.baseAmount)}</TD>
                    <TD className="num">{formatRate(c.rate)}</TD>
                    <TD className="num">{formatMoney(c.amount)}</TD>
                    <TD className="text-muted-foreground">{c.reason ?? ''}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </PagedList>
        </div>
      </CardContent>
    </Card>
  )
}

/** 중개업자 관리 (guide 2.3, R56) */
export function BrokersPage() {
  const { can } = useAuth()
  const client = useQueryClient()
  const q = useQuery({ queryKey: originationKeys.brokers, queryFn: origination.brokers })
  const [selected, setSelected] = useState<number | null>(null)
  const scan = useMutation({
    mutationFn: origination.clawbackScan,
    onSuccess: () => client.invalidateQueries({ queryKey: originationKeys.brokers }),
  })
  const broker = q.data?.find((b) => b.id === selected)
  const { sorted, header } = useSort(q.data, {
    no: (b) => b.brokerNo,
    name: (b) => b.name,
    registrationNo: (b) => b.registrationNo,
    rate: (b) => b.currentRate,
    ips: (b) => b.allowedIps,
    status: (b) => (b.status === 'ACTIVE' ? '거래 중' : '정지'),
  })
  const paging = usePaging(sorted)
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-base font-semibold">중개업자</h1>
        {can('broker.manage') && (
          <div className="flex gap-2">
            <Button variant="outline" disabled={scan.isPending} onClick={() => scan.mutate()}>
              조기 완제 환수 점검
            </Button>
            <RegisterBrokerDialog />
          </div>
        )}
      </div>
      {scan.data && <Alert tone="info">조기 완제 환수 {scan.data.clawbacks}건을 기록했습니다.</Alert>}
      <ErrorMessage error={q.error ?? scan.error} />
      <p className="text-[11px] text-muted-foreground">
        수수료 = 실행금액 × 실행일 수수료율(원 미만 절사). 실행일부터 3개월 안 완제 또는 1년 안 불완전판매 확인 시 전액 환수 (R56).
      </p>
      <PagedList paging={paging.props}>
        <Table>
          <THead>
            <TR>
              <SortTH {...header('no')}>번호</SortTH>
              <SortTH {...header('name')}>이름</SortTH>
              <SortTH {...header('registrationNo')}>등록 번호</SortTH>
              <SortTH {...header('rate')} className="text-right">
                현재 수수료율
              </SortTH>
              <SortTH {...header('ips')}>허용 IP</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              <TH />
            </TR>
          </THead>
          <TBody>
            {q.data?.length === 0 && <EmptyRow colSpan={7} />}
            {paging.rows.map((b) => (
              <TR key={b.id}>
                <TD>{b.brokerNo}</TD>
                <TD>{b.name}</TD>
                <TD>{b.registrationNo}</TD>
                <TD className="num">{formatRate(b.currentRate)}</TD>
                <TD className="text-muted-foreground">{b.allowedIps ?? '제한 없음'}</TD>
                <TD>{b.status === 'ACTIVE' ? '거래 중' : '정지'}</TD>
                <TD>
                  <Button size="sm" variant={selected === b.id ? 'default' : 'outline'} onClick={() => setSelected(b.id)}>
                    상세
                  </Button>
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </PagedList>
      {broker && <BrokerDetail key={broker.id} broker={broker} />}
    </div>
  )
}
