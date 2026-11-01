import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { documentKeys, documents } from '@/api/documents'
import type { LoanDetail } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatDateTime } from '@/lib/format'
import { buildPrintDocument, printHtml } from '@/lib/print'

/** 계약 상세 "양식인쇄" 탭 (R72): 양식을 골라 미리보고 1건 인쇄 */
export function PrintTab({ loan }: { loan: Pick<LoanDetail, 'id' | 'accountNo'> }) {
  const client = useQueryClient()
  const templates = useQuery({ queryKey: documentKeys.templates, queryFn: documents.templates })
  const [picked, setPicked] = useState('')
  const code = picked || templates.data?.[0]?.code || ''
  const preview = useQuery({
    queryKey: documentKeys.preview(code, loan.id),
    queryFn: () => documents.render({ templateCode: code, accountIds: [loan.id], purpose: 'PREVIEW' }),
    enabled: !!code,
    staleTime: 0,
  })
  const log = useQuery({ queryKey: documentKeys.printLog(loan.id), queryFn: () => documents.printLog(loan.id) })
  const print = useMutation({
    mutationFn: () => documents.render({ templateCode: code, accountIds: [loan.id], purpose: 'PRINT' }),
    onSuccess: async (result) => {
      if (result.documents.length > 0) printHtml(buildPrintDocument(result))
      await client.invalidateQueries({ queryKey: documentKeys.printLog(loan.id) })
    },
  })
  const template = templates.data?.find((t) => t.code === code)
  const skipped = preview.data?.skipped[0]

  return (
    <div className="space-y-3">
      <Card>
        <CardHeader>
          <CardTitle>양식인쇄</CardTitle>
          <div className="flex items-center gap-2">
            <Select
              aria-label="양식"
              value={code}
              onChange={(e) => {
                setPicked(e.target.value)
                print.reset()
              }}
            >
              {templates.data?.map((t) => (
                <option key={t.code} value={t.code}>
                  {t.name}
                </option>
              ))}
            </Select>
            <Button
              size="sm"
              disabled={!preview.data?.documents.length || print.isPending}
              onClick={() => print.mutate()}
            >
              인쇄
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-2 text-xs">
          {template && <p className="text-muted-foreground">{template.description}</p>}
          <p className="text-muted-foreground">
            금액은 업무일자 기준입니다. 인쇄하면 문서번호가 매겨지고 인쇄 이력이 남습니다(미리보기는 남지 않음). 인쇄는
            발송이 아니므로 추심 연락 횟수에 들어가지 않습니다. 실제로 보내면 우편 발송으로 기록하세요. (R72)
          </p>
          <ErrorMessage error={templates.error ?? preview.error ?? print.error} />
          {skipped && <Alert tone="warn">이 계약은 {template?.name} 대상이 아닙니다: {skipped.reason}</Alert>}
          {print.data?.documents[0] && (
            <Alert tone="success">문서번호 {print.data.documents[0].documentNo}로 인쇄했습니다.</Alert>
          )}
          {preview.data && preview.data.documents.length > 0 && (
            <iframe
              title={`${template?.name ?? '양식'} 미리보기`}
              srcDoc={buildPrintDocument(preview.data)}
              className="h-[640px] w-full rounded border bg-white"
            />
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>인쇄 이력</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <THead>
              <TR>
                <TH>일시</TH>
                <TH>업무일자</TH>
                <TH>양식</TH>
                <TH>문서번호</TH>
                <TH>인쇄한 직원</TH>
              </TR>
            </THead>
            <TBody>
              {log.data?.length === 0 && <EmptyRow colSpan={5}>인쇄한 양식이 없습니다</EmptyRow>}
              {log.data?.map((r) => (
                <TR key={r.id}>
                  <TD className="whitespace-nowrap">{formatDateTime(r.printedAt)}</TD>
                  <TD>{formatDate(r.businessDate)}</TD>
                  <TD>{r.templateName}</TD>
                  <TD className="font-mono">{r.documentNo}</TD>
                  <TD>{r.printedBy}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
