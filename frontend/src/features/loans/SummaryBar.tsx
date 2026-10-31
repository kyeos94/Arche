import type { ReactNode } from 'react'
import type { LoanDetail } from '@/api/types'
import { StatusBadge } from '@/components/ui/badge'
import { formatDate, formatMoney, formatRate } from '@/lib/format'

function Item({ label, value, strong }: { label: string; value: ReactNode; strong?: boolean }) {
  return (
    <div className="flex flex-col">
      <span className="text-[11px] text-muted-foreground">{label}</span>
      <span className={strong ? 'text-sm font-semibold tabular-nums' : 'text-xs tabular-nums'}>{value}</span>
    </div>
  )
}

/** 계약 상세 상단 고정 요약 바 (guide 13.2) */
export function SummaryBar({ loan }: { loan: LoanDetail }) {
  return (
    <section
      aria-label="계약 요약"
      className="sticky top-0 z-10 flex flex-wrap items-center gap-x-6 gap-y-2 rounded-lg border bg-background px-4 py-2 shadow-sm"
    >
      <div className="flex items-center gap-2">
        <span className="text-sm font-bold">{loan.customer.name}</span>
        <span className="text-xs text-muted-foreground">{loan.accountNo}</span>
        <StatusBadge status={loan.status} />
      </div>
      <Item label="상품" value={`${loan.productCode} ${loan.productName}`} />
      <Item label="원금 잔액" value={formatMoney(loan.principalBalance)} strong />
      <Item label="완제 금액" value={formatMoney(loan.payoffAmount)} />
      <Item
        label="연체일수"
        value={
          loan.delinquencyDays > 0 ? (
            <span className="font-semibold text-destructive">{loan.delinquencyDays}일</span>
          ) : (
            '0일'
          )
        }
      />
      <Item label="다음 납입일" value={formatDate(loan.nextDueDate)} />
      <Item label="약정이율 / 연체이율" value={`${formatRate(loan.currentRate)} / ${formatRate(loan.lateRate)}`} />
      <Item label="소멸시효 완성 예정" value={formatDate(loan.limitationCompletionDate)} />
      <Item label="기준일" value={formatDate(loan.businessDate)} />
    </section>
  )
}
