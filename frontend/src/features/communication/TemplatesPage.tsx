import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import { ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select, Textarea } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { type MessageTemplate, sliceApi, sliceKeys, sliceLabel } from './api'
import { PLACEHOLDERS, templateSchema } from './schemas'

type FormIn = z.input<typeof templateSchema>
type FormOut = z.output<typeof templateSchema>

const EMPTY: FormIn = { code: '', name: '', channel: 'SMS', purpose: 'COLLECTION', title: '', body: '', active: true }

/** 문자·팩스·우편 템플릿 관리 (guide 13.3 관리 > 문자 템플릿). 목적은 템플릿이 정하고 발송자가 바꿀 수 없다 (R59) */
export function TemplatesPage() {
  const client = useQueryClient()
  const q = useQuery({ queryKey: sliceKeys.templates(false), queryFn: () => sliceApi.templates(false) })
  const [editing, setEditing] = useState<MessageTemplate | null>(null)
  const form = useForm<FormIn, unknown, FormOut>({ resolver: zodResolver(templateSchema), defaultValues: EMPTY })
  const save = useMutation({
    mutationFn: (v: FormOut) => (editing ? sliceApi.updateTemplate(editing.id, v) : sliceApi.createTemplate(v)),
    onSuccess: async () => {
      setEditing(null)
      form.reset(EMPTY)
      await client.invalidateQueries({ queryKey: ['message-templates'] })
    },
  })
  const errors = form.formState.errors
  const channel = form.watch('channel')

  function edit(t: MessageTemplate) {
    setEditing(t)
    form.reset({
      code: t.code,
      name: t.name,
      channel: t.channel,
      purpose: t.purpose,
      title: t.title ?? '',
      body: t.body,
      active: t.active,
    })
  }

  return (
    <div className="space-y-3">
      <Card>
        <CardHeader>
          <CardTitle>{editing ? `템플릿 수정 — ${editing.code}` : '새 템플릿'}</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="grid gap-2 md:grid-cols-4" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
            <Field label="코드" htmlFor="tCode" error={errors.code?.message}>
              <Input id="tCode" disabled={!!editing} {...form.register('code')} />
            </Field>
            <Field label="이름" htmlFor="tName" error={errors.name?.message}>
              <Input id="tName" {...form.register('name')} />
            </Field>
            <Field label="발송 수단" htmlFor="tChannel">
              <Select id="tChannel" {...form.register('channel')}>
                <option value="SMS">문자</option>
                <option value="FAX">팩스</option>
                <option value="POST">우편</option>
              </Select>
            </Field>
            <Field label="목적" htmlFor="tPurpose">
              <Select id="tPurpose" {...form.register('purpose')}>
                <option value="COLLECTION">추심 안내 (7일 7회·연락 유예 적용)</option>
                <option value="NOTICE">법정·계약상 통지 (한도 제외)</option>
              </Select>
            </Field>
            {channel !== 'SMS' && (
              <div className="md:col-span-4">
                <Field label="제목" htmlFor="tTitle" error={errors.title?.message}>
                  <Input id="tTitle" {...form.register('title')} />
                </Field>
              </div>
            )}
            <div className="md:col-span-4">
              <Field label={`내용 — 치환자: ${PLACEHOLDERS.map((p) => `{${p}}`).join(' ')}`} htmlFor="tBody" error={errors.body?.message}>
                <Textarea id="tBody" rows={4} {...form.register('body')} />
              </Field>
            </div>
            <label className="flex items-center gap-1 text-xs">
              <input type="checkbox" {...form.register('active')} /> 사용
            </label>
            <div className="flex justify-end gap-2 md:col-span-3">
              {editing && (
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    setEditing(null)
                    form.reset(EMPTY)
                  }}
                >
                  취소
                </Button>
              )}
              <Button type="submit" disabled={save.isPending}>
                {editing ? '저장' : '추가'}
              </Button>
            </div>
            <div className="md:col-span-4">
              <ErrorMessage error={save.error} />
            </div>
          </form>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>템플릿 목록</CardTitle>
        </CardHeader>
        <CardContent>
          <ErrorMessage error={q.error} />
          <Table>
            <THead>
              <TR>
                <TH>코드</TH>
                <TH>이름</TH>
                <TH>수단</TH>
                <TH>목적</TH>
                <TH>내용</TH>
                <TH>상태</TH>
                <TH />
              </TR>
            </THead>
            <TBody>
              {q.data?.length === 0 && <EmptyRow colSpan={7} />}
              {q.data?.map((t) => (
                <TR key={t.id}>
                  <TD className="font-mono">{t.code}</TD>
                  <TD>{t.name}</TD>
                  <TD>{sliceLabel(t.channel)}</TD>
                  <TD>{sliceLabel(t.purpose)}</TD>
                  <TD className="max-w-md truncate" title={t.body}>
                    {t.title ? `[${t.title}] ` : ''}
                    {t.body}
                  </TD>
                  <TD>
                    <Badge className={t.active ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-muted'}>
                      {t.active ? '사용' : '중지'}
                    </Badge>
                  </TD>
                  <TD>
                    <Button size="sm" variant="outline" onClick={() => edit(t)}>
                      수정
                    </Button>
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
