import type { ApplicationStatus, ScreeningRow } from '@/api/origination'
import { Badge } from '@/components/ui/badge'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
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
  return (
    <Table aria-label="자동 거절 규칙 결과">
      <THead>
        <TR>
          <TH>규칙</TH>
          <TH>결과</TH>
          <TH>근거</TH>
          <TH>판정일</TH>
        </TR>
      </THead>
      <TBody>
        {rows.length === 0 && <EmptyRow colSpan={4}>사전심사 전입니다</EmptyRow>}
        {rows.map((r) => (
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
  )
}
