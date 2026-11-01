import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import { lifecycleApi, lifecycleKeys, stopReasonLabels, type CollectionStopDto } from '@/api/lifecycle'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { Can } from '@/features/auth/auth'
import { formatDate, formatRate } from '@/lib/format'
import { STOP_REASONS, collectionStopSchema, reliefSchema } from '@/lib/lifecycleSchemas'
import { ReasonDialog, useInvalidateLifecycle } from './shared'

/**
 * 추심 중지 (R45·R46): 사망(상속)·개인회생·파산·공적 채무조정(가상). 고객의 열린 계약 전체에 걸리고, 중지 중에는 추심 연락과
 * 자동 문자(완제 안내 제외)를 하지 않는다. 개인회생·파산은 사유 발생일부터 이자를 붙이지 않는다.
 */
export function CollectionStopCard({ loanId, businessDate }: { loanId: number; businessDate: string }) {
  const q = useQuery({
    queryKey: lifecycleKeys.collectionStops(loanId),
    queryFn: () => lifecycleApi.collectionStops(loanId),
  })
  const invalidate = useInvalidateLifecycle()
  const active = q.data?.find((s) => s.status === 'ACTIVE')
  return (
    <Card>
      <CardHeader>
        <CardTitle>추심 중지</CardTitle>
        <Can permission="collection.stop">
          {!active && <RegisterDialog loanId={loanId} businessDate={businessDate} />}
        </Can>
      </CardHeader>
      <CardContent className="space-y-2">
        <ErrorMessage error={q.error} />
        {active && (
          <Alert tone="warn">
            추심 중지 중 — {active.reasonLabel} ({formatDate(active.effectiveDate)}부터, 묶음 {active.groupNo}). 추심 연락·자동
            문자를 보내지 않습니다{active.suspendsInterest ? '. 이자 부과 중단 중' : ''}.
          </Alert>
        )}
        <Table>
          <THead>
            <TR>
              <TH>사유</TH>
              <TH>발생일</TH>
              <TH>근거</TH>
              <TH>이자</TH>
              <TH>조정 금리</TH>
              <TH>상태</TH>
              <TH>해제</TH>
              <TH />
            </TR>
          </THead>
          <TBody>
            {q.data?.length === 0 && <EmptyRow colSpan={8} />}
            {q.data?.map((s) => (
              <TR key={s.id}>
                <TD>{s.reasonLabel}</TD>
                <TD>{formatDate(s.effectiveDate)}</TD>
                <TD>{s.memo}</TD>
                <TD>{s.suspendsInterest ? '부과 중단' : '변경 없음'}</TD>
                <TD>{s.reliefRate === null ? '-' : `${formatRate(s.reliefRate)} (${formatDate(s.reliefFrom)}부터)`}</TD>
                <TD>
                  <StatusBadge status={s.status} />
                </TD>
                <TD>{s.releasedOn ? `${formatDate(s.releasedOn)} · ${s.releaseReason ?? ''}` : '-'}</TD>
                <TD>
                  {s.status === 'ACTIVE' && (
                    <Can permission="collection.stop">
                      <div className="flex gap-1">
                        {s.reason === 'PUBLIC_WORKOUT' && s.reliefFrom === null && (
                          <ReliefDialog loanId={loanId} stop={s} businessDate={businessDate} />
                        )}
                        <ReasonDialog
                          trigger="해제"
                          title="추심 중지 해제"
                          description="같은 묶음(고객의 모든 계약)이 함께 해제되고, 이자 부과 중단은 오늘로 끝납니다 (R46)."
                          submitLabel="해제"
                          onSubmit={async (reason) => {
                            await lifecycleApi.releaseStop(loanId, s.id, reason)
                            await invalidate()
                          }}
                        />
                      </div>
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

type StopInput = z.input<typeof collectionStopSchema>

function RegisterDialog({ loanId, businessDate }: { loanId: number; businessDate: string }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLifecycle()
  const form = useForm<StopInput>({
    resolver: zodResolver(collectionStopSchema),
    defaultValues: { reason: 'DECEASED', effectiveDate: businessDate, memo: '' },
  })
  const reason = form.watch('reason')
  const save = useMutation({
    mutationFn: (v: StopInput) => lifecycleApi.registerStop(loanId, v),
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
          추심 중지 등록
        </Button>
      </DialogTrigger>
      <DialogContent
        title="추심 중지 등록 (R45)"
        description="이 고객의 완제·매각되지 않은 모든 계약에 걸립니다. 입금은 계속 받습니다."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="사유" htmlFor="stopReason">
              <Select id="stopReason" {...form.register('reason')}>
                {STOP_REASONS.map((r) => (
                  <option key={r} value={r}>
                    {stopReasonLabels[r]}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="사유 발생일" htmlFor="effectiveDate" error={errors.effectiveDate?.message}>
              <Input id="effectiveDate" type="date" {...form.register('effectiveDate')} />
            </Field>
          </div>
          <Field label="근거 (통지 수령 내용·사건 정보 등)" htmlFor="stopMemo" error={errors.memo?.message}>
            <Input id="stopMemo" {...form.register('memo')} />
          </Field>
          {(reason === 'REHABILITATION' || reason === 'BANKRUPTCY') && (
            <Alert tone="warn">사유 발생일부터 정상이자·가산이자를 붙이지 않습니다. 과거 날짜면 소급 재계산합니다 (R46).</Alert>
          )}
          {reason === 'PUBLIC_WORKOUT' && (
            <Alert tone="info">접수 시에는 추심만 중지합니다. 조정이 확정되면 “조정 확정”으로 조정 금리를 적용하세요 (R46).</Alert>
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

type ReliefInput = z.input<typeof reliefSchema>
type ReliefOutput = z.output<typeof reliefSchema>

function ReliefDialog({ loanId, stop, businessDate }: { loanId: number; stop: CollectionStopDto; businessDate: string }) {
  const [open, setOpen] = useState(false)
  const invalidate = useInvalidateLifecycle()
  const form = useForm<ReliefInput, unknown, ReliefOutput>({
    resolver: zodResolver(reliefSchema),
    defaultValues: { rate: '', validFrom: businessDate },
  })
  const save = useMutation({
    mutationFn: (v: ReliefOutput) => lifecycleApi.confirmRelief(loanId, stop.id, v),
    onSuccess: async () => {
      await invalidate(loanId)
      setOpen(false)
    },
  })
  const errors = form.formState.errors
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm">조정 확정</Button>
      </DialogTrigger>
      <DialogContent
        title="공적 채무조정(가상) 확정 (R46)"
        description="확정일부터 조정 금리를 금리 이력에 추가합니다. 현재 적용 이율 이하만 가능합니다."
      >
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="조정 금리 (%)" htmlFor="reliefRate" error={errors.rate?.message}>
              <Input id="reliefRate" inputMode="decimal" {...form.register('rate')} />
            </Field>
            <Field label="확정일" htmlFor="reliefFrom" error={errors.validFrom?.message}>
              <Input id="reliefFrom" type="date" {...form.register('validFrom')} />
            </Field>
          </div>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              확정
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
