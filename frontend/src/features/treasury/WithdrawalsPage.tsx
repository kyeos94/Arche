import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { Can, useAuth } from '@/features/auth/auth'
import {
  type Withdrawal,
  type WithdrawalType,
  sliceApi,
  sliceKeys,
  sliceLabel,
  sliceTones,
} from '@/features/communication/api'
import { withdrawalSchema } from '@/features/communication/schemas'
import { formatDate, formatDateTime, formatMoney } from '@/lib/format'
import { useSort } from '@/lib/useSort'

const OPEN_STATUSES = 'REQUESTED,FIRST_APPROVED,SENDING,UNKNOWN'

/**
 * 출금 작성·승인 (guide 6.4·13.3, R62~R64). 작성은 출납, 승인은 1,000만원 이하 채권팀장 또는 본부장,
 * 초과는 채권팀장 → 본부장 2단계. 작성자는 승인할 수 없다.
 */
export function WithdrawalsPage() {
  const [filter, setFilter] = useState(OPEN_STATUSES)
  const q = useQuery({ queryKey: sliceKeys.withdrawals(filter), queryFn: () => sliceApi.withdrawals(filter) })
  const { sorted, header } = useSort(q.data, {
    no: (w) => w.withdrawalNo,
    type: (w) => sliceLabel(w.type),
    amount: (w) => w.amount,
    reason: (w) => w.reason,
    requested: (w) => w.requestedOn,
    status: (w) => sliceLabel(w.status),
  })
  return (
    <div className="space-y-3">
      <Can permission="withdrawal.create">
        <CreateWithdrawal />
      </Can>
      <Card>
        <CardHeader>
          <CardTitle>출금 목록</CardTitle>
          <div className="flex gap-1" role="tablist">
            {[
              [OPEN_STATUSES, '진행 중'],
              ['', '전체 (최근 200건)'],
            ].map(([v, label]) => (
              <Button
                key={label}
                size="sm"
                role="tab"
                aria-selected={filter === v}
                variant={filter === v ? 'default' : 'outline'}
                onClick={() => setFilter(v)}
              >
                {label}
              </Button>
            ))}
          </div>
        </CardHeader>
        <CardContent>
          <ErrorMessage error={q.error} />
          <Table>
            <THead>
              <TR>
                <SortTH {...header('no')}>번호</SortTH>
                <SortTH {...header('type')}>유형</SortTH>
                <SortTH {...header('amount')} className="text-right">
                  금액
                </SortTH>
                <TH>받는 계좌</TH>
                <SortTH {...header('reason')}>사유</SortTH>
                <SortTH {...header('requested')}>작성</SortTH>
                <TH>승인</TH>
                <SortTH {...header('status')}>상태</SortTH>
                <TH>결과</TH>
                <TH />
              </TR>
            </THead>
            <TBody>
              {q.data?.length === 0 && <EmptyRow colSpan={10} />}
              {sorted.map((w) => (
                <WithdrawalRow key={w.id} w={w} />
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}

function WithdrawalRow({ w }: { w: Withdrawal }) {
  const client = useQueryClient()
  const { can } = useAuth()
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const done = () => client.invalidateQueries({ queryKey: ['withdrawals'] })
  const act = useMutation({
    mutationFn: (kind: 'approve' | 'reject' | 'cancel' | 'inquire') => {
      if (kind === 'approve') return sliceApi.approveWithdrawal(w.id)
      if (kind === 'reject') return sliceApi.rejectWithdrawal(w.id, reason)
      if (kind === 'cancel') return sliceApi.cancelWithdrawal(w.id)
      return sliceApi.inquireWithdrawal(w.id)
    },
    onSuccess: async () => {
      setRejecting(false)
      setReason('')
      await done()
    },
  })
  const open = w.status === 'REQUESTED' || w.status === 'FIRST_APPROVED'
  const approver = can('withdrawal.approve.small') || can('withdrawal.approve.large')
  return (
    <>
      <TR>
        <TD className="font-mono">{w.withdrawalNo}</TD>
        <TD>
          {sliceLabel(w.type)}
          {w.twoStep && <span className="ml-1 text-[11px] text-amber-800">2단계</span>}
        </TD>
        <TD className="num">{formatMoney(w.amount)}</TD>
        <TD>
          {w.bankName} {w.payeeAccountMasked}
          <span className="block text-[11px] text-muted-foreground">예금주 {w.holderName}</span>
        </TD>
        <TD className="max-w-48 truncate" title={w.reason}>
          {w.reason}
        </TD>
        <TD>
          {formatDate(w.requestedOn)}
          <span className="block text-[11px] text-muted-foreground">{w.requestedBy}</span>
        </TD>
        <TD className="text-[11px]">
          {w.firstApprovedBy && <div>1단계 {w.firstApprovedBy}</div>}
          {w.finalApprovedBy && <div>최종 {w.finalApprovedBy}</div>}
          {w.rejectedBy && <div>반려 {w.rejectedBy}: {w.rejectReason}</div>}
          {w.nextStep && <div className="text-amber-800">다음: {w.nextStep}</div>}
        </TD>
        <TD>
          <Badge className={sliceTones[w.status] ?? 'bg-muted'}>{sliceLabel(w.status)}</Badge>
        </TD>
        <TD className="text-[11px]">
          {w.resultCode && (
            <>
              {w.resultCode} · {w.resultMessage}
              <span className="block text-muted-foreground">
                {formatDateTime(w.completedAt ?? w.sentAt)} {w.inquiryCount > 0 && `(조회 ${w.inquiryCount}회)`}
              </span>
            </>
          )}
        </TD>
        <TD>
          <div className="flex gap-1">
            {open && approver && (
              <>
                <Button size="sm" disabled={act.isPending} onClick={() => act.mutate('approve')}>
                  승인
                </Button>
                <Button size="sm" variant="outline" disabled={act.isPending} onClick={() => setRejecting((r) => !r)}>
                  반려
                </Button>
              </>
            )}
            {open && can('withdrawal.create') && (
              <Button size="sm" variant="outline" disabled={act.isPending} onClick={() => act.mutate('cancel')}>
                작성 취소
              </Button>
            )}
            {w.status === 'UNKNOWN' && (
              <Button size="sm" variant="outline" disabled={act.isPending} onClick={() => act.mutate('inquire')}>
                결과 조회
              </Button>
            )}
          </div>
        </TD>
      </TR>
      {(rejecting || act.error) && (
        <tr>
          <td colSpan={10} className="px-2 pb-2">
            {rejecting && (
              <form
                className="flex items-end gap-2"
                onSubmit={(e) => {
                  e.preventDefault()
                  act.mutate('reject')
                }}
              >
                <Field label="반려 사유" htmlFor={`rej-${w.id}`}>
                  <Input id={`rej-${w.id}`} value={reason} onChange={(e) => setReason(e.target.value)} />
                </Field>
                <Button type="submit" size="sm" variant="destructive" disabled={!reason.trim() || act.isPending}>
                  반려 확정
                </Button>
              </form>
            )}
            <ErrorMessage error={act.error} />
          </td>
        </tr>
      )}
    </>
  )
}

type FormIn = z.input<typeof withdrawalSchema>
type FormOut = z.output<typeof withdrawalSchema>

function CreateWithdrawal() {
  const client = useQueryClient()
  const [type, setType] = useState<WithdrawalType>('UNMATCHED_REFUND')
  const [target, setTarget] = useState('')
  const candidates = useQuery({ queryKey: sliceKeys.withdrawalCandidates, queryFn: sliceApi.withdrawalCandidates })
  const form = useForm<FormIn, unknown, FormOut>({
    resolver: zodResolver(withdrawalSchema),
    defaultValues: { bankName: '', accountNo: '', holderName: '', amount: '', reason: '' },
  })
  const save = useMutation({
    mutationFn: (v: FormOut) =>
      sliceApi.createWithdrawal({
        type,
        ...v,
        unmatchedId: type === 'UNMATCHED_REFUND' ? Number(target) : undefined,
        accountId: type === 'OVERPAYMENT_REFUND' ? Number(target) : undefined,
      }),
    onSuccess: async () => {
      form.reset()
      setTarget('')
      await client.invalidateQueries({ queryKey: ['withdrawals'] })
      await client.invalidateQueries({ queryKey: ['unmatched'] })
    },
  })
  const e = form.formState.errors
  const unmatched = candidates.data?.unmatched ?? []
  const refunds = candidates.data?.refunds ?? []

  function pick(value: string) {
    setTarget(value)
    if (type === 'UNMATCHED_REFUND') {
      const u = unmatched.find((x) => String(x.unmatchedId) === value)
      if (u) {
        form.setValue('amount', String(u.amount))
        form.setValue('holderName', u.depositorName ?? '')
      }
    } else {
      const r = refunds.find((x) => String(x.accountId) === value)
      if (r) form.setValue('amount', String(r.available))
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>출금 작성 (출납)</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <Alert tone="info">
          미확인 입금은 전액을 입금자 본인 계좌로, 과입금(완제 후 반환 대기)은 반환 가능액 안에서 채무자 본인 계좌로만 반환합니다.
          계약 중 가수금은 반환하지 않습니다. 작성하면 금액이 예약되고, 반려·취소·송금 실패 시 되돌아갑니다 (R62).
        </Alert>
        <form className="flex flex-wrap items-end gap-2" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <Field label="유형" htmlFor="wType">
            <Select
              id="wType"
              value={type}
              onChange={(ev) => {
                setType(ev.target.value as WithdrawalType)
                setTarget('')
              }}
            >
              <option value="UNMATCHED_REFUND">미확인 입금 반환</option>
              <option value="OVERPAYMENT_REFUND">과입금 반환</option>
            </Select>
          </Field>
          <Field label={type === 'UNMATCHED_REFUND' ? '미확인 입금' : '계약 (반환 가능액)'} htmlFor="wTarget">
            <Select id="wTarget" value={target} onChange={(ev) => pick(ev.target.value)}>
              <option value="">선택</option>
              {type === 'UNMATCHED_REFUND'
                ? unmatched.map((u) => (
                    <option key={u.unmatchedId} value={u.unmatchedId}>
                      {formatDate(u.bankDate)} · {formatMoney(u.amount)} · {u.depositorName ?? '입금자명 없음'}
                    </option>
                  ))
                : refunds.map((r) => (
                    <option key={r.accountId} value={r.accountId}>
                      {r.accountNo} · {formatMoney(r.available)}
                    </option>
                  ))}
            </Select>
          </Field>
          <Field label="금액" htmlFor="wAmount" error={e.amount?.message}>
            <Input id="wAmount" className="w-32" inputMode="numeric" {...form.register('amount')} />
          </Field>
          <Field label="은행" htmlFor="wBank" error={e.bankName?.message}>
            <Input id="wBank" className="w-28" placeholder="예: 가상은행" {...form.register('bankName')} />
          </Field>
          <Field label="계좌번호" htmlFor="wAccount" error={e.accountNo?.message}>
            <Input id="wAccount" autoComplete="off" {...form.register('accountNo')} />
          </Field>
          <Field label="예금주" htmlFor="wHolder" error={e.holderName?.message}>
            <Input id="wHolder" className="w-28" {...form.register('holderName')} />
          </Field>
          <Field label="사유" htmlFor="wReason" error={e.reason?.message}>
            <Input id="wReason" {...form.register('reason')} />
          </Field>
          <Button type="submit" disabled={!target || save.isPending}>
            작성 (예금주 확인)
          </Button>
        </form>
        {save.data && <Alert tone="success">{save.data.withdrawalNo} 작성 — {save.data.nextStep}</Alert>}
        <ErrorMessage error={save.error ?? candidates.error} />
      </CardContent>
    </Card>
  )
}
