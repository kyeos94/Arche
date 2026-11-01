import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { type Application, type ApplicationDocument, type Review, origination, originationKeys } from '@/api/origination'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatDateTime, formatMoney, formatRate } from '@/lib/format'
import { DocumentStatusBadge, ScreeningTable } from './components'
import { APPLICATION_STATUS_LABEL, CHANNEL_LABEL, LANDING_STATUS_LABEL, VIA_LABEL, formatBytes } from './labels'

type Done = () => Promise<void>

export function ScreeningTab({ review, onDone }: { review: Review; onDone: Done }) {
  const id = review.application.id
  const [evidence, setEvidence] = useState('')
  const open = ['RECEIVED', 'PRE_SCREENING', 'ASSIGNED', 'UNDERWRITING'].includes(review.application.status)
  const consent = useMutation({
    mutationFn: () => origination.recordConsent(id, evidence),
    onSuccess: async () => {
      setEvidence('')
      await onDone()
    },
  })
  const withdraw = useMutation({ mutationFn: (cid: number) => origination.withdrawConsent(id, cid), onSuccess: onDone })
  const inquire = useMutation({ mutationFn: () => origination.inquireCredit(id), onSuccess: onDone })
  return (
    <div className="space-y-3">
      <Card>
        <CardHeader>
          <CardTitle>자동 거절 규칙 (사전심사, R53)</CardTitle>
        </CardHeader>
        <CardContent>
          <ScreeningTable rows={review.screening} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>신용조회</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <Table aria-label="신용조회 결과">
            <THead>
              <TR>
                <TH>조회일</TH>
                <TH>등급</TH>
                <TH>현재 연체</TH>
                <TH className="text-right">보유 대출</TH>
                <TH className="text-right">잔액</TH>
                <TH>조회 번호</TH>
              </TR>
            </THead>
            <TBody>
              {review.creditInquiries.length === 0 && <EmptyRow colSpan={6}>조회 기록이 없습니다</EmptyRow>}
              {review.creditInquiries.map((c) => (
                <TR key={c.id}>
                  <TD>{formatDate(c.inquiredOn)}</TD>
                  <TD>{c.grade}등급</TD>
                  <TD>{c.currentlyDelinquent ? <Badge className="bg-red-50 text-red-700 border-red-200">있음</Badge> : '없음'}</TD>
                  <TD className="num">{c.openLoanCount}건</TD>
                  <TD className="num">{formatMoney(c.totalDebt)}</TD>
                  <TD className="text-muted-foreground">{c.inquiryRef}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
          {open && (
            <Button size="sm" variant="outline" disabled={!review.creditConsentValid || inquire.isPending} onClick={() => inquire.mutate()}>
              신용조회 (수동, 자동 거절하지 않음)
            </Button>
          )}
          <ErrorMessage error={inquire.error} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>신용조회 동의 (guide 11.2)</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <Table aria-label="동의 이력">
            <THead>
              <TR>
                <TH>채널</TH>
                <TH>동의일</TH>
                <TH>유효기간</TH>
                <TH>증빙</TH>
                <TH>상태</TH>
                <TH />
              </TR>
            </THead>
            <TBody>
              {review.consents.length === 0 && <EmptyRow colSpan={6}>동의 이력이 없습니다</EmptyRow>}
              {review.consents.map((c) => (
                <TR key={c.id}>
                  <TD>{CHANNEL_LABEL[c.channel] ?? c.channel}</TD>
                  <TD>{formatDate(c.agreedOn)}</TD>
                  <TD>~ {formatDate(c.validUntil)}</TD>
                  <TD className="text-muted-foreground">{c.evidence ?? '-'}</TD>
                  <TD>{c.withdrawnAt ? '철회' : c.valid ? '유효' : '만료'}</TD>
                  <TD>
                    {!c.withdrawnAt && c.valid && (
                      <Button size="sm" variant="ghost" onClick={() => withdraw.mutate(c.id)}>
                        철회
                      </Button>
                    )}
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
          {open && (
            <div className="flex items-end gap-2">
              <Input
                aria-label="동의 증빙"
                placeholder="서면 동의서 번호·녹취 번호"
                value={evidence}
                onChange={(e) => setEvidence(e.target.value)}
                className="max-w-xs"
              />
              <Button size="sm" variant="outline" disabled={!evidence.trim() || consent.isPending} onClick={() => consent.mutate()}>
                동의 기록
              </Button>
            </div>
          )}
          <ErrorMessage error={consent.error ?? withdraw.error} />
        </CardContent>
      </Card>
    </div>
  )
}

function DocumentRow({ appId, doc, editable, onDone }: { appId: number; doc: ApplicationDocument; editable: boolean; onDone: Done }) {
  const [note, setNote] = useState('')
  const upload = useMutation({ mutationFn: (f: File) => origination.uploadDocument(appId, doc.id, f), onSuccess: onDone })
  const paper = useMutation({ mutationFn: () => origination.receivePaper(appId, doc.id, note || '원본 대조'), onSuccess: onDone })
  const verify = useMutation({ mutationFn: () => origination.verifyDocument(appId, doc.id), onSuccess: onDone })
  const reject = useMutation({
    mutationFn: () => origination.rejectDocument(appId, doc.id, note),
    onSuccess: async () => {
      setNote('')
      await onDone()
    },
  })
  const error = upload.error ?? paper.error ?? verify.error ?? reject.error
  return (
    <TR>
      <TD>
        {doc.label}
        {doc.required ? <span className="text-destructive"> *</span> : <span className="text-muted-foreground"> (선택)</span>}
      </TD>
      <TD>
        <DocumentStatusBadge status={doc.status} />
      </TD>
      <TD className="text-muted-foreground">
        {doc.receivedVia ? VIA_LABEL[doc.receivedVia] : '-'}
        {doc.fileName && (
          <>
            {' · '}
            <a className="text-primary hover:underline" href={origination.documentFileUrl(appId, doc.id)}>
              {doc.fileName}
            </a>{' '}
            ({formatBytes(doc.sizeBytes)})
          </>
        )}
      </TD>
      <TD className="text-muted-foreground">
        {doc.reviewedBy ? `${doc.reviewedBy} ${formatDateTime(doc.reviewedAt)}` : '-'}
        {doc.note && <div>{doc.note}</div>}
      </TD>
      <TD>
        {editable && doc.status !== 'VERIFIED' && (
          <div className="flex flex-wrap items-center gap-1">
            {doc.status !== 'RECEIVED' && (
              <>
                <label className="cursor-pointer rounded border px-2 py-1 text-[11px] hover:bg-muted">
                  파일
                  <input
                    type="file"
                    accept="application/pdf,image/jpeg,image/png"
                    className="sr-only"
                    aria-label={`${doc.label} 파일 업로드`}
                    onChange={(e) => e.target.files?.[0] && upload.mutate(e.target.files[0])}
                  />
                </label>
                <Button size="sm" variant="outline" onClick={() => paper.mutate()}>
                  서면 접수
                </Button>
              </>
            )}
            {doc.status === 'RECEIVED' && (
              <>
                <Button size="sm" onClick={() => verify.mutate()}>
                  확인
                </Button>
                <Input aria-label="보완 사유" placeholder="보완 사유" className="h-7 w-28" value={note} onChange={(e) => setNote(e.target.value)} />
                <Button size="sm" variant="outline" disabled={!note.trim()} onClick={() => reject.mutate()}>
                  보완 요청
                </Button>
              </>
            )}
          </div>
        )}
        {error ? <div className="mt-1"><ErrorMessage error={error} /></div> : null}
      </TD>
    </TR>
  )
}

export function DocumentsTab({ review, onDone }: { review: Review; onDone: Done }) {
  const editable = ['RECEIVED', 'PRE_SCREENING', 'ASSIGNED', 'UNDERWRITING'].includes(review.application.status)
  return (
    <div className="space-y-2">
      <p className="text-[11px] text-muted-foreground">
        * 필수 서류는 모두 ‘확인’이어야 승인할 수 있습니다 (R54). 파일은 PDF·JPG·PNG 10MB 이하, 열람은 접근 기록에 남습니다.
      </p>
      <Table aria-label="서류 징구">
        <THead>
          <TR>
            <TH>서류</TH>
            <TH>상태</TH>
            <TH>접수</TH>
            <TH>확인·보완</TH>
            <TH />
          </TR>
        </THead>
        <TBody>
          {review.documents.length === 0 && <EmptyRow colSpan={5} />}
          {review.documents.map((d) => (
            <DocumentRow key={d.id} appId={review.application.id} doc={d} editable={editable} onDone={onDone} />
          ))}
        </TBody>
      </Table>
    </div>
  )
}

export function ChannelTab({ app, onDone }: { app: Application; onDone: Done }) {
  const { can } = useAuth()
  const q = useQuery({ queryKey: originationKeys.channel(app.id), queryFn: () => origination.channel(app.id) })
  const [issued, setIssued] = useState<string | null>(null)
  const [misReason, setMisReason] = useState('')
  const issue = useMutation({
    mutationFn: () => origination.issueLanding(app.id),
    onSuccess: async (r) => {
      setIssued(r.path)
      await onDone()
    },
  })
  const resend = useMutation({ mutationFn: () => origination.resendPlatformNotice(app.id), onSuccess: onDone })
  const misSelling = useMutation({
    mutationFn: () => origination.misSelling(app.id, misReason),
    onSuccess: async () => {
      setMisReason('')
      await onDone()
    },
  })
  const c = q.data
  const open = ['RECEIVED', 'PRE_SCREENING', 'ASSIGNED', 'UNDERWRITING'].includes(app.status)
  return (
    <div className="space-y-3">
      <ErrorMessage error={q.error} />
      <Card>
        <CardHeader>
          <CardTitle>본인인증 링크 (guide 11.3, R58)</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <p className="text-[11px] text-muted-foreground">
            고객 휴대폰으로 30분 유효한 일회용 링크를 보냅니다. 고객은 본인인증 후 신용조회 동의와 서류를 제출합니다.
          </p>
          {open && (
            <Button size="sm" disabled={issue.isPending} onClick={() => issue.mutate()}>
              링크 발급·문자 발송
            </Button>
          )}
          {issued && (
            <Alert tone="success">
              발급했습니다 (데모: <a className="underline" href={issued} target="_blank" rel="noreferrer">고객 화면 열기</a>)
            </Alert>
          )}
          <ErrorMessage error={issue.error} />
          <Table aria-label="랜딩 링크">
            <THead>
              <TR>
                <TH>발급</TH>
                <TH>상태</TH>
                <TH>만료</TH>
                <TH>인증 실패</TH>
                <TH>본인 확인</TH>
                <TH>제출 완료</TH>
              </TR>
            </THead>
            <TBody>
              {c?.landingLinks.length === 0 && <EmptyRow colSpan={6}>발급한 링크가 없습니다</EmptyRow>}
              {c?.landingLinks.map((l) => (
                <TR key={l.id}>
                  <TD>{formatDateTime(l.createdAt)}</TD>
                  <TD>{LANDING_STATUS_LABEL[l.status] ?? l.status}</TD>
                  <TD>{formatDateTime(l.expiresAt)}</TD>
                  <TD className="num">{l.failedAttempts}</TD>
                  <TD>{formatDateTime(l.verifiedAt)}</TD>
                  <TD>{formatDateTime(l.completedAt)}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>

      {c?.broker && (
        <Card>
          <CardHeader>
            <CardTitle>
              중개업자 {c.broker.name} ({c.broker.brokerNo}) · 중개수수료 (R56)
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <Table aria-label="중개수수료">
              <THead>
                <TR>
                  <TH>구분</TH>
                  <TH>일자</TH>
                  <TH className="text-right">기준 금액</TH>
                  <TH className="text-right">수수료율</TH>
                  <TH className="text-right">금액</TH>
                  <TH>사유</TH>
                </TR>
              </THead>
              <TBody>
                {c.commissions.length === 0 && <EmptyRow colSpan={6}>실행 시 정산됩니다</EmptyRow>}
                {c.commissions.map((m) => (
                  <TR key={m.entryType}>
                    <TD>{m.entryType === 'SETTLEMENT' ? '정산' : '환수'}</TD>
                    <TD>{formatDate(m.occurredOn)}</TD>
                    <TD className="num">{formatMoney(m.baseAmount)}</TD>
                    <TD className="num">{formatRate(m.rate)}</TD>
                    <TD className="num">{formatMoney(m.amount)}</TD>
                    <TD className="text-muted-foreground">{m.reason ?? ''}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            {can('broker.manage') &&
              c.commissions.some((m) => m.entryType === 'SETTLEMENT') &&
              !c.commissions.some((m) => m.entryType === 'CLAWBACK') && (
                <div className="flex items-end gap-2">
                  <Input
                    aria-label="불완전판매 확인 내용"
                    placeholder="불완전판매 확인 내용"
                    className="max-w-xs"
                    value={misReason}
                    onChange={(e) => setMisReason(e.target.value)}
                  />
                  <Button size="sm" variant="destructive" disabled={!misReason.trim()} onClick={() => misSelling.mutate()}>
                    불완전판매 환수
                  </Button>
                </div>
              )}
            <ErrorMessage error={misSelling.error} />
          </CardContent>
        </Card>
      )}

      {c?.platformInquiry && (
        <Card>
          <CardHeader>
            <CardTitle>
              대출 비교 플랫폼 {c.platformInquiry.platformCode} · 참조 {c.platformInquiry.platformRef} (R57)
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-xs">
            <p>
              한도조회 {c.platformInquiry.inquiryNo} ({formatDate(c.platformInquiry.inquiredOn)}, ~
              {formatDate(c.platformInquiry.validUntil)}) · 등급 {c.platformInquiry.creditGrade ?? '-'} · 제시 한도{' '}
              {formatMoney(c.platformInquiry.offeredLimit)} · {formatRate(c.platformInquiry.offeredRate)}
            </p>
            <Table aria-label="결과 통보">
              <THead>
                <TR>
                  <TH>결과</TH>
                  <TH>전달</TH>
                  <TH>시도</TH>
                  <TH>마지막 발송</TH>
                  <TH>수신 번호</TH>
                </TR>
              </THead>
              <TBody>
                {c.platformNotices.length === 0 && <EmptyRow colSpan={5}>승인·거절·철회 시 통보됩니다</EmptyRow>}
                {c.platformNotices.map((n) => (
                  <TR key={n.result}>
                    <TD>{APPLICATION_STATUS_LABEL[n.result as keyof typeof APPLICATION_STATUS_LABEL] ?? n.result}</TD>
                    <TD>{n.delivered ? '전달' : '실패'}</TD>
                    <TD className="num">{n.attempts}</TD>
                    <TD>{formatDateTime(n.lastSentAt)}</TD>
                    <TD className="text-muted-foreground">{n.receiptId ?? '-'}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            {c.platformNotices.some((n) => !n.delivered) && (
              <Button size="sm" variant="outline" onClick={() => resend.mutate()}>
                재통보
              </Button>
            )}
            <ErrorMessage error={resend.error} />
          </CardContent>
        </Card>
      )}
    </div>
  )
}

export function HistoryTab({ id }: { id: number }) {
  const q = useQuery({ queryKey: originationKeys.history(id), queryFn: () => origination.history(id) })
  return (
    <Table aria-label="상태 이력">
      <THead>
        <TR>
          <TH>시각</TH>
          <TH>이전</TH>
          <TH>이후</TH>
          <TH>처리자</TH>
          <TH>사유</TH>
        </TR>
      </THead>
      <TBody>
        {q.data?.map((h, i) => (
          <TR key={i}>
            <TD>{formatDateTime(h.changedAt)}</TD>
            <TD>{h.fromStatus ? (APPLICATION_STATUS_LABEL[h.fromStatus as keyof typeof APPLICATION_STATUS_LABEL] ?? h.fromStatus) : '-'}</TD>
            <TD>{APPLICATION_STATUS_LABEL[h.toStatus as keyof typeof APPLICATION_STATUS_LABEL] ?? h.toStatus}</TD>
            <TD>{h.actor}</TD>
            <TD className="text-muted-foreground">{h.reason ?? ''}</TD>
          </TR>
        ))}
      </TBody>
    </Table>
  )
}
