import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { endpoints } from '@/api/endpoints'
import { ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Field, Input } from '@/components/ui/input'
import { type LoginForm, loginSchema } from '@/lib/schemas'
import { useAuth } from './auth'

export function LoginPage() {
  const { setMe } = useAuth()
  const form = useForm<LoginForm>({ resolver: zodResolver(loginSchema), defaultValues: { loginId: '', password: '' } })
  const login = useMutation({
    mutationFn: async (v: LoginForm) => {
      await endpoints.csrf().catch(() => undefined)
      return endpoints.login(v.loginId, v.password)
    },
    onSuccess: async (me) => {
      await endpoints.csrf().catch(() => undefined)
      setMe(me)
    },
  })

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted">
      <Card className="w-80">
        <CardContent className="space-y-3">
          <div>
            <h1 className="text-base font-bold">Arche 여신관리</h1>
            <p className="text-xs text-muted-foreground">예시대부(가상) 직원 로그인</p>
          </div>
          <form className="space-y-3" onSubmit={form.handleSubmit((v) => login.mutate(v))}>
            <Field label="아이디" htmlFor="loginId" error={form.formState.errors.loginId?.message}>
              <Input id="loginId" autoComplete="username" {...form.register('loginId')} />
            </Field>
            <Field label="비밀번호" htmlFor="password" error={form.formState.errors.password?.message}>
              <Input id="password" type="password" autoComplete="current-password" {...form.register('password')} />
            </Field>
            <ErrorMessage error={login.error} />
            <Button type="submit" className="w-full" disabled={login.isPending}>
              로그인
            </Button>
          </form>
          <p className="text-[11px] text-muted-foreground">5회 연속 실패하면 계정이 잠깁니다.</p>
        </CardContent>
      </Card>
    </div>
  )
}
