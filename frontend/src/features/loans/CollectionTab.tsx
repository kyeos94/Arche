import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { ContactChannel, LoanDetail, PromiseJudgment } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { Can } from '@/features/auth/auth'
import { formatDate, formatDateTime, formatMoney, statusLabel } from '@/lib/format'
import { promiseSchema } from '@/lib/schemas'
import { useSort } from '@/lib/useSort'
import { cn } from '@/lib/utils'
import { CollectionStopCard } from '@/features/lifecycle/CollectionStopCard'

/** 추심 탭: 소멸시효(G13), 연락 유예, 연락 기록(G14), 자동 문자, 분할납부 약속(G19, 매입채권만) */
export function CollectionTab({ loan }: { loan: LoanDetail }) {
  return (
    <div className="space-y-3">
      <CollectionStopCard loanId={loan.id} businessDate={loan.businessDate} />
      <LimitationCard loanId={loan.id} businessDate={loan.businessDate} />
      <DeferralCard loanId={loan.id} businessDate={loan.businessDate} />
      <ContactsCard loanId={loan.id} />
      <MessagesCard loanId={loan.id} />
      {loan.repaymentMethod === 'ACQUIRED' && <PromisesCard loan={loan} />}
    </div>
  )
}

// ------------------------------------------------------------------ 소멸시효 (G13)

