import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { PagedList } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatDateTime, statusLabel } from '@/lib/format'
import { usePaging } from '@/lib/usePaging'
import { useSort } from '@/lib/useSort'
import { cn } from '@/lib/utils'

/** 추심 알림: 소멸시효 임박·완성(G13), 분할납부 약속 불이행·해제(G19). 일마감에서 만든다 */
export function AlertsPage() {
  const client = useQueryClient()
  const [unreadOnly, setUnreadOnly] = useState(true)
  const q = useQuery({
    queryKey: [...queryKeys.alerts, unreadOnly],
    queryFn: () => endpoints.alerts(unreadOnly),
  })
  const read = useMutation({
    mutationFn: (id: number) => endpoints.readAlert(id),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.alerts }),
  })
  const { sorted, header } = useSort(q.data, {
    alertDate: (a) => a.alertDate,
    type: (a) => statusLabel(a.type),
    message: (a) => a.message,
    readAt: (a) => a.readAt,
  })
  const paging = usePaging(sorted)
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h1 className="text-sm font-semibold">추심 알림</h1>
        <label className="flex items-center gap-2 text-xs">
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => {
              setUnreadOnly(e.target.checked)
              paging.setPage(0)
            }}
          />
          읽지 않은 알림만
        </label>
      </div>
      <ErrorMessage error={q.error ?? read.error} />
      <PagedList paging={paging.props}>
        <Table>
          <THead>
            <TR>
              <SortTH {...header('alertDate')}>알림일</SortTH>
              <SortTH {...header('type')}>유형</SortTH>
              <SortTH {...header('message')}>내용</SortTH>
              <TH>계약</TH>
              <SortTH {...header('readAt')}>읽음</SortTH>
            </TR>
          </THead>
          <TBody>
            {q.data && q.data.length === 0 && <EmptyRow colSpan={5} />}
            {paging.rows.map((a) => (
              <TR key={a.id} className={cn(a.readAt && 'text-muted-foreground')}>
                <TD>{formatDate(a.alertDate)}</TD>
                <TD>{statusLabel(a.type)}</TD>
                <TD>{a.message}</TD>
                <TD>
                  <Link to={`/loans/${a.accountId}`} className="text-primary hover:underline">
                    상세
                  </Link>
                </TD>
                <TD>
                  {a.readAt ? (
                    formatDateTime(a.readAt)
                  ) : (
                    <Button size="sm" variant="outline" onClick={() => read.mutate(a.id)}>
                      읽음
                    </Button>
                  )}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </PagedList>
    </div>
  )
}
