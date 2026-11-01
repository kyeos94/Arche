import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { origination } from '@/api/origination'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input } from '@/components/ui/input'
import { formatDateTime } from '@/lib/format'
import { DocumentStatusBadge } from './components'

/** 고객 화면 공통 틀 (로그인 없음) */
export function PublicShell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-muted/40 px-4 py-8">
      <div className="mx-auto max-w-md space-y-3">
        <p className="text-center text-sm font-bold text-primary">예시대부 (가상)</p>
        <Card>
          <CardHeader>
            <CardTitle>{title}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">{children}</CardContent>
        </Card>
      </div>
    </div>
  )
}

/**
 * 본인인증 기반 랜딩 페이지 (guide 11.3, R58): 문자 링크 → 본인인증 → 신용조회 동의·서류 제출 → 완료.
 */
export function LandingPage() {
  const token = useParams().token ?? ''
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const client = useQueryClient()
  const key = ['landing', token]
  const view = useQuery({ queryKey: key, queryFn: () => origination.landing(token), retry: false })
  const [agree, setAgree] = useState(false)

  const start = useMutation({
    mutationFn: () => origination.landingStartIdentity(token),
    onSuccess: (s) => navigate(s.authUrl),
  })
  const result = useMutation({
    mutationFn: (tx: string) => origination.landingIdentityResult(token, tx),
    onSuccess: (v) => client.setQueryData(key, v),
  })
  const consent = useMutation({
    mutationFn: () => origination.landingConsent(token, agree),
    onSuccess: (v) => client.setQueryData(key, v),
  })
  const upload = useMutation({
    mutationFn: ({ docType, file }: { docType: string; file: File }) => origination.landingUpload(token, docType, file),
    onSuccess: (v) => client.setQueryData(key, v),
  })
  const complete = useMutation({ mutationFn: () => origination.landingComplete(token) })

  // 인증 기관에서 돌아오면(?tx=) 결과를 한 번 대조한다
  const tx = params.get('tx')
  const handled = useRef<string | null>(null)
  useEffect(() => {
    if (tx && handled.current !== tx) {
      handled.current = tx
      result.mutate(tx, { onSettled: () => setParams({}, { replace: true }) })
    }
  }, [tx, result, setParams])

  if (complete.isSuccess) {
    return (
      <PublicShell title="제출 완료">
        <Alert tone="success">제출해 주셔서 감사합니다. 심사 결과는 담당자가 안내해 드립니다.</Alert>
      </PublicShell>
    )
  }
  if (view.error) {
    return (
      <PublicShell title="대출 신청 확인">
        <ErrorMessage error={view.error} />
      </PublicShell>
    )
  }
  const v = view.data
  if (!v) {
    return (
      <PublicShell title="대출 신청 확인">
        <p className="text-xs text-muted-foreground">불러오는 중…</p>
      </PublicShell>
    )
  }
  const verified = v.status === 'VERIFIED'
  return (
    <PublicShell title="대출 신청 확인">
      <p className="text-xs">
        {v.customerName} 님의 신청 {v.applicationNo} ({v.productName})
      </p>
      <p className="text-[11px] text-muted-foreground">이 링크는 {formatDateTime(v.expiresAt)}까지 쓸 수 있습니다.</p>
      <ErrorMessage error={result.error ?? start.error} />
      {!verified ? (
        <div className="space-y-2">
          <p className="text-xs">본인 확인을 위해 본인인증을 진행해 주세요.</p>
          <Button className="w-full" disabled={start.isPending || result.isPending} onClick={() => start.mutate()}>
            본인인증
          </Button>
        </div>
      ) : (
        <div className="space-y-4">
          <Alert tone="success">본인인증이 완료되었습니다.</Alert>
          <section className="space-y-2">
            <h3 className="text-xs font-semibold">1. 신용정보 조회 동의</h3>
            {v.consentGiven ? (
              <p className="text-xs text-emerald-700">동의하셨습니다.</p>
            ) : (
              <>
                <label className="flex items-start gap-2 text-xs">
                  <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
                  대출 심사를 위해 신용정보를 조회하는 데 동의합니다. (동의일부터 1년, 언제든 철회할 수 있습니다)
                </label>
                <Button size="sm" disabled={!agree || consent.isPending} onClick={() => consent.mutate()}>
                  동의
                </Button>
                <ErrorMessage error={consent.error} />
              </>
            )}
          </section>
          <section className="space-y-2">
            <h3 className="text-xs font-semibold">2. 서류 제출 (PDF·JPG·PNG, 10MB 이하)</h3>
            <ul className="space-y-1">
              {v.documents.map((d) => (
                <li key={d.docType} className="flex items-center justify-between gap-2 text-xs">
                  <span>
                    {d.label}
                    {d.required && <span className="text-destructive"> *</span>} <DocumentStatusBadge status={d.status} />
                  </span>
                  {d.status !== 'VERIFIED' && (
                    <Field label="" htmlFor={`f-${d.docType}`}>
                      <Input
                        id={`f-${d.docType}`}
                        aria-label={`${d.label} 제출`}
                        type="file"
                        accept="application/pdf,image/jpeg,image/png"
                        className="h-7 w-44"
                        onChange={(e) => e.target.files?.[0] && upload.mutate({ docType: d.docType, file: e.target.files[0] })}
                      />
                    </Field>
                  )}
                </li>
              ))}
            </ul>
            <ErrorMessage error={upload.error} />
          </section>
          <Button className="w-full" disabled={!v.consentGiven || complete.isPending} onClick={() => complete.mutate()}>
            제출 완료
          </Button>
          <ErrorMessage error={complete.error} />
        </div>
      )}
    </PublicShell>
  )
}

/** Mock 인증 기관 화면: 실제 기관 화면이 아니라 성공·실패를 고르는 가짜 화면이다 (guide 11.1) */
export function MockIdentityPage() {
  const tx = useParams().tx ?? ''
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [birthDate, setBirthDate] = useState('')
  const pending = useQuery({ queryKey: ['mock-identity', tx], queryFn: () => origination.mockIdentity(tx), retry: false })
  const complete = useMutation({
    mutationFn: (success: boolean) => origination.mockIdentityComplete(tx, { success, name, birthDate }),
    onSuccess: (r) => navigate(r.redirectUrl),
  })
  return (
    <PublicShell title="가상 본인인증 (Mock)">
      <Alert tone="info">개발·데모용 가짜 인증 화면입니다. 실제 인증 기관과 연결되지 않습니다.</Alert>
      <ErrorMessage error={pending.error ?? complete.error} />
      {pending.data && (
        <form
          className="space-y-2"
          onSubmit={(e) => {
            e.preventDefault()
            complete.mutate(true)
          }}
        >
          <Field label="이름" htmlFor="miName">
            <Input id="miName" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="생년월일" htmlFor="miBirth">
            <Input id="miBirth" type="date" value={birthDate} onChange={(e) => setBirthDate(e.target.value)} />
          </Field>
          <div className="flex gap-2">
            <Button type="submit" disabled={!name || !birthDate || complete.isPending}>
              인증 성공
            </Button>
            <Button type="button" variant="outline" disabled={complete.isPending} onClick={() => complete.mutate(false)}>
              인증 실패
            </Button>
          </div>
        </form>
      )}
    </PublicShell>
  )
}
