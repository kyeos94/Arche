import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { type LimitInquiryResult, origination } from '@/api/origination'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, THead, TR, Table } from '@/components/ui/table'
import { formatDateTime, formatMoney, formatRate } from '@/lib/format'
import { DUE_DAYS, money, residentNo } from '@/lib/schemas'
import { useSort } from '@/lib/useSort'

const schema = z.object({
  platformRef: z.string().trim().min(1, '플랫폼 신청 번호를 입력하세요').max(60),
  name: z.string().trim().min(1, '이름을 입력하세요'),
  residentNo,
  birthDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'yyyy-MM-dd 형식으로 입력하세요'),
  productCode: z.string().min(1),
  requestedAmount: money,
  termMonths: z.coerce.number().int().positive(),
  dueDay: z.coerce.number(),
  creditInquiryConsent: z.boolean(),
})
type In = z.input<typeof schema>
type Out = z.output<typeof schema>

const newRef = () => `PF-${Date.now()}`

/**
 * Mock 대출 비교 플랫폼 클라이언트: 플랫폼이 우리 수신 API를 부르는 3단계(한도조회 → 신청 → 결과 통보)를 화면에서 흉내 낸다 (R57).
 * 플랫폼 API 키는 로컬 기본값(arche.integration.platform.mock.api-key)이다.
 */
