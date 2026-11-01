import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { endpoints } from '@/api/endpoints'
import type { ContactChannel } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select, Textarea } from '@/components/ui/input'
import { Can, useAuth } from '@/features/auth/auth'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'
import {
  type DispatchChannel,
  type TimelineItem,
  type TimelinePage,
  sliceApi,
  sliceKeys,
  sliceLabel,
  sliceTones,
} from './api'
import { buildSendRequest, type QuickMode } from './quickInput'

const PAGE_SIZE = 20

/**
 * 계약 상세 우측 패널 (guide 13.2): 연락 타임라인(연락 기록·자동 문자·문자/팩스/우편 발송·메모를 시각순으로) + 빠른 입력.
 * 남은 연락 횟수와 연락을 막는 사유(연락 유예·소멸시효)를 위에 보여준다. 판정은 서버가 한다 (R59).
 */
export function TimelinePanel({ loanId }: { loanId: number }) {
  const q = useInfiniteQuery({
    queryKey: sliceKeys.timeline(loanId),
    queryFn: ({ pageParam }) => sliceApi.timeline(loanId, pageParam, PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last: TimelinePage) => (last.hasNext ? last.page + 1 : undefined),
  })
  const first = q.data?.pages[0]
  const items = q.data?.pages.flatMap((p) => p.content) ?? []

  return (
    <Card className="xl:sticky xl:top-2 xl:max-h-[calc(100vh-1rem)] xl:overflow-y-auto" aria-label="연락 타임라인">
      <CardHeader>
        <CardTitle>연락 타임라인</CardTitle>
        <span className="text-xs" data-testid="remaining-today">
          오늘 남은 추심 연락{' '}
          <b className={cn(first?.remainingToday === 0 && 'text-red-700')}>{first?.remainingToday ?? '-'}</b>회 / 7회
        </span>
      </CardHeader>
      <CardContent className="space-y-3">
        {first?.blockingReason && <Alert tone="warn">{first.blockingReason}</Alert>}
        <Can permission="collection.activity">
          <QuickInput loanId={loanId} />
        </Can>
        <ErrorMessage error={q.error} />
        <ol className="space-y-2">
          {items.length === 0 && !q.isPending && <li className="text-xs text-muted-foreground">기록이 없습니다</li>}
          {items.map((i) => (
            <TimelineRow key={`${i.kind}-${i.sourceId}`} item={i} loanId={loanId} />
          ))}
        </ol>
        {q.hasNextPage && (
          <Button
            size="sm"
            variant="outline"
            className="w-full"
            disabled={q.isFetchingNextPage}
            onClick={() => q.fetchNextPage()}
          >
            더 보기 ({items.length} / {first?.totalElements})
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

function TimelineRow({ item, loanId }: { item: TimelineItem; loanId: number }) {
  const client = useQueryClient()
  const track = useMutation({
    mutationFn: () => sliceApi.track(loanId, item.sourceId),
    onSuccess: () => client.invalidateQueries({ queryKey: sliceKeys.timeline(loanId) }),
  })
  const muted = item.status === 'BLOCKED' || item.status === 'SKIPPED'
  const trackable = item.kind === 'DISPATCH' && item.channel === 'POST' && ['ACCEPTED', 'IN_TRANSIT'].includes(item.status ?? '')
  return (
    <li className={cn('rounded-md border px-2 py-1.5 text-xs', muted && 'text-muted-foreground')}>
      <div className="flex flex-wrap items-center gap-1">
        <span className="font-semibold">{channelLabel(item)}</span>
        {item.status && <Badge className={sliceTones[item.status] ?? 'bg-muted'}>{sliceLabel(item.status)}</Badge>}
        <span className="ml-auto text-muted-foreground tabular-nums">{formatDateTime(item.at)}</span>
      </div>
      {item.title && <p className="font-medium">{item.title}</p>}
      {item.summary && <p className="break-words whitespace-pre-wrap">{item.summary}</p>}
      {item.reason && <p className="text-muted-foreground">{item.reason}</p>}
      <div className="flex items-center justify-between text-[11px] text-muted-foreground">
        <span>{item.actor}</span>
        {trackable && (
          <Can permission="collection.activity">
            <Button size="sm" variant="ghost" disabled={track.isPending} onClick={() => track.mutate()}>
              배달 조회
            </Button>
          </Can>
        )}
      </div>
    </li>
  )
}

function channelLabel(i: TimelineItem): string {
  if (i.kind === 'MEMO') return '메모'
  if (i.kind === 'AUTO_MESSAGE') return '자동 문자'
  if (i.kind === 'DISPATCH') return `${sliceLabel(i.channel)} 발송`
  return sliceLabel(i.channel)
}

/** 빠른 입력: 전화·방문 기록, 문자·팩스·우편 발송(템플릿 또는 직접 입력), 메모 */
function QuickInput({ loanId }: { loanId: number }) {
  const client = useQueryClient()
  const { can } = useAuth()
  const [mode, setMode] = useState<QuickMode>('CALL')
  const [templateId, setTemplateId] = useState('')
  const [title, setTitle] = useState('')
  const [text, setText] = useState('')
  const [faxNumber, setFaxNumber] = useState('')
  const [result, setResult] = useState<{ tone: 'success' | 'warn' | 'error'; message: string } | null>(null)
  const channel: DispatchChannel | null = mode === 'SMS' || mode === 'FAX' || mode === 'POST' ? mode : null
  const templates = useQuery({
    queryKey: sliceKeys.templates(true),
    queryFn: () => sliceApi.templates(true),
    enabled: can('loan.read'),
  })
  const options = (templates.data ?? []).filter((t) => t.channel === channel)
  const preview = useQuery({
    queryKey: ['loan', loanId, 'template-preview', templateId],
    queryFn: () => sliceApi.preview(loanId, Number(templateId)),
    enabled: channel !== null && templateId !== '',
  })

  const save = useMutation({
    mutationFn: async () => {
      if (mode === 'MEMO') {
        await sliceApi.memo(loanId, text)
        return { tone: 'success' as const, message: '메모를 남겼습니다' }
      }
      if (mode === 'CALL' || mode === 'VISIT') {
        const d = await endpoints.recordContact(loanId, { channel: mode as ContactChannel, memo: text || undefined })
        return d.result === 'ALLOWED'
          ? { tone: 'success' as const, message: '연락을 기록했습니다' }
          : { tone: 'error' as const, message: `${sliceLabel(d.result)}: ${d.reason}` }
      }
      const d = await sliceApi.send(
        loanId,
        buildSendRequest(channel as DispatchChannel, { templateId, title, body: text, faxNumber }),
      )
      if (d.status === 'BLOCKED') return { tone: 'error' as const, message: `차단: ${d.reason}` }
      if (d.status === 'FAILED') return { tone: 'error' as const, message: d.reason ?? '발송 실패' }
      if (d.status === 'SCHEDULED') return { tone: 'warn' as const, message: `${d.contactDate} 08:00 발송 예약` }
      return { tone: 'success' as const, message: `${sliceLabel(d.status)} · 남은 횟수 ${d.remainingToday}회` }
    },
    onSuccess: async (r) => {
      setResult(r)
      setText('')
      setTitle('')
      await client.invalidateQueries({ queryKey: ['loan', loanId] })
    },
  })

  return (
    <form
      className="space-y-2 rounded-md border bg-muted/30 p-2"
      aria-label="빠른 입력"
      onSubmit={(e) => {
        e.preventDefault()
        setResult(null)
        save.mutate()
      }}
    >
      <div className="flex flex-wrap gap-1" role="radiogroup" aria-label="입력 종류">
        {(['CALL', 'VISIT', 'SMS', 'FAX', 'POST', 'MEMO'] as QuickMode[]).map((m) => (
          <Button
            key={m}
            type="button"
            size="sm"
            role="radio"
            aria-checked={mode === m}
            variant={mode === m ? 'default' : 'outline'}
            onClick={() => {
              setMode(m)
              setTemplateId('')
              setResult(null)
            }}
          >
            {m === 'MEMO' ? '메모' : sliceLabel(m)}
          </Button>
        ))}
      </div>
      {channel && (
        <Field label="템플릿" htmlFor="qTemplate">
          <Select id="qTemplate" value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
            <option value="">직접 입력 (추심 안내로 셈)</option>
            {options.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} · {sliceLabel(t.purpose)}
              </option>
            ))}
          </Select>
        </Field>
      )}
      {channel === 'FAX' && (
        <Field label="받는 팩스 번호" htmlFor="qFax">
          <Input id="qFax" inputMode="tel" value={faxNumber} onChange={(e) => setFaxNumber(e.target.value)} />
        </Field>
      )}
      {channel && templateId !== '' ? (
        <div className="rounded border bg-background p-2 text-xs whitespace-pre-wrap" aria-label="미리보기">
          {preview.data?.title && <p className="font-semibold">{preview.data.title}</p>}
          {preview.data?.body ?? '미리보기를 불러오는 중…'}
          {preview.data?.purpose === 'NOTICE' && (
            <p className="mt-1 text-muted-foreground">법정·계약상 통지: 7일 7회·연락 유예와 무관하게 보냅니다</p>
          )}
        </div>
      ) : (
        <>
          {channel && channel !== 'SMS' && (
            <Field label="제목" htmlFor="qTitle">
              <Input id="qTitle" value={title} onChange={(e) => setTitle(e.target.value)} />
            </Field>
          )}
          <Field label={mode === 'MEMO' ? '메모' : channel ? '내용 ({고객명} 등 치환자 사용 가능)' : '통화·방문 메모'} htmlFor="qText">
            <Textarea id="qText" value={text} maxLength={mode === 'SMS' ? 500 : 1000} onChange={(e) => setText(e.target.value)} />
          </Field>
        </>
      )}
      <Button type="submit" size="sm" className="w-full" disabled={save.isPending}>
        {mode === 'MEMO' ? '메모 남기기' : channel ? `${sliceLabel(channel)} 보내기` : '연락 기록'}
      </Button>
      {result && <Alert tone={result.tone}>{result.message}</Alert>}
      <ErrorMessage error={save.error} />
    </form>
  )
}
