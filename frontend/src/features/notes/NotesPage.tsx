import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { type InboxItem, type NoteRef, type SentItem, noteKeys, notes, refPath } from '@/api/notes'
import { ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'
import { type ComposeInitial, NoteComposeDialog } from './NoteComposeDialog'

type Box = 'inbox' | 'sent'

/** 쪽지함 (R74): 받은 쪽지·보낸 쪽지 목록과 내용, 쪽지 쓰기·답장 */
export function NotesPage() {
  const [box, setBox] = useState<Box>('inbox')
  const [selected, setSelected] = useState<number | null>(null)
  const [compose, setCompose] = useState<ComposeInitial | null>(null)
  const inbox = useQuery({ queryKey: noteKeys.inbox, queryFn: notes.inbox, refetchInterval: 30_000 })
  const sent = useQuery({ queryKey: noteKeys.sent, queryFn: notes.sent, enabled: box === 'sent' })
  const unread = inbox.data?.filter((n) => !n.readAt).length ?? 0

  return (
    <div className="grid gap-3 lg:grid-cols-[minmax(0,26rem)_minmax(0,1fr)]">
      <Card>
        <CardHeader>
          <CardTitle>쪽지함</CardTitle>
          <Button size="sm" onClick={() => setCompose({})}>
            쪽지 쓰기
          </Button>
        </CardHeader>
        <CardContent>
          <Tabs
            value={box}
            onValueChange={(v) => {
              setBox(v as Box)
              setSelected(null)
            }}
          >
            <TabsList>
              <TabsTrigger value="inbox">받은 쪽지{unread > 0 && ` (${unread})`}</TabsTrigger>
              <TabsTrigger value="sent">보낸 쪽지</TabsTrigger>
            </TabsList>
            <TabsContent value="inbox">
              <ErrorMessage error={inbox.error} />
              <NoteList
                items={inbox.data}
                selected={selected}
                onSelect={setSelected}
                empty="받은 쪽지가 없습니다"
                render={(n: InboxItem) => ({
                  who: n.sender ?? '시스템',
                  unread: !n.readAt,
                  subject: n.subject,
                  at: n.sentAt,
                  ref: n.ref,
                  extra: null,
                })}
              />
            </TabsContent>
            <TabsContent value="sent">
              <ErrorMessage error={sent.error} />
              <NoteList
                items={sent.data}
                selected={selected}
                onSelect={setSelected}
                empty="보낸 쪽지가 없습니다"
                render={(n: SentItem) => ({
                  who: `→ ${n.recipients}`,
                  unread: false,
                  subject: n.subject,
                  at: n.sentAt,
                  ref: n.ref,
                  extra: `읽음 ${n.readCount}/${n.recipientCount}`,
                })}
              />
            </TabsContent>
          </Tabs>
        </CardContent>
      </Card>
      {selected === null ? (
        <Card>
          <CardContent className="py-10 text-center text-xs text-muted-foreground">
            왼쪽에서 쪽지를 고르세요
          </CardContent>
        </Card>
      ) : (
        <NoteView
          key={selected}
          id={selected}
          onReply={setCompose}
          onHidden={() => setSelected(null)}
        />
      )}
      {compose && <NoteComposeDialog initial={compose} onClose={() => setCompose(null)} />}
    </div>
  )
}

interface Row {
  who: string
  unread: boolean
  subject: string
  at: string
  ref: NoteRef | null
  extra: string | null
}

function NoteList<T extends { id: number }>({
  items,
  selected,
  onSelect,
  empty,
  render,
}: {
  items: T[] | undefined
  selected: number | null
  onSelect: (id: number) => void
  empty: string
  render: (item: T) => Row
}) {
  if (items?.length === 0) return <p className="py-6 text-center text-xs text-muted-foreground">{empty}</p>
  return (
    <ul className="divide-y text-xs" aria-label="쪽지 목록">
      {items?.map((item) => {
        const r = render(item)
        return (
          <li key={item.id}>
            <button
              type="button"
              onClick={() => onSelect(item.id)}
              className={cn(
                'w-full px-2 py-1.5 text-left hover:bg-muted',
                selected === item.id && 'bg-muted',
                r.unread && 'font-semibold',
              )}
            >
              <span className="flex justify-between gap-2">
                <span className="truncate">
                  {r.unread && <span className="mr-1 inline-block size-1.5 rounded-full bg-primary align-middle" />}
                  {r.subject}
                </span>
                <span className="shrink-0 font-normal text-muted-foreground">{formatDateTime(r.at)}</span>
              </span>
              <span className="flex justify-between gap-2 font-normal text-muted-foreground">
                <span className="truncate">{r.who}</span>
                <span className="shrink-0">
                  {r.ref && `${r.ref.label} `}
                  {r.extra}
                </span>
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

function NoteView({
  id,
  onReply,
  onHidden,
}: {
  id: number
  onReply: (initial: ComposeInitial) => void
  onHidden: () => void
}) {
  const client = useQueryClient()
  // 여는 순간 읽음 처리되므로 받은 쪽지 목록과 안 읽은 수를 다시 받는다
  const note = useQuery({
    queryKey: [...noteKeys.all, 'detail', id],
    queryFn: async () => {
      const detail = await notes.open(id)
      await client.invalidateQueries({ queryKey: noteKeys.inbox })
      await client.invalidateQueries({ queryKey: noteKeys.unread })
      return detail
    },
    staleTime: Number.POSITIVE_INFINITY,
  })
  const hide = useMutation({
    mutationFn: () => notes.hide(id),
    onSuccess: async () => {
      onHidden()
      await Promise.all(
        [noteKeys.inbox, noteKeys.sent, noteKeys.unread].map((queryKey) => client.invalidateQueries({ queryKey })),
      )
    },
  })
  const n = note.data
  return (
    <Card>
      <CardHeader>
        <CardTitle className="truncate">{n?.subject ?? '쪽지'}</CardTitle>
        <div className="flex gap-1">
          {n && !n.mine && n.senderId !== null && (
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                onReply({
                  staffIds: [n.senderId!],
                  subject: n.subject.startsWith('RE: ') ? n.subject : `RE: ${n.subject}`.slice(0, 100),
                  ref: n.ref,
                })
              }
            >
              답장
            </Button>
          )}
          {n && (
            <Button
              size="sm"
              variant="outline"
              disabled={hide.isPending}
              onClick={() => {
                if (window.confirm('내 쪽지함에서 지웁니다. 상대방 쪽지함에는 남습니다.')) hide.mutate()
              }}
            >
              지우기
            </Button>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-3 text-xs">
        <ErrorMessage error={note.error ?? hide.error} />
        {n && (
          <>
            <dl className="grid grid-cols-[5rem_1fr] gap-y-0.5">
              <dt className="text-muted-foreground">보낸 사람</dt>
              <dd>{n.sender ?? '시스템 알림'}</dd>
              <dt className="text-muted-foreground">받는 사람</dt>
              <dd>{n.recipientSummary}</dd>
              <dt className="text-muted-foreground">보낸 시각</dt>
              <dd>{formatDateTime(n.sentAt)}</dd>
              {n.ref && (
                <>
                  <dt className="text-muted-foreground">연결</dt>
                  <dd>
                    <Link to={refPath(n.ref)} className="text-primary hover:underline">
                      {n.ref.type === 'LOAN' ? '계약' : '신청'} {n.ref.label} 열기 →
                    </Link>
                  </dd>
                </>
              )}
            </dl>
            <p className="whitespace-pre-wrap rounded border bg-muted/30 p-3 text-sm leading-relaxed">{n.body}</p>
            {n.recipients && (
              <div>
                <h3 className="mb-1 font-semibold">
                  읽음 {n.recipients.filter((r) => r.readAt).length}/{n.recipients.length}
                </h3>
                <ul className="grid gap-x-4 gap-y-0.5 sm:grid-cols-2">
                  {n.recipients.map((r, i) => (
                    <li key={i} className="flex justify-between gap-2">
                      <span>
                        {r.name} <span className="text-muted-foreground">{r.team ?? ''}</span>
                      </span>
                      <span className={r.readAt ? 'text-muted-foreground' : 'text-amber-600'}>
                        {r.readAt ? formatDateTime(r.readAt) : '안 읽음'}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
