import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { endpoints } from '@/api/endpoints'
import type { SearchHit } from '@/api/types'
import { StatusBadge } from '@/components/ui/badge'
import { formatMoney } from '@/lib/format'

/**
 * 상단 통합 검색: 계약번호·이름·주민등록번호·휴대폰번호. 결과가 하나면 바로 계약 상세로, 여럿이면 목록에서 고른다.
 * 주민등록번호·휴대폰번호는 서버에서 블라인드 인덱스로 일치 검색하며 결과에는 마스킹된 값만 보인다.
 */
export function GlobalSearch() {
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const search = useMutation({
    mutationFn: (query: string) => endpoints.search(query),
    onSuccess: (hits) => {
      if (hits.length === 1) {
        go(hits[0])
      } else {
        setOpen(true)
      }
    },
  })

  function go(hit: SearchHit) {
    setOpen(false)
    setQ('')
    navigate(`/loans/${hit.id}`)
  }

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [])

  return (
    <div ref={box} className="relative">
      <form
        role="search"
        onSubmit={(e) => {
          e.preventDefault()
          if (q.trim()) search.mutate(q.trim())
        }}
      >
        <input
          aria-label="통합 검색"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === 'Escape' && setOpen(false)}
          placeholder="계약번호·이름·주민번호·휴대폰"
          autoComplete="off"
          className="h-7 w-56 rounded-md border border-white/30 bg-white/15 px-2 text-xs text-primary-foreground placeholder:text-primary-foreground/70 focus-visible:bg-background focus-visible:text-foreground focus-visible:outline-none"
        />
      </form>
      {open && (
        <div className="absolute right-0 z-50 mt-1 w-[28rem] rounded-md border bg-background text-foreground shadow-lg">
          {search.error && <p className="p-3 text-xs text-red-700">{(search.error as Error).message}</p>}
          {search.data && search.data.length === 0 && (
            <p className="p-3 text-xs text-muted-foreground">조회 범위 안에서 찾은 계약이 없습니다.</p>
          )}
          <ul className="max-h-80 overflow-y-auto">
            {search.data?.map((h) => (
              <li key={h.id}>
                <button
                  type="button"
                  onClick={() => go(h)}
                  className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-xs hover:bg-muted"
                >
                  <span className="min-w-0">
                    <span className="font-semibold">{h.accountNo}</span> {h.customerName}
                    <span className="ml-2 text-muted-foreground">
                      {h.residentNoMasked} · {h.phoneMasked ?? '-'} · {h.productCode}
                    </span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2">
                    <span className="num">{formatMoney(h.principalBalance)}</span>
                    <StatusBadge status={h.status} />
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {search.data && search.data.length >= 20 && (
            <p className="border-t px-3 py-1.5 text-xs text-muted-foreground">최대 20건까지 보여 줍니다. 더 자세히 입력하세요.</p>
          )}
        </div>
      )}
    </div>
  )
}
