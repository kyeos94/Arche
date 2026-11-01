import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { type NoteRef, noteKeys, notes } from '@/api/notes'
import { ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter } from '@/components/ui/dialog'
import { Input, Textarea } from '@/components/ui/input'

export interface ComposeInitial {
  staffIds?: number[]
  subject?: string
  body?: string
  ref?: NoteRef | null
}

/** 주민등록번호 형식 (서버 NoteService.RESIDENT_NO와 같음). 보내기 전에 미리 알려 준다 */
const RESIDENT_NO = /(?<!\d)\d{6}\s*[-–]?\s*[1-8]\d{6}(?!\d)/

/**
 * 쪽지 쓰기 (R74). 받는 사람은 팀 단위나 직원 단위로 고른다. 계약·신청을 붙이면 받은 사람이 눌러 그 화면으로 간다.
 */
export function NoteComposeDialog({
  initial,
  onClose,
  onSent,
}: {
  initial?: ComposeInitial
  onClose: () => void
  onSent?: () => void
}) {
  const client = useQueryClient()
  const dir = useQuery({ queryKey: noteKeys.directory, queryFn: notes.directory, staleTime: 60_000 })
  const [staffIds, setStaffIds] = useState<number[]>(initial?.staffIds ?? [])
  const [teamIds, setTeamIds] = useState<number[]>([])
  const [filter, setFilter] = useState('')
  const [subject, setSubject] = useState(initial?.subject ?? '')
  const [body, setBody] = useState(initial?.body ?? '')
  const [ref, setRef] = useState<NoteRef | null>(initial?.ref ?? null)
  const send = useMutation({
    mutationFn: () => notes.send({ staffIds, teamIds, subject: subject.trim(), body: body.trim(), ref }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: noteKeys.sent })
      onSent?.()
      onClose()
    },
  })

  const toggle = (list: number[], id: number) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id])
  const people = (dir.data?.staff ?? []).filter(
    (s) => !filter.trim() || s.name.includes(filter.trim()) || (s.team ?? '').includes(filter.trim()),
  )
  const sensitive = RESIDENT_NO.test(subject) || RESIDENT_NO.test(body)
  const count = new Set([
    ...staffIds,
    ...(dir.data?.staff ?? []).filter((s) => s.teamId !== null && teamIds.includes(s.teamId)).map((s) => s.id),
  ]).size
  const ready = count > 0 && subject.trim().length > 0 && body.trim().length > 0 && !sensitive

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="쪽지 쓰기" description="받는 사람을 팀이나 직원으로 고르세요. 보낸 뒤에는 고칠 수 없습니다.">
        <div className="space-y-3 text-xs">
          <ErrorMessage error={dir.error ?? send.error} />
          <fieldset className="space-y-1">
            <legend className="text-muted-foreground">팀 전체에게</legend>
            <div className="flex flex-wrap gap-x-3 gap-y-1">
              {dir.data?.teams.map((t) => (
                <label key={t.id} className="flex items-center gap-1">
                  <input
                    type="checkbox"
                    checked={teamIds.includes(t.id)}
                    onChange={() => setTeamIds(toggle(teamIds, t.id))}
                  />
                  {t.name} ({t.members}명)
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset className="space-y-1">
            <legend className="text-muted-foreground">직원에게</legend>
            <Input
              aria-label="직원 찾기"
              placeholder="이름·팀으로 찾기"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <ul className="max-h-36 overflow-y-auto rounded border p-1" aria-label="직원 목록">
              {people.map((s) => (
                <li key={s.id}>
                  <label className="flex items-center gap-1 px-1 py-0.5 hover:bg-muted">
                    <input
                      type="checkbox"
                      checked={staffIds.includes(s.id)}
                      onChange={() => setStaffIds(toggle(staffIds, s.id))}
                    />
                    {s.name}
                    <span className="text-muted-foreground">{s.team ?? '-'}</span>
                  </label>
                </li>
              ))}
              {dir.data && people.length === 0 && <li className="px-1 text-muted-foreground">찾는 직원이 없습니다</li>}
            </ul>
          </fieldset>
          <p className="text-muted-foreground" aria-live="polite">
            받는 사람 <b>{count}</b>명 (나는 빠짐)
          </p>
          {ref && (
            <p className="flex items-center gap-2">
              <span className="rounded border px-1.5 py-0.5">
                {ref.type === 'LOAN' ? '계약' : '신청'} {ref.label} 연결
              </span>
              <Button size="sm" variant="ghost" onClick={() => setRef(null)}>
                연결 빼기
              </Button>
            </p>
          )}
          <label className="block space-y-1">
            <span className="block text-muted-foreground">제목 (100자까지)</span>
            <Input aria-label="제목" maxLength={100} value={subject} onChange={(e) => setSubject(e.target.value)} />
          </label>
          <label className="block space-y-1">
            <span className="block text-muted-foreground">내용 (2,000자까지)</span>
            <Textarea
              aria-label="내용"
              className="min-h-32"
              maxLength={2000}
              value={body}
              onChange={(e) => setBody(e.target.value)}
            />
          </label>
          {sensitive && (
            <p className="text-destructive" role="alert">
              주민등록번호 형식의 숫자가 있습니다. 식별번호는 쪽지로 보내지 말고 계약을 붙여 보내세요.
            </p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            취소
          </Button>
          <Button disabled={!ready || send.isPending} onClick={() => send.mutate()}>
            보내기
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
