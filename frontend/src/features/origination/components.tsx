import type { ApplicationStatus, ScreeningRow } from '@/api/origination'
import { PagedList } from '@/components/Pagination'
import { Badge } from '@/components/ui/badge'
import { EmptyRow, SortTH, TBody, TD, THead, TR, Table } from '@/components/ui/table'
import { usePaging } from '@/lib/usePaging'
import { useSort } from '@/lib/useSort'
import {
  APPLICATION_STATUS_LABEL,
  APPLICATION_STATUS_TONE,
  DOCUMENT_STATUS_LABEL,
  DOCUMENT_STATUS_TONE,
  OUTCOME_LABEL,
  OUTCOME_TONE,
} from './labels'

export function ApplicationStatusBadge({ status }: { status: ApplicationStatus }) {
  return <Badge className={APPLICATION_STATUS_TONE[status]}>{APPLICATION_STATUS_LABEL[status] ?? status}</Badge>
}

export function DocumentStatusBadge({ status }: { status: string }) {
  return <Badge className={DOCUMENT_STATUS_TONE[status]}>{DOCUMENT_STATUS_LABEL[status] ?? status}</Badge>
}

/** 자동 거절 규칙 판정 결과 (R53) */
export function ScreeningTable({ rows }: { rows: ScreeningRow[] }) {
  const { sorted, header } = useSort(rows, {
    rule: (r) => r.label,
    outcome: (r) => OUTCOME_LABEL[r.outcome] ?? r.outcome,
    detail: (r) => r.detail,
    screenedOn: (r) => r.screenedOn,
  })
  const paging = usePaging(sorted)
  return (
    <PagedList paging={paging.props}>
      <Table aria-label="자동 거절 규칙 결과">
        <THead>
          <TR>
            <SortTH {...header('rule')}>규칙</SortTH>
            <SortTH {...header('outcome')}>결과</SortTH>
            <SortTH {...header('detail')}>근거</SortTH>
            <SortTH {...header('screenedOn')}>판정일</SortTH>
          </TR>
        </THead>
        <TBody>
          {rows.length === 0 && <EmptyRow colSpan={4}>사전심사 전입니다</EmptyRow>}
          {paging.rows.map((r) => (
            <TR key={r.rule}>
              <TD>{r.label}</TD>
              <TD>
                <Badge className={OUTCOME_TONE[r.outcome]}>{OUTCOME_LABEL[r.outcome] ?? r.outcome}</Badge>
              </TD>
              <TD className="text-muted-foreground">{r.detail}</TD>
              <TD>{r.screenedOn}</TD>
            </TR>
          ))}
        </TBody>
      </Table>
    </PagedList>
  )
}