function LimitationCard({ loanId, businessDate }: { loanId: number; businessDate: string }) {
  const client = useQueryClient()
  const q = useQuery({ queryKey: queryKeys.loanLimitation(loanId), queryFn: () => endpoints.limitation(loanId) })
  const [type, setType] = useState('PAYMENT_ORDER_FILED')
  const [date, setDate] = useState(businessDate)
  const [memo, setMemo] = useState('')
  const save = useMutation({
    mutationFn: () => endpoints.recordLimitationEvent(loanId, { type, date, memo: memo || undefined }),
    onSuccess: async () => {
      setMemo('')
      await client.invalidateQueries({ queryKey: ['loan', loanId] })
    },
  })
  const info = q.data
  const events = useSort(info?.events, {
    date: (e) => e.date,
    type: (e) => statusLabel(e.type),
    memo: (e) => e.memo,
    counted: (e) => (e.counted ? '중단·연장' : '기산 전'),
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>소멸시효</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <ErrorMessage error={q.error} />
        {info && (
          <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs md:grid-cols-4">
            <span className="text-muted-foreground">기산일</span>
            <span>
              {formatDate(info.start)} {info.startReason && `(${info.startReason})`}
            </span>
            <span className="text-muted-foreground">완성 예정일</span>
            <span className={cn('font-semibold', info.completed && 'text-red-700')}>
              {formatDate(info.completionDate)} {info.completed && '— 완성 (추심 연락 차단)'}
            </span>
            <span className="text-muted-foreground">임박 알림 (180·90·30일 전)</span>
            <span className="md:col-span-3">{info.alertDates.map(formatDate).join(' · ') || '-'}</span>
          </div>
        )}
        {info && info.events.length > 0 && (
          <Table>
            <THead>
              <TR>
                <SortTH {...events.header('date')}>날짜</SortTH>
                <SortTH {...events.header('type')}>사유</SortTH>
                <SortTH {...events.header('memo')}>메모</SortTH>
                <SortTH {...events.header('counted')}>반영</SortTH>
              </TR>
            </THead>
            <TBody>
              {events.sorted.map((e, i) => (
                <TR key={i} className={cn(!e.counted && 'text-muted-foreground')}>
                  <TD>{formatDate(e.date)}</TD>
                  <TD>{statusLabel(e.type)}</TD>
                  <TD>{e.memo ?? ''}</TD>
                  <TD>{e.counted ? '중단·연장' : '기산 전'}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
        <Can permission="collection.legal">
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              save.mutate()
            }}
          >
            <Field label="사유" htmlFor="limType">
              <Select id="limType" value={type} onChange={(e) => setType(e.target.value)}>
                <option value="PAYMENT_ORDER_FILED">지급명령 신청 (중단)</option>
                <option value="SEIZURE">압류 (중단)</option>
                <option value="JUDGMENT_CONFIRMED">판결·지급명령 확정 (10년)</option>
              </Select>
            </Field>
            <Field label="날짜" htmlFor="limDate">
              <Input id="limDate" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
            </Field>
            <Field label="메모" htmlFor="limMemo">
              <Input id="limMemo" value={memo} onChange={(e) => setMemo(e.target.value)} />
            </Field>
            <Button type="submit" size="sm" disabled={save.isPending}>
              기록
            </Button>
          </form>
          <ErrorMessage error={save.error} />
        </Can>
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ 추심 연락 (G14)

function ContactsCard({ loanId }: { loanId: number }) {
  const client = useQueryClient()
  const q = useQuery({ queryKey: queryKeys.loanContacts(loanId), queryFn: () => endpoints.contacts(loanId) })
  const [channel, setChannel] = useState<ContactChannel>('CALL')
  const [memo, setMemo] = useState('')
  const save = useMutation({
    mutationFn: () => endpoints.recordContact(loanId, { channel, memo: memo || undefined }),
    onSuccess: async () => {
      setMemo('')
      await client.invalidateQueries({ queryKey: queryKeys.loanContacts(loanId) })
    },
  })
  const data = q.data
  const { sorted, header } = useSort(data?.contacts, {
    date: (c) => c.contactDate,
    contactedAt: (c) => c.contactedAt,
    channel: (c) => statusLabel(c.channel),
    result: (c) => statusLabel(c.result),
    count: (c) => (c.channel === 'STATUTORY_NOTICE' ? null : c.countInWindow),
    createdBy: (c) => c.createdBy,
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          추심 연락 — 직전 7일(당일 포함) 7회 · 오늘 남은 횟수{' '}
          <span className={cn(data?.remainingToday === 0 && 'text-red-700')}>{data?.remainingToday ?? '-'}</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <Can permission="collection.activity">
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              save.mutate()
            }}
          >
            <Field label="수단" htmlFor="contactChannel">
              <Select
                id="contactChannel"
                value={channel}
                onChange={(e) => setChannel(e.target.value as ContactChannel)}
              >
                <option value="CALL">전화</option>
                <option value="SMS">문자</option>
                <option value="VISIT">방문</option>
                <option value="STATUTORY_NOTICE">법정·계약상 통지 (한도 제외)</option>
              </Select>
            </Field>
            <Field label="메모" htmlFor="contactMemo">
              <Input id="contactMemo" value={memo} onChange={(e) => setMemo(e.target.value)} />
            </Field>
            <Button type="submit" size="sm" disabled={save.isPending}>
              연락 기록
            </Button>
          </form>
          {save.data && save.data.result !== 'ALLOWED' && (
            <Alert tone={save.data.result === 'BLOCKED' ? 'error' : 'warn'}>
              {statusLabel(save.data.result)}: {save.data.reason}
            </Alert>
          )}
          <ErrorMessage error={save.error} />
        </Can>
        <ErrorMessage error={q.error} />
        <Table>
          <THead>
            <TR>
              <SortTH {...header('date')}>날짜</SortTH>
              <SortTH {...header('contactedAt')}>기록 시각</SortTH>
              <SortTH {...header('channel')}>수단</SortTH>
              <SortTH {...header('result')}>결과</SortTH>
              <SortTH {...header('count')} className="text-right">
                창 안 기존 횟수
              </SortTH>
              <TH>사유·메모</TH>
              <SortTH {...header('createdBy')}>담당</SortTH>
            </TR>
          </THead>
          <TBody>
            {data && data.contacts.length === 0 && <EmptyRow colSpan={7} />}
            {sorted.map((c) => (
              <TR key={c.id} className={cn(c.result === 'BLOCKED' && 'text-muted-foreground')}>
                <TD>{formatDate(c.contactDate)}</TD>
                <TD>{formatDateTime(c.contactedAt)}</TD>
                <TD>{statusLabel(c.channel)}</TD>
                <TD>
                  <StatusBadge status={c.result} />
                </TD>
                <TD className="num">{c.channel === 'STATUTORY_NOTICE' ? '—' : c.countInWindow}</TD>
                <TD>{[c.reason, c.memo].filter(Boolean).join(' / ')}</TD>
                <TD>{c.createdBy}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ 분할납부 약속 (G19)

type PromiseInput = z.input<typeof promiseSchema>
type PromiseOutput = z.output<typeof promiseSchema>

function PromisesCard({ loan }: { loan: LoanDetail }) {
  const client = useQueryClient()
  const q = useQuery({ queryKey: queryKeys.loanPromises(loan.id), queryFn: () => endpoints.promises(loan.id) })
  const form = useForm<PromiseInput, unknown, PromiseOutput>({
    resolver: zodResolver(promiseSchema),
    defaultValues: { firstMonth: loan.businessDate.slice(0, 7), day: 7, amount: '', months: 6, memo: '' },
  })
  const save = useMutation({
    mutationFn: (v: PromiseOutput) => endpoints.registerPromise(loan.id, v),
    onSuccess: async () => {
      form.reset()
      await client.invalidateQueries({ queryKey: queryKeys.loanPromises(loan.id) })
    },
  })
  const errors = form.formState.errors
  const hasActive = q.data?.some((p) => p.status === 'ACTIVE')
  return (
    <Card>
      <CardHeader>
        <CardTitle>분할납부 약속 — 약속일 다음 날까지 약속액의 90% 이상 입금이면 이행, 3회 연속 불이행이면 해제</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {!hasActive && (
          <Can permission="collection.activity">
            <form className="flex flex-wrap items-end gap-2" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
              <Field label="첫 약속 월" htmlFor="pMonth" error={errors.firstMonth?.message}>
                <Input id="pMonth" type="month" {...form.register('firstMonth')} />
              </Field>
              <Field label="매월 약속일" htmlFor="pDay" error={errors.day?.message}>
                <Input id="pDay" type="number" className="w-20" {...form.register('day')} />
              </Field>
              <Field label="약속액" htmlFor="pAmount" error={errors.amount?.message}>
                <Input id="pAmount" inputMode="numeric" {...form.register('amount')} />
              </Field>
              <Field label="회차" htmlFor="pMonths" error={errors.months?.message}>
                <Input id="pMonths" type="number" className="w-20" {...form.register('months')} />
              </Field>
              <Field label="메모" htmlFor="pMemo">
                <Input id="pMemo" {...form.register('memo')} />
              </Field>
              <Button type="submit" size="sm" disabled={save.isPending}>
                약속 등록
              </Button>
            </form>
            <ErrorMessage error={save.error} />
          </Can>
        )}
        <ErrorMessage error={q.error} />
        {q.data && q.data.length === 0 && <p className="text-xs text-muted-foreground">등록된 약속이 없습니다.</p>}
        {q.data?.map((p) => (
          <div key={p.id} className="space-y-1">
            <div className="flex items-center gap-2 text-xs">
              <StatusBadge status={p.status} />
              <span>
                매월 {p.day}일 {formatMoney(p.amount)} × {p.months}회 (등록 {formatDate(p.registeredOn)})
              </span>
              {p.legalReview && <span className="font-semibold text-red-700">법조치 검토 대상</span>}
              {p.memo && <span className="text-muted-foreground">{p.memo}</span>}
            </div>
            <JudgmentsTable rows={p.judgments} />
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

const verdict = (j: PromiseJudgment) => (j.fulfilled === null ? '대기' : j.fulfilled ? '이행' : '불이행')

/** 약속 회차별 판정. 약속마다 하나씩 그려 정렬 상태를 따로 가진다 */
function JudgmentsTable({ rows }: { rows: PromiseJudgment[] }) {
  const { sorted, header } = useSort(rows, {
    index: (j) => j.index,
    promiseDate: (j) => j.promiseDate,
    deadline: (j) => j.deadline,
    paid: (j) => (j.fulfilled === null ? null : j.paid),
    verdict: (j) => verdict(j),
    misses: (j) => (j.fulfilled === null ? null : j.consecutiveMisses),
    action: (j) => (j.fulfilled === null ? null : statusLabel(j.action)),
  })
  return (
    <Table>
      <THead>
        <TR>
          <SortTH {...header('index')}>회차</SortTH>
          <SortTH {...header('promiseDate')}>약속일</SortTH>
          <SortTH {...header('deadline')}>기한</SortTH>
          <SortTH {...header('paid')} className="text-right">
            판정 대상 입금
          </SortTH>
          <SortTH {...header('verdict')}>판정</SortTH>
          <SortTH {...header('misses')} className="text-right">
            연속 불이행
          </SortTH>
          <SortTH {...header('action')}>처리</SortTH>
        </TR>
      </THead>
      <TBody>
        {sorted.map((j) => (
          <TR key={j.index}>
            <TD>{j.index}</TD>
            <TD>{formatDate(j.promiseDate)}</TD>
            <TD>{formatDate(j.deadline)}</TD>
            <TD className="num">{j.fulfilled === null ? '' : formatMoney(j.paid)}</TD>
            <TD
              className={cn(j.fulfilled === false && 'text-red-700', j.fulfilled === null && 'text-muted-foreground')}
            >
              {verdict(j)}
            </TD>
            <TD className="num">{j.fulfilled === null ? '' : j.consecutiveMisses}</TD>
            <TD>{j.fulfilled === null ? '' : statusLabel(j.action)}</TD>
          </TR>
        ))}
      </TBody>
    </Table>
  )
}

// ------------------------------------------------------------------ 연락 유예 (정책서 6장)

function addMonths(date: string, months: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const target = new Date(Date.UTC(y, m - 1 + months, 1))
  const last = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate()
  target.setUTCDate(Math.min(d, last))
  return target.toISOString().slice(0, 10)
}

function DeferralCard({ loanId, businessDate }: { loanId: number; businessDate: string }) {
  const client = useQueryClient()
  const q = useQuery({ queryKey: queryKeys.loanDeferrals(loanId), queryFn: () => endpoints.deferrals(loanId) })
  const [start, setStart] = useState(businessDate)
  const [end, setEnd] = useState(addMonths(businessDate, 1))
  const [reason, setReason] = useState('')
  const refresh = () => client.invalidateQueries({ queryKey: ['loan', loanId] })
  const register = useMutation({
    mutationFn: () => endpoints.registerDeferral(loanId, { startDate: start, endDate: end, reason }),
    onSuccess: async () => {
      setReason('')
      await refresh()
    },
  })
  const extend = useMutation({
    mutationFn: (v: { id: number; endDate: string }) => endpoints.extendDeferral(loanId, v.id, v.endDate),
    onSuccess: refresh,
  })
  const finish = useMutation({ mutationFn: (id: number) => endpoints.endDeferral(loanId, id), onSuccess: refresh })
  const active = q.data?.find((d) => d.status === 'ACTIVE' && d.endDate >= businessDate)
  const deferrals = useSort(q.data, {
    period: (d) => d.startDate,
    reason: (d) => d.reason,
    status: (d) => statusLabel(d.status),
    extended: (d) => d.extended,
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          연락 유예 — 입원·재난 등 고객 요청 시 최대 3개월, 1회 연장. 유예 중에는 추심 연락·연체 문자 차단
          {active?.activeToday && <span className="ml-2 text-amber-700">유예 중</span>}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {!active && (
          <Can permission="collection.activity">
            <form
              className="flex flex-wrap items-end gap-2"
              onSubmit={(e) => {
                e.preventDefault()
                register.mutate()
              }}
            >
              <Field label="시작일" htmlFor="defStart">
                <Input id="defStart" type="date" value={start} onChange={(e) => setStart(e.target.value)} />
              </Field>
              <Field label={`종료일 (최대 ${addMonths(start, 3)})`} htmlFor="defEnd">
                <Input
                  id="defEnd"
                  type="date"
                  value={end}
                  max={addMonths(start, 3)}
                  onChange={(e) => setEnd(e.target.value)}
                />
              </Field>
              <Field label="사유" htmlFor="defReason">
                <Input id="defReason" value={reason} onChange={(e) => setReason(e.target.value)} />
              </Field>
              <Button type="submit" size="sm" disabled={register.isPending}>
                유예 등록
              </Button>
            </form>
          </Can>
        )}
        <ErrorMessage error={q.error ?? register.error ?? extend.error ?? finish.error} />
        <Table>
          <THead>
            <TR>
              <SortTH {...deferrals.header('period')}>기간</SortTH>
              <SortTH {...deferrals.header('reason')}>사유</SortTH>
              <SortTH {...deferrals.header('status')}>상태</SortTH>
              <SortTH {...deferrals.header('extended')}>연장</SortTH>
              <TH />
            </TR>
          </THead>
          <TBody>
            {q.data && q.data.length === 0 && <EmptyRow colSpan={5} />}
            {deferrals.sorted.map((d) => (
              <TR key={d.id}>
                <TD>
                  {formatDate(d.startDate)} ~ {formatDate(d.endDate)}
                </TD>
                <TD>{d.reason}</TD>
                <TD>
                  <StatusBadge status={d.status} />
                  {d.endedOn && <span className="ml-1 text-muted-foreground">{formatDate(d.endedOn)} 종료</span>}
                </TD>
                <TD>{d.extended ? `연장함 (원래 ${formatDate(d.originalEnd)})` : '-'}</TD>
                <TD className="space-x-1 text-right">
                  {d.status === 'ACTIVE' && (
                    <Can permission="collection.activity">
                      {!d.extended && (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            const next = window.prompt('연장 종료일 (최대 3개월 뒤)', addMonths(d.endDate, 3))
                            if (next) extend.mutate({ id: d.id, endDate: next })
                          }}
                        >
                          연장
                        </Button>
                      )}
                      <Button size="sm" variant="outline" onClick={() => finish.mutate(d.id)}>
                        종료
                      </Button>
                    </Can>
                  )}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ 자동 문자 (정책서 6장)

function MessagesCard({ loanId }: { loanId: number }) {
  const q = useQuery({ queryKey: queryKeys.loanMessages(loanId), queryFn: () => endpoints.messages(loanId) })
  const { sorted, header } = useSort(q.data, {
    scheduledDate: (m) => m.scheduledDate,
    type: (m) => statusLabel(m.type),
    body: (m) => m.body,
    status: (m) => statusLabel(m.status),
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>자동 문자 — 일마감이 다음 날 08:00 발송분을 만들고, 발송 직전에 한도·유예·시효를 다시 판정</CardTitle>
      </CardHeader>
      <CardContent>
        <ErrorMessage error={q.error} />
        <Table>
          <THead>
            <TR>
              <SortTH {...header('scheduledDate')}>예정일</SortTH>
              <SortTH {...header('type')}>종류</SortTH>
              <SortTH {...header('body')}>내용</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              <TH>발송·사유</TH>
            </TR>
          </THead>
          <TBody>
            {q.data && q.data.length === 0 && <EmptyRow colSpan={5} />}
            {sorted.map((m) => (
              <TR key={m.id} className={cn(m.status === 'SKIPPED' && 'text-muted-foreground')}>
                <TD>{formatDate(m.scheduledDate)}</TD>
                <TD>{statusLabel(m.type)}</TD>
                <TD className="max-w-md truncate" title={m.body}>
                  {m.body}
                </TD>
                <TD>
                  <StatusBadge status={m.status} />
                </TD>
                <TD>{m.status === 'SENT' ? formatDateTime(m.sentAt) : (m.skipReason ?? '')}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}
