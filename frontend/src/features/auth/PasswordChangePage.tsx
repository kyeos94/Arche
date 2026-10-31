import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { endpoints } from '@/api/endpoints'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Field, Input } from '@/components/ui/input'
import { type PasswordChangeForm, passwordChangeSchema } from '@/lib/schemas'
import { useAuth } from './auth'

/** 초기 비밀번호·90일 경과 시 강제되는 비밀번호 변경 화면 (정책서 7장) */
export function PasswordChangePage({ forced = false, onDone }: { forced?: boolean; onDone?: () => void }) {
  const { me, setMe, logout } = useAuth()
  const form = useForm<PasswordChangeForm>({
    resolver: zodResolver(passwordChangeSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirm: '' },
  })
  const change = useMutation({
    mutationFn: (v: PasswordChangeForm) => endpoints.changePassword(v.currentPassword, v.newPassword),
    onSuccess: async () => {
      setMe(await endpoints.me())
      form.reset()
      onDone?.()
    },
  })
  const errors = form.formState.errors

  const body = (
    <form className="space-y-3" onSubmit={form.handleSubmit((v) => change.mutate(v))}>
      {forced && <Alert tone="warn">비밀번호를 변경해야 업무 화면을 사용할 수 있습니다.</Alert>}
      <Field label="현재 비밀번호" htmlFor="currentPassword" error={errors.currentPassword?.message}>
        <Input id="currentPassword" type="password" {...form.register('currentPassword')} />
      </Field>
      <Field label="새 비밀번호 (10자 이상)" htmlFor="newPassword" error={errors.newPassword?.message}>
        <Input id="newPassword" type="password" {...form.register('newPassword')} />
      </Field>
      <Field label="새 비밀번호 확인" htmlFor="confirm" error={errors.confirm?.message}>
        <Input id="confirm" type="password" {...form.register('confirm')} />
      </Field>
      <ErrorMessage error={change.error} />
      {change.isSuccess && <Alert tone="success">비밀번호를 변경했습니다.</Alert>}
      <div className="flex gap-2">
        <Button type="submit" disabled={change.isPending}>
          변경
        </Button>
        {forced && (
          <Button type="button" variant="outline" onClick={() => void logout()}>
            로그아웃
          </Button>
        )}
      </div>
    </form>
  )

  if (!forced) return body
  return (
    <div className="flex min-h-screen items-center justify-center bg-muted">
      <Card className="w-96">
        <CardContent className="space-y-2">
          <h1 className="text-sm font-semibold">{me?.name}님, 비밀번호 변경</h1>
          {body}
        </CardContent>
      </Card>
    </div>
  )
}
