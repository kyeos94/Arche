import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { endpoints } from '@/api/endpoints'
import type { Correction, CorrectionType, PaymentRow } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input } from '@/components/ui/input'
import { useInvalidateLoan } from '@/features/loans/hooks'
import { formatDate, formatMoney } from '@/lib/format'
import { type CorrectionForm, correctionSchema } from '@/lib/schemas'

const titles: Record<CorrectionType, { button: string; title: string; description: string }> = {
  CANCEL: {
    button: '입금 삭제',
    title: '입금 취소',
    description: '원장을 지우지 않고 취소 거래와 재계산으로 처리합니다. 취소된 돈은 미확인 입금으로 돌아갑니다 (R26).',
  },
  MOVE: {
    button: '계약 이동',
    title: '다른 계약으로 이동',
    description: '이 계약의 충당을 취소하고 같은 은행 거래일자로 대상 계약에 소급 입금합니다 (R30, G28).',
  },
  AMOUNT_FIX: {
    button: '금액 정정',
    title: '금액 오입력 정정',
    description: '취소 후 올바른 금액으로 같은 기산일에 다시 입력합니다. 실제로 들어오지 않은 금액은 남기지 않습니다 (G26).',
  },
}

export function CorrectionDialog({
  loanId,
  payment,
  type,
  groupParentId,
}: {
  loanId: number
  payment: PaymentRow
  type: CorrectionType
  /** 일괄 배분 하위 입금이면 원 입금 id: 원 입금 단위 전체 취소 (R48) */
  groupParentId?: number
}) {
  const [open, setOpen] = useState(false)
  const [result, setResult] = useState<Correction | null>(null)
  const invalidate = useInvalidateLoan()
  const form = useForm<CorrectionForm>({
    resolver: zodResolver(correctionSchema),
    defaultValues: { type, reason: '', targetAccountNo: '', correctedAmount: '' },
  })
  const save = useMutation({
    mutationFn: (v: CorrectionForm) =>
      endpoints.requestCorrection({
        type: v.type,
        paymentId: groupParentId ?? payment.id,
        targetAccountNo: v.type === 'MOVE' ? v.targetAccountNo?.trim() : undefined,
        correctedAmount:
          v.type === 'AMOUNT_FIX' ? Number((v.correctedAmount ?? '').replace(/[^0-9]/g, '')) : undefined,
        reason: v.reason,
      }),
    onSuccess: async (c) => {
      setResult(c)
      await invalidate(loanId)
      if (c.targetAccountId) await invalidate(c.targetAccountId)
    },
  })
  const t = groupParentId
    ? {
        button: '배분 전체 취소',
        title: '일괄 배분 전체 취소',
        description: `원 입금 #${groupParentId}로 나눈 모든 계약의 충당을 취소하고 원 입금 전액을 미확인 입금으로 돌립니다. 하위 입금만 따로 취소할 수는 없습니다 (R48).`,
      }
    : titles[type]
  const errors = form.formState.errors

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (!o) {
          setResult(null)
          save.reset()
          form.reset()
        }
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant={type === 'CANCEL' ? 'destructive' : 'outline'}>
          {t.button}
        </Button>
      </DialogTrigger>
      <DialogContent title={t.title} description={t.description}>
        <p className="mb-3 text-xs">
          입금 #{payment.id} · {formatDate(payment.valueDate)} · {formatMoney(payment.amount)}
          {!payment.cancellableToday && (
            <span className="ml-1 text-amber-700">(마감 후: 시스템관리자 요청 → 채권팀장 승인)</span>
          )}
        </p>
        {result ? (
          <div className="space-y-3">
            {result.status === 'REQUESTED' ? (
              <Alert tone="warn">
                정정번호 {result.correctionNo}을 요청했습니다. 마감 후 정정이라 채권팀장 승인을 기다립니다.
              </Alert>
            ) : (
              <Alert tone="success">정정번호 {result.correctionNo}로 처리했습니다. 재계산 결과를 확인하세요.</Alert>
            )}
            <DialogFooter>
              <Button onClick={() => setOpen(false)}>닫기</Button>
            </DialogFooter>
          </div>
        ) : (
          <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
            {type === 'MOVE' && (
              <Field label="옮길 계약번호" htmlFor="targetAccountNo" error={errors.targetAccountNo?.message}>
                <Input id="targetAccountNo" placeholder="L00001002" {...form.register('targetAccountNo')} />
              </Field>
            )}
            {type === 'AMOUNT_FIX' && (
              <Field label="올바른 금액" htmlFor="correctedAmount" error={errors.correctedAmount?.message}>
                <Input id="correctedAmount" inputMode="numeric" {...form.register('correctedAmount')} />
              </Field>
            )}
            <Field label="사유" htmlFor="correctionReason" error={errors.reason?.message}>
              <Input id="correctionReason" {...form.register('reason')} />
            </Field>
            <ErrorMessage error={save.error} />
            <DialogFooter>
              <Button type="submit" variant={type === 'CANCEL' ? 'destructive' : 'default'} disabled={save.isPending}>
                {payment.cancellableToday ? '처리' : '승인 요청'}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
