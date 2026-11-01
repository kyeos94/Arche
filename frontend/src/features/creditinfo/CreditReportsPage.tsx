import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { sliceApi, sliceKeys, sliceLabel, sliceTones } from '@/features/communication/api'
import { formatDate, formatDateTime, formatMoney, formatNumber } from '@/lib/format'
import { cn } from '@/lib/utils'

/**
 * 신용정보 보고 파일 이력 (R61): 매일 23:30 생성 → 전송 → 결과 반영. 오류 레코드는 다음 보고일에 다시 보낸다.
 * 레이아웃은 자체 일반형 120바이트 고정길이(MS949)이며 실제 기관 형식이 아니다 (ADR 0008).
 */
export function CreditReportsPage() {
  const client = useQueryClient()
  const q = useQuery({ queryKey: sliceKeys.creditReports, queryFn: sliceApi.creditReports })
  const [baseDate, setBaseDate] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  const refresh = () => client.invalidateQueries({ queryKey: ['credit-reports'] })
  const run = useMutation({
    mutationFn: () => sliceApi.runCreditReport(baseDate || undefined),
    onSuccess: async (f) => {
      setSelected(f.id)
      await refresh()
    },
  })
  const step = useMutation({
    mutationFn: ({ id, kind }: { id: number; kind: 'send' | 'result' }) =>
      kind === 'send' ? sliceApi.sendCreditReport(id) : sliceApi.creditReportResult(id),
    onSuccess: refresh,
  })
  return (
    <div className="space-y-3">
      <Card>
        <CardHeader>
          <CardTitle>신용정보 보고 — 파일 이력</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              run.mutate()
            }}
          >
            <Field label={`기준일 (비우면 ${formatDate(q.data?.defaultBaseDate)})`} htmlFor="crBase">
              <Input id="crBase" type="date" value={baseDate} onChange={(e) => setBaseDate(e.target.value)} />
            </Field>
            <Button type="submit" disabled={run.isPending}>
              생성 → 전송 → 결과 반영
            </Button>
          </form>
          <ErrorMessage error={run.error ?? step.error ?? q.error} />
          <Table>
            <THead>
              <TR>
                <TH>기준일</TH>
                <TH>파일</TH>
                <TH className="text-right">레코드</TH>
                <TH className="text-right">바이트</TH>
                <TH>상태</TH>
                <TH>전송</TH>
                <TH>결과</TH>
                <TH className="text-right">오류</TH>
                <TH />
              </TR>
            </THead>
            <TBody>
              {q.data?.files.length === 0 && <EmptyRow colSpan={9} />}
              {q.data?.files.map((f) => (
                <TR key={f.id} className={cn(selected === f.id && 'bg-muted')}>
                  <TD>{formatDate(f.baseDate)}</TD>
                  <TD className="font-mono">
                    <a className="text-primary hover:underline" href={sliceApi.creditReportFileUrl(f.id)}>
                      {f.fileName}
                    </a>
                  </TD>
                  <TD className="num">{formatNumber(f.recordCount)}</TD>
                  <TD className="num">{formatNumber(f.byteLength)}</TD>
                  <TD>
                    <Badge className={sliceTones[f.status] ?? 'bg-muted'}>{sliceLabel(f.status)}</Badge>
                  </TD>
                  <TD>{formatDateTime(f.sentAt)}</TD>
                  <TD>{formatDateTime(f.resultAt)}</TD>
                  <TD className={cn('num', f.errorCount > 0 && 'text-red-700')}>{f.errorCount}</TD>
                  <TD>
                    <div className="flex gap-1">
                      {f.status === 'GENERATED' && (
                        <Button size="sm" variant="outline" onClick={() => step.mutate({ id: f.id, kind: 'send' })}>
                          전송
                        </Button>
                      )}
                      {f.status === 'SENT' && (
                        <Button size="sm" variant="outline" onClick={() => step.mutate({ id: f.id, kind: 'result' })}>
                          결과 반영
                        </Button>
                      )}
                      <Button size="sm" variant="ghost" onClick={() => setSelected(f.id)}>
                        레코드
                      </Button>
                    </div>
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
      {selected !== null && <RecordsCard id={selected} />}
    </div>
  )
}

function RecordsCard({ id }: { id: number }) {
  const q = useQuery({ queryKey: sliceKeys.creditReport(id), queryFn: () => sliceApi.creditReport(id) })
  return (
    <Card>
      <CardHeader>
        <CardTitle>{q.data?.file.fileName ?? '레코드'}</CardTitle>
      </CardHeader>
      <CardContent>
        <ErrorMessage error={q.error} />
        <Table>
          <THead>
            <TR>
              <TH className="text-right">일련</TH>
              <TH>계약</TH>
              <TH>사유</TH>
              <TH>사유일</TH>
              <TH className="text-right">잔액</TH>
              <TH className="text-right">연체일수</TH>
              <TH>결과</TH>
            </TR>
          </THead>
          <TBody>
            {q.data?.records.length === 0 && <EmptyRow colSpan={7}>보고할 변경이 없었습니다</EmptyRow>}
            {q.data?.records.map((r) => (
              <TR key={r.seq} className={cn(r.resultCode && r.resultCode !== '0000' && 'text-red-700')}>
                <TD className="num">{r.seq}</TD>
                <TD>
                  <Link className="text-primary hover:underline" to={`/loans/${r.accountId}`}>
                    #{r.accountId}
                  </Link>
                </TD>
                <TD>{sliceLabel(r.recordType)}</TD>
                <TD>{formatDate(r.eventDate)}</TD>
                <TD className="num">{formatMoney(r.balance)}</TD>
                <TD className="num">{r.delinquencyDays}</TD>
                <TD>
                  {r.resultCode ?? '-'} {r.resultMessage}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}
