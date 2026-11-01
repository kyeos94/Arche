import { useState } from 'react'
import type { LoanListItem } from '@/api/types'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/auth'
import { bulkActions } from './bulkActions'

/** 계약 목록 위 일괄 작업 바: 선택 건수와, 권한이 있는 일괄 작업 버튼 */
export function BulkActionBar({ selected, onClear }: { selected: LoanListItem[]; onClear: () => void }) {
  const { can } = useAuth()
  const [open, setOpen] = useState<string | null>(null)
  const actions = bulkActions.filter((a) => a.permissions.every((p) => can(p)))
  if (actions.length === 0) return null
  const active = actions.find((a) => a.key === open)
  return (
    <div
      className="flex flex-wrap items-center gap-2 rounded border bg-muted/50 px-3 py-1.5 text-xs"
      role="toolbar"
      aria-label="일괄 작업"
    >
      <span>
        선택 <b>{selected.length}</b>건
      </span>
      {selected.length > 0 && (
        <Button size="sm" variant="ghost" onClick={onClear}>
          선택 비우기
        </Button>
      )}
      <span className="mx-1 h-4 border-l" />
      {actions.map((a) => (
        <Button
          key={a.key}
          size="sm"
          variant="outline"
          disabled={selected.length === 0 || selected.length > a.max}
          title={selected.length > a.max ? `한 번에 ${a.max}건까지 할 수 있습니다` : undefined}
          onClick={() => setOpen(a.key)}
        >
          {a.label}
        </Button>
      ))}
      {active && (
        <active.Dialog
          selected={selected}
          onClose={() => setOpen(null)}
          onDone={() => {
            setOpen(null)
            onClear()
          }}
        />
      )}
    </div>
  )
}
