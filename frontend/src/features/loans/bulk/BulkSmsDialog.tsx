import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '@/api/client'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter } from '@/components/ui/dialog'
import { Select, Textarea } from '@/components/ui/input'
import { sliceApi, sliceKeys } from '@/features/communication/api'
import type { BulkActionProps } from './bulkActions'

export interface BulkSmsItem {
  accountId: number
  accountNo: string | null
  result: 'SENT' | 'SCHEDULED' | 'BLOCKED' | 'FAILED' | 'SKIPPED'
  reason: string | null
}

export interface BulkSmsResult {
  sent: number
  scheduled: number
  blocked: number
  skipped: number
  items: BulkSmsItem[]
}

const RESULT_LABEL: Record<BulkSmsItem['result'], string> = {
  SENT: '발송',
  SCHEDULED: '예약',
  BLOCKED: '차단',
  FAILED: '실패',
  SKIPPED: '제외',
}

/** 계약 상세 우측 타임라인의 문자 발송과 같은 한도 (서버 CommunicationService.SMS_MAX) */
const SMS_MAX = 500

/**
 * 문자 일괄 발송 (ADR 0011). 계약 상세의 문자 발송을 선택한 계약마다 한 번씩 하는 것과 같다:
 * 템플릿의 {{고객명}} 같은 치환은 계약마다, 7일 7회·연락 유예·추심 중지·야간 예약도 계약마다 서버가 판단한다.
 */
export function BulkSmsDialog({ selected, onClose, onDone }: BulkActionProps) {
  const templates = useQuery({
    queryKey: sliceKeys.templates(true),
    queryFn: () => sliceApi.templates(true),
    select: (all) => all.filter((t) => t.channel === 'SMS'),
  })
  const [templateId, setTemplateId] = useState('')
  const [text, setText] = useState('')
  const template = templates.data?.find((t) => String(t.id) === templateId)
  const send = useMutation({
    mutationFn: () =>
      api.post<BulkSmsResult>('/api/dispatches/bulk-sms', {
        accountIds: selected.map((s) => s.id),
        templateId: template ? template.id : null,
        body: template ? null : text.trim(),
      }),
  })
  const result = send.data
  const ready = (!!template || text.trim().length > 0) && text.length <= SMS_MAX && !send.isPending

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        className="max-w-xl"
        title="문자 일괄 발송"
        description={`선택한 계약 ${selected.length}건의 고객에게 문자를 보냅니다. 계약마다 7일 7회·연락 유예·추심 중지 규칙이 따로 적용되어 막힌 계약은 보내지 않습니다.`}
      >
        {result ? (
          <div className="space-y-2 text-xs">
            <Alert tone={result.sent + result.scheduled > 0 ? 'success' : 'warn'}>
              발송 {result.sent}건 · 예약 {result.scheduled}건 · 차단 {result.blocked}건 · 제외 {result.skipped}건
            </Alert>
            <ul className="max-h-60 space-y-0.5 overflow-y-auto rounded border p-2" aria-label="계약별 결과">
              {result.items.map((i) => (
                <li key={i.accountId} className="flex gap-2">
                  <span className="w-24 shrink-0 font-mono">{i.accountNo ?? `#${i.accountId}`}</span>
                  <span className="w-10 shrink-0 font-semibold">{RESULT_LABEL[i.result]}</span>
                  <span className="text-muted-foreground">{i.reason ?? ''}</span>
                </li>
              ))}
            </ul>
            <p className="text-muted-foreground">보낸 문자와 차단 기록은 각 계약의 연락 타임라인에 남습니다.</p>
          </div>
        ) : (
          <div className="space-y-3 text-xs">
            <label className="block space-y-1">
              <span className="block text-muted-foreground">템플릿</span>
              <Select
                aria-label="템플릿"
                className="w-full"
                value={templateId}
                onChange={(e) => setTemplateId(e.target.value)}
              >
                <option value="">직접 입력</option>
                {templates.data?.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </Select>
            </label>
            {template ? (
              <div className="space-y-1">
                <span className="block text-muted-foreground">내용 (치환 문자열은 계약마다 채워짐)</span>
                <p className="whitespace-pre-wrap rounded border bg-muted/30 p-2">{template.body}</p>
              </div>
            ) : (
              <label className="block space-y-1">
                <span className="flex justify-between text-muted-foreground">
                  <span>내용 (추심 안내로 발송, {'{{고객명}}'} 같은 치환 문자열 사용 가능)</span>
                  <span className={text.length > SMS_MAX ? 'text-destructive' : undefined}>
                    {text.length}/{SMS_MAX}
                  </span>
                </span>
                <Textarea aria-label="문자 내용" className="min-h-28" value={text} onChange={(e) => setText(e.target.value)} />
              </label>
            )}
            <ErrorMessage error={templates.error ?? send.error} />
          </div>
        )}
        <DialogFooter>
          {result ? (
            <Button onClick={onDone}>닫고 선택 비우기</Button>
          ) : (
            <>
              <Button variant="outline" onClick={onClose}>
                취소
              </Button>
              <Button
                disabled={!ready}
                onClick={() => {
                  if (window.confirm(`${selected.length}건에 문자를 보냅니다. 보낸 뒤에는 취소할 수 없습니다.`)) send.mutate()
                }}
              >
                {selected.length}건 발송
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