export function PlatformSimulatorPage() {
  const [key, setKey] = useState('local-platform-key')
  const [limit, setLimit] = useState<LimitInquiryResult | null>(null)
  const form = useForm<In, unknown, Out>({
    resolver: zodResolver(schema),
    defaultValues: {
      platformRef: newRef(),
      name: '',
      residentNo: '',
      birthDate: '',
      productCode: 'P1',
      requestedAmount: '10000000',
      termMonths: 24,
      dueDay: 15,
      creditInquiryConsent: true,
    },
  })
  const notices = useQuery({ queryKey: ['mock-platform', key], queryFn: () => origination.platformNotices(key), retry: false })
  const inquire = useMutation({
    mutationFn: (v: Out) =>
      origination.platformLimit(key, {
        platformRef: v.platformRef,
        name: v.name,
        residentNo: v.residentNo,
        birthDate: v.birthDate,
        productCode: v.productCode,
        requestedAmount: v.requestedAmount,
        termMonths: v.termMonths,
        creditInquiryConsent: v.creditInquiryConsent,
        consentRef: v.creditInquiryConsent ? `PF-AGREE-${Date.now() % 100000}` : undefined,
      }),
    onSuccess: setLimit,
  })
  const apply = useMutation({
    mutationFn: (v: Out) =>
      origination.platformApply(key, {
        platformRef: v.platformRef,
        name: v.name,
        residentNo: v.residentNo,
        birthDate: v.birthDate,
        requestedAmount: v.requestedAmount,
        termMonths: v.termMonths,
        graceMonths: 0,
        dueDay: v.dueDay,
      }),
  })
  const { sorted, header } = useSort(notices.data, {
    received: (n) => n.receivedAt,
    platformRef: (n) => n.notice.platformRef,
    applicationNo: (n) => n.notice.applicationNo,
    result: (n) => n.notice.result,
    approved: (n) => n.notice.approvedAmount,
    delivered: (n) => n.delivered,
  })
  const e = form.formState.errors
  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">Mock 대출 비교 플랫폼</h1>
      <Alert tone="info">
        플랫폼 쪽 화면을 흉내 낸 데모 도구입니다. 식별번호 끝자리로 Mock 신용조회 등급이 정해집니다 (1~8 = 등급, 9·0 = 연체).
      </Alert>
      <Card>
        <CardHeader>
          <CardTitle>① 한도조회 → ② 신청</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="grid gap-2 sm:grid-cols-3">
            <Field label="플랫폼 API 키" htmlFor="pfKey">
              <Input id="pfKey" value={key} onChange={(ev) => setKey(ev.target.value)} />
            </Field>
            <Field label="플랫폼 신청 번호" htmlFor="pfRef" error={e.platformRef?.message}>
              <Input id="pfRef" {...form.register('platformRef')} />
            </Field>
            <Field label="상품" htmlFor="pfProduct">
              <Select id="pfProduct" {...form.register('productCode')}>
                {['P1', 'P2', 'P3', 'P4', 'P5'].map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </Select>
            </Field>
            <Field label="이름" htmlFor="pfName" error={e.name?.message}>
              <Input id="pfName" {...form.register('name')} />
            </Field>
            <Field label="주민등록번호" htmlFor="pfResident" error={e.residentNo?.message}>
              <Input id="pfResident" placeholder="900101-1234565" {...form.register('residentNo')} />
            </Field>
            <Field label="생년월일" htmlFor="pfBirth" error={e.birthDate?.message}>
              <Input id="pfBirth" type="date" {...form.register('birthDate')} />
            </Field>
            <Field label="희망 금액" htmlFor="pfAmount" error={e.requestedAmount?.message}>
              <Input id="pfAmount" inputMode="numeric" {...form.register('requestedAmount')} />
            </Field>
            <Field label="기간(개월)" htmlFor="pfTerm" error={e.termMonths?.message}>
              <Input id="pfTerm" type="number" {...form.register('termMonths')} />
            </Field>
            <Field label="약정일" htmlFor="pfDue">
              <Select id="pfDue" {...form.register('dueDay')}>
                {DUE_DAYS.map((d) => (
                  <option key={d} value={d}>
                    {d}일
                  </option>
                ))}
              </Select>
            </Field>
            <label className="flex items-center gap-2 text-xs sm:col-span-3">
              <input type="checkbox" {...form.register('creditInquiryConsent')} /> 고객이 플랫폼에서 신용조회에 동의함
            </label>
            <div className="flex flex-wrap gap-2 sm:col-span-3">
              <Button type="button" disabled={inquire.isPending} onClick={form.handleSubmit((v) => inquire.mutate(v))}>
                ① 한도조회
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={!limit?.offered || apply.isPending}
                onClick={form.handleSubmit((v) => apply.mutate(v))}
              >
                ② 신청
              </Button>
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  form.setValue('platformRef', newRef())
                  setLimit(null)
                  apply.reset()
                }}
              >
                새 신청 번호
              </Button>
            </div>
          </form>
          <div className="mt-2 space-y-2">
            <ErrorMessage error={inquire.error ?? apply.error} />
            {limit && (
              <Alert tone={limit.offered ? 'success' : 'warn'}>
                {limit.offered
                  ? `한도 ${formatMoney(limit.offeredLimit)} · 금리 ${formatRate(limit.offeredRate)} · ${limit.validUntil}까지 유효 (${limit.inquiryNo})`
                  : `한도 없음: ${limit.declineReason}`}
              </Alert>
            )}
            {apply.data && (
              <Alert tone="success">
                접수 {apply.data.applicationNo} · 상태 {apply.data.status}
                {apply.data.rejectReasonCode ? ` (${apply.data.rejectReasonCode})` : ''}
              </Alert>
            )}
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>③ 플랫폼이 받은 결과 통보</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <Button size="sm" variant="outline" onClick={() => notices.refetch()}>
            새로고침
          </Button>
          <ErrorMessage error={notices.error} />
          <Table>
            <THead>
              <TR>
                <SortTH {...header('received')}>수신</SortTH>
                <SortTH {...header('platformRef')}>플랫폼 번호</SortTH>
                <SortTH {...header('applicationNo')}>신청번호</SortTH>
                <SortTH {...header('result')}>결과</SortTH>
                <SortTH {...header('approved')} className="text-right">
                  승인액
                </SortTH>
                <SortTH {...header('delivered')}>전달</SortTH>
              </TR>
            </THead>
            <TBody>
              {notices.data?.length === 0 && <EmptyRow colSpan={6} />}
              {sorted.map((n, i) => (
                <TR key={i}>
                  <TD>{formatDateTime(n.receivedAt)}</TD>
                  <TD>{n.notice.platformRef}</TD>
                  <TD>{n.notice.applicationNo}</TD>
                  <TD>{n.notice.result}</TD>
                  <TD className="num">{formatMoney(n.notice.approvedAmount)}</TD>
                  <TD>{n.delivered ? '전달' : '실패(흉내)'}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
