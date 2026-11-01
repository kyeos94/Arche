import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import type { z } from 'zod'
import { lifecycleApi, adjustmentTypeLabels, type AdjustmentRequestDto } from '@/api/lifecycle'
import { ErrorMessage } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatMoney } from '@/lib/format'
import { reasonSchema } from '@/lib/lifecycleSchemas'

/** 생애주기 변경 뒤: 계약 쿼리 전부 + 목록·후보·결재 목록 */
export function useInvalidateLifecycle() {
  const client = useQueryClient()
  return async (loanId?: number) => {
    await Promise.all([
      client.invalidateQueries({ queryKey: loanId === undefined ? ['loan'] : ['loan', loanId] }),
      client.invalidateQueries({ queryKey: ['loans'] }),
      client.invalidateQueries({ queryKey: ['charge-off-candidates'] }),
      client.invalidateQueries({ queryKey: ['adjustment-requests'] }),
    ])
  }
}

type ReasonInput = z.input<typeof reasonSchema>

/** 사유 하나만 받는 확정 대화상자 (상각 확정·환매) */
export function ReasonDialog({
  trigger,
  title,
  description,
  submitLabel,
  disabled,
  onSubmit,
}: {
  trigger: string
  title: string
  description: string
  submitLabel: string
  disabled?: boolean
  onSubmit: (reason: string) => Promise<unknown>
}) {
  const [open, setOpen] = useState(false)
  const form = useForm<ReasonInput>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: '' } })
  const save = useMutation({
    mutationFn: (v: ReasonInput) => onSubmit(v.reason),
    onSuccess: () => {
      setOpen(false)
      form.reset()
    },
  })
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" disabled={disabled}>
          {trigger}
        </Button>
      </DialogTrigger>
      <DialogContent title={title} description={description}>
        <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
          <Field label="사유" htmlFor="lifecycle-reason" error={form.formState.errors.reason?.message}>
            <Input id="lifecycle-reason" {...form.register('reason')} />
          </Field>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              {submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** 요청 내용 한 줄 요약 */
export function describeRequest(r: AdjustmentRequestDto): string {
  switch (r.type) {
    case 'ADJUSTMENT': {
      const parts = [
        r.principalDown && `원금 -${formatMoney(r.principalDown)}`,
        r.normalDown && `정상이자 -${formatMoney(r.normalDown)}`,
        r.lateDown && `가산이자 -${formatMoney(r.lateDown)}`,
        r.preAcqDown && `양수 전 미수이자 -${formatMoney(r.preAcqDown)}`,
        r.costDelta && `비용 ${r.costDelta > 0 ? '+' : ''}${formatMoney(r.costDelta)}`,
      ].filter(Boolean)
      return parts.join(', ')
    }
    case 'SETOFF':
      return `${r.sourceAccountNo ?? '-'}의 ${r.setoffSource === 'REFUND' ? '반환 대기' : '가수금'} ${formatMoney(r.amount)} → 이 계약`
    case 'REVERT_SETOFF':
      return `상계 #${r.targetEventId ?? '-'} 취소`
    default:
      return adjustmentTypeLabels[r.type]
  }
}

/** 결재 요청 목록. 승인 권한이 있고 본인 요청이 아니면 승인·반려 버튼 (서버가 다시 검사) */
export function RequestsTable({ rows, showAccount }: { rows: AdjustmentRequestDto[]; showAccount?: boolean }) {
  const { can, me } = useAuth()
  const invalidate = useInvalidateLifecycle()
  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: number; approve: boolean }) => lifecycleApi.decide(id, approve),
    onSuccess: (r) => invalidate(r.accountId),
  })
  const canApprove = can('loan.adjust.approve')
  const cols = showAccount ? 10 : 9
  return (
    <div className="space-y-2">
      <ErrorMessage error={decide.error} />
      <Table>
        <THead>
          <TR>
            <TH>요청번호</TH>
            <TH>유형</TH>
            {showAccount && <TH>계약</TH>}
            <TH>내용</TH>
            <TH>사유</TH>
            <TH>요청</TH>
            <TH>결재</TH>
            <TH>상태</TH>
            <TH>사용일</TH>
            <TH />
          </TR>
        </THead>
        <TBody>
          {rows.length === 0 && <EmptyRow colSpan={cols} />}
          {rows.map((r) => (
            <TR key={r.id}>
              <TD>{r.requestNo}</TD>
              <TD>{adjustmentTypeLabels[r.type]}</TD>
              {showAccount && (
                <TD>
                  <Link className="text-primary hover:underline" to={`/loans/${r.accountId}`}>
                    {r.accountNo}
                  </Link>
                </TD>
              )}
              <TD>{describeRequest(r)}</TD>
              <TD>{r.reason}</TD>
              <TD>
                {r.requestedBy} · {formatDate(r.requestedOn)}
              </TD>
              <TD>{r.decidedBy ? `${r.decidedBy} · ${formatDate(r.decidedOn)}` : '-'}</TD>
              <TD>
                <StatusBadge status={r.status} />
              </TD>
              <TD>{formatDate(r.consumedOn)}</TD>
              <TD>
                {canApprove && r.status === 'REQUESTED' && r.requestedBy !== me?.loginId && (
                  <div className="flex gap-1">
                    <Button
                      size="sm"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ id: r.id, approve: true })}
                    >
                      승인
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ id: r.id, approve: false })}
                    >
                      반려
                    </Button>
                  </div>
                )}
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </div>
  )
}
