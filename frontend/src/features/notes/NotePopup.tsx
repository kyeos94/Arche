import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { noteKeys, notes } from '@/api/notes'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/auth'
import { formatDateTime } from '@/lib/format'

const storageKey = (staffId: number) => `arche.notes.popupSeen.${staffId}`

function readSeen(staffId: number): number {
  try {
    return Number(window.localStorage.getItem(storageKey(staffId))) || 0
  } catch {
    return 0
  }
}

function writeSeen(staffId: number, id: number) {
  try {
    window.localStorage.setItem(storageKey(staffId), String(id))
  } catch {
    // 저장이 막힌 브라우저: 이번 화면에서만 기억
  }
}

/**
 * 새 쪽지 알림 팝업 (R74): 안 읽은 쪽지 수가 바뀌면 받은 쪽지를 다시 받아,
 * 아직 팝업으로 보여 주지 않은 안 읽은 쪽지가 있으면 오른쪽 아래에 작은 창으로 알린다.
 * 닫거나 열면 그 쪽지까지는 다시 띄우지 않는다 (직원별로 브라우저에 기억). 읽은 쪽지는 저절로 사라진다.
 */
export function NotePopup() {
  const { me } = useAuth()
  const staffId = me?.id ?? 0
  const client = useQueryClient()
  const navigate = useNavigate()
  const unread = useQuery({ queryKey: noteKeys.unread, queryFn: notes.unreadCount, refetchInterval: 30_000 })
  const count = unread.data?.count ?? 0
  const inbox = useQuery({ queryKey: noteKeys.inbox, queryFn: notes.inbox, enabled: count > 0 })
  const [seen, setSeen] = useState(() => ({ staffId, id: readSeen(staffId) }))
  const seenId = seen.staffId === staffId ? seen.id : readSeen(staffId)

  // 안 읽은 수가 바뀌었으면 새 쪽지가 왔을 수 있으니 받은 쪽지를 다시 받는다
  useEffect(() => {
    if (count > 0) void client.invalidateQueries({ queryKey: noteKeys.inbox })
  }, [count, client])

  const fresh = (count > 0 ? (inbox.data ?? []) : []).filter((n) => !n.readAt && n.id > seenId)
  if (!me || fresh.length === 0) return null
  const latest = fresh.reduce((a, b) => (b.id > a.id ? b : a))
  const maxId = latest.id

  const dismiss = () => {
    writeSeen(staffId, maxId)
    setSeen({ staffId, id: maxId })
  }

  return (
    <div
      role="alertdialog"
      aria-label="새 쪽지"
      className="fixed right-4 bottom-4 z-50 w-72 rounded-lg border bg-background p-3 text-xs shadow-lg"
    >
      <div className="mb-1 flex items-center justify-between">
        <span className="font-semibold text-primary">✉ 새 쪽지{fresh.length > 1 && ` ${fresh.length}건`}</span>
        <button type="button" aria-label="새 쪽지 알림 닫기" onClick={dismiss} className="text-muted-foreground hover:text-foreground">
          ✕
        </button>
      </div>
      <p className="truncate font-medium">{latest.subject}</p>
      <p className="mb-2 flex justify-between gap-2 text-muted-foreground">
        <span className="truncate">{latest.sender ?? '시스템 알림'}</span>
        <span className="shrink-0">{formatDateTime(latest.sentAt)}</span>
      </p>
      {fresh.length > 1 && <p className="mb-2 text-muted-foreground">외 {fresh.length - 1}건</p>}
      <div className="flex justify-end gap-1">
        <Button size="sm" variant="outline" onClick={dismiss}>
          나중에
        </Button>
        <Button
          size="sm"
          onClick={() => {
            dismiss()
            navigate(`/notes?open=${latest.id}`)
          }}
        >
          열기
        </Button>
      </div>
    </div>
  )
}
