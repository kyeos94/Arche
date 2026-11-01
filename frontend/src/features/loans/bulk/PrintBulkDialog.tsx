import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { type DocumentPurpose, documentKeys, documents } from '@/api/documents'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter } from '@/components/ui/dialog'
import { Select } from '@/components/ui/input'
import { buildPrintDocument, printHtml } from '@/lib/print'
import type { BulkActionProps } from './bulkActions'

/**
 * 양식 일괄 인쇄 (R72). 먼저 대상 확인(미리보기)으로 몇 건이 인쇄되고 몇 건이 왜 빠지는지 보여 준 뒤 인쇄한다.
 * 대상이 아닌 계약은 서버가 건너뛰고 이유를 돌려준다.
 */
export function PrintBulkDialog({ selected, onClose, onDone }: BulkActionProps) {
  const templates = useQuery({ queryKey: documentKeys.templates, queryFn: documents.templates })
  const [picked, setPicked] = useState('')
  const code = picked || templates.data?.[0]?.code || ''
  const ids = selected.map((s) => s.id)
  const run = useMutation({
    mutationFn: (purpose: DocumentPurpose) => documents.render({ templateCode: code, accountIds: ids, purpose }),
    onSuccess: (result, purpose) => {
      if (purpose === 'PRINT' && result.documents.length > 0) {
        printHtml(buildPrintDocument(result))
      }
    },
  })
  const result = run.data
  const printed = run.variables === 'PRINT' && run.isSuccess
  const checked = run.variables === 'PREVIEW' && run.isSuccess
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        title="양식 일괄 인쇄"
        description={`선택한 계약 ${selected.length}건에 양식을 채워 한 번에 인쇄합니다. 대상이 아닌 계약은 빠집니다.`}
      >
        <div className="space-y-3 text-xs">
          <label className="block space-y-1">
            <span className="block text-muted-foreground">양식</span>
            <Select
              aria-label="양식"
              value={code}
              className="w-full"
              onChange={(e) => {
                setPicked(e.target.value)
                run.reset()
              }}
            >
              {templates.data?.map((t) => (
                <option key={t.code} value={t.code}>
                  {t.name}
                </option>
              ))}
            </Select>
          </label>
          <ErrorMessage error={templates.error ?? run.error} />
          {result && (
            <div className="space-y-2">
              <Alert tone={result.documents.length ? (printed ? 'success' : 'info') : 'warn'}>
                {printed
                  ? `${result.documents.length}건을 인쇄했습니다 (배치 ${result.batchNo}).`
                  : `인쇄 대상 ${result.documents.length}건`}
                {result.skipped.length > 0 && ` · 제외 ${result.skipped.length}건`}
              </Alert>
              {result.skipped.length > 0 && (
                <ul className="max-h-40 space-y-0.5 overflow-y-auto rounded border p-2" aria-label="제외된 계약">
                  {result.skipped.map((s) => (
                    <li key={s.accountId}>
                      <span className="font-mono">{s.accountNo ?? s.accountId}</span> — {s.reason}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
        <DialogFooter>
          {printed ? (
            <Button onClick={onDone}>닫고 선택 비우기</Button>
          ) : (
            <>
              <Button variant="outline" disabled={!code || run.isPending} onClick={() => run.mutate('PREVIEW')}>
                대상 확인
              </Button>
              <Button
                disabled={!code || run.isPending || !checked || result?.documents.length === 0}
                onClick={() => run.mutate('PRINT')}
              >
                인쇄
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
