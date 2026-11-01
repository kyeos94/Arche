import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { endpoints, queryKeys } from '@/api/endpoints'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { type FilterKey, type FilterValues, KEYWORD_TYPES } from './loanFilter'

/**
 * 계약 목록 상세 검색 (ADR 0011). 입력은 초안으로 들고 있다가 [검색]을 눌러야 URL(=검색 조건)에 반영한다.
 * 부모가 URL 조건이 바뀔 때 key로 다시 그려 초안을 새 조건으로 맞춘다.
 */
export function LoanFilterPanel({
  initial,
  onApply,
}: {
  initial: FilterValues
  onApply: (values: FilterValues) => void
}) {
  const [draft, setDraft] = useState<FilterValues>(initial)
  const [open, setOpen] = useState(() => Object.keys(initial).some((k) => k !== 'keyword' && k !== 'keywordType'))
  const products = useQuery({ queryKey: queryKeys.products, queryFn: endpoints.products, staleTime: 300_000 })
  const assignees = useQuery({ queryKey: queryKeys.loanAssignees, queryFn: endpoints.loanAssignees, staleTime: 60_000 })
  const set = (k: FilterKey, v: string) => setDraft((d) => ({ ...d, [k]: v || undefined }))
  const assigneeValue = draft.unassigned === 'true' ? 'NONE' : (draft.assignedStaffId ?? '')
  const keywordType = draft.keywordType ?? 'ACCOUNT_NO'
  const resident = keywordType === 'RESIDENT_NO'

  return (
    <form
      className="space-y-2 rounded border bg-muted/30 p-2 text-xs"
      aria-label="계약 상세 검색"
      onSubmit={(e) => {
        e.preventDefault()
        onApply({ ...draft, keywordType: draft.keyword ? keywordType : undefined })
      }}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Select
          aria-label="검색 항목"
          value={keywordType}
          onChange={(e) => set('keywordType', e.target.value)}
        >
          {KEYWORD_TYPES.map((t) => (
            <option key={t.code} value={t.code}>
              {t.label}
            </option>
          ))}
        </Select>
        <Input
          aria-label="검색어"
          className="w-56"
          type={resident ? 'password' : 'text'}
          autoComplete="off"
          placeholder={
            resident ? '13자리 (입력값은 기록되지 않음)' : keywordType === 'PHONE' ? '01012345678' : '일부만 입력해도 됩니다'
          }
          value={draft.keyword ?? ''}
          onChange={(e) => set('keyword', e.target.value)}
        />
        <Button type="submit" size="sm">
          검색
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => {
            setDraft({})
            onApply({})
          }}
        >
          초기화
        </Button>
        <Button type="button" size="sm" variant="ghost" aria-expanded={open} onClick={() => setOpen(!open)}>
          상세 조건 {open ? '접기 ▲' : '펼치기 ▼'}
        </Button>
      </div>
      {open && (
        <div className="grid gap-x-4 gap-y-2 sm:grid-cols-2 xl:grid-cols-3">
          <Labeled label="상품">
            <Select
              aria-label="상품"
              className="w-full"
              value={draft.productCode ?? ''}
              onChange={(e) => set('productCode', e.target.value)}
            >
              <option value="">전체</option>
              {products.data?.map((p) => (
                <option key={p.code} value={p.code}>
                  {p.code} · {p.name}
                </option>
              ))}
            </Select>
          </Labeled>
          <Labeled label="담당자">
            <Select
              aria-label="담당자"
              className="w-full"
              value={assigneeValue}
              onChange={(e) => {
                const v = e.target.value
                setDraft((d) => ({
                  ...d,
                  unassigned: v === 'NONE' ? 'true' : undefined,
                  assignedStaffId: v && v !== 'NONE' ? v : undefined,
                }))
              }}
            >
              <option value="">전체</option>
              <option value="NONE">미배정</option>
              {assignees.data?.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} · {a.team ?? '-'}
                </option>
              ))}
            </Select>
          </Labeled>
          <Range label="원금 잔액 (원)" from="principalMin" to="principalMax" draft={draft} set={set} />
          <Range label="완제 금액 (원)" from="payoffMin" to="payoffMax" draft={draft} set={set} />
          <Range label="연체일수 (일)" from="delinquencyMin" to="delinquencyMax" draft={draft} set={set} />
          <Range label="다음 납입일" from="nextDueFrom" to="nextDueTo" draft={draft} set={set} type="date" />
          <Range label="실행일" from="disbursedFrom" to="disbursedTo" draft={draft} set={set} type="date" />
        </div>
      )}
    </form>
  )
}

function Labeled({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block space-y-0.5">
      <span className="block text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}

/** 범위 입력: 한쪽만 채우면 이상/이하 */
function Range({
  label,
  from,
  to,
  draft,
  set,
  type = 'number',
}: {
  label: string
  from: FilterKey
  to: FilterKey
  draft: FilterValues
  set: (k: FilterKey, v: string) => void
  type?: 'number' | 'date'
}) {
  return (
    <fieldset className="space-y-0.5">
      <legend className="text-muted-foreground">{label}</legend>
      <div className="flex items-center gap-1">
        <Input
          aria-label={`${label} 부터`}
          type={type}
          min={type === 'number' ? 0 : undefined}
          className="w-full"
          value={draft[from] ?? ''}
          onChange={(e) => set(from, e.target.value)}
        />
        <span className="text-muted-foreground">~</span>
        <Input
          aria-label={`${label} 까지`}
          type={type}
          min={type === 'number' ? 0 : undefined}
          className="w-full"
          value={draft[to] ?? ''}
          onChange={(e) => set(to, e.target.value)}
        />
      </div>
    </fieldset>
  )
}
