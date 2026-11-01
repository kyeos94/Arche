import { useQuery } from '@tanstack/react-query'
import { NavLink } from 'react-router'
import { noteKeys, notes } from '@/api/notes'

/** 상단 바의 쪽지 버튼: 안 읽은 쪽지 수를 30초마다 확인한다 (R74). 웹소켓 없이 PostgreSQL 조회만으로 충분한 빈도 */
export function NoteBadge() {
  const unread = useQuery({ queryKey: noteKeys.unread, queryFn: notes.unreadCount, refetchInterval: 30_000 })
  const count = unread.data?.count ?? 0
  return (
    <NavLink
      to="/notes"
      className="relative rounded px-2 py-0.5 hover:bg-white/15"
      aria-label={count > 0 ? `쪽지함, 안 읽은 쪽지 ${count}개` : '쪽지함'}
      title="쪽지함"
    >
      쪽지
      {count > 0 && (
        <span className="ml-1 rounded-full bg-amber-400 px-1.5 text-[11px] font-bold text-black tabular-nums">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </NavLink>
  )
}
