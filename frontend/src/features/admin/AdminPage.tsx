import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { endpoints, queryKeys } from '@/api/endpoints'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatDateTime, formatMoney, formatNumber } from '@/lib/format'

function Closing() {
  const client = useQueryClient()
  const bd = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })
  const closings = useQuery({ queryKey: queryKeys.closings, queryFn: endpoints.closings })
  const close = useMutation({
    mutationFn: endpoints.close,
    onSuccess: async () => {
      await client.invalidateQueries()
    },
  })
  const dispatch = useMutation({ mutationFn: endpoints.dispatchMessages })
  return (
    <Card>
      <CardHeader>
        <CardTitle>일마감 (업무일자 {formatDate(bd.data?.businessDate)})</CardTitle>
        <Button
          variant="destructive"
          disabled={close.isPending}
          onClick={() => {
            if (window.confirm(`${bd.data?.businessDate} 업무를 마감하고 다음 날로 넘깁니다.`)) close.mutate()
          }}
        >
          일마감 실행
        </Button>
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-xs text-muted-foreground">
          순서: ① 당일 입금 충당 → ② 약정일 가수금 충당 → ③ 연체 판정 → ④ 기한이익상실 판정 → ⑤ 잔액 대사 → ⑥ 업무일자
          변경 (R24)
        </p>
        <ErrorMessage error={close.error ?? dispatch.error} />
        <div className="flex items-center gap-2">
          <Button size="sm" variant="outline" disabled={dispatch.isPending} onClick={() => dispatch.mutate()}>
            자동 문자 지금 발송
          </Button>
          <span className="text-xs text-muted-foreground">
            평소에는 08:00~21:00에 5분마다 자동 발송합니다.
            {dispatch.data && ` 방금: 발송 ${dispatch.data.sent}건, 건너뜀 ${dispatch.data.skipped}건`}
          </span>
        </div>
        {close.data && (
          <Alert tone="success">
            {close.data.businessDate} 마감 완료 → 업무일자 {formatDate(close.data.nextBusinessDate)} · 판정{' '}
            {close.data.accountsJudged}건 · 새 통지 {close.data.newNotices} · 상실 {close.data.accelerations} · 대사 불일치{' '}
            {close.data.mismatches}
          </Alert>
        )}
        <Table>
          <THead>
            <TR>
              <TH>업무일자</TH>
              <TH>마감 시각</TH>
              <TH className="text-right">입금 건수</TH>
              <TH className="text-right">입금 합계</TH>
              <TH className="text-right">정정</TH>
              <TH className="text-right">미확인 잔액</TH>
              <TH className="text-right">판정</TH>
              <TH className="text-right">새 통지</TH>
              <TH className="text-right">상실</TH>
              <TH className="text-right">대사 불일치</TH>
            </TR>
          </THead>
          <TBody>
            {closings.data?.length === 0 && <EmptyRow colSpan={10} />}
            {closings.data?.map((c) => (
              <TR key={c.businessDate}>
                <TD>{formatDate(c.businessDate)}</TD>
                <TD>{formatDateTime(c.closedAt)}</TD>
                <TD className="num">{formatNumber(c.paymentCount)}</TD>
                <TD className="num">{formatMoney(c.paymentTotal)}</TD>
                <TD className="num">{formatMoney(c.correctionTotal)}</TD>
                <TD className="num">{formatMoney(c.unmatchedTotal)}</TD>
                <TD className="num">{formatNumber(c.accountsJudged)}</TD>
                <TD className="num">{formatNumber(c.newNotices)}</TD>
                <TD className="num">{formatNumber(c.accelerations)}</TD>
                <TD className={c.mismatches > 0 ? 'num text-destructive' : 'num'}>{formatNumber(c.mismatches)}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function StaffList() {
  const client = useQueryClient()
  const staff = useQuery({ queryKey: queryKeys.staff, queryFn: endpoints.staff })
  const unlock = useMutation({
    mutationFn: endpoints.unlockStaff,
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.staff }),
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>직원</CardTitle>
      </CardHeader>
      <CardContent>
        <ErrorMessage error={staff.error ?? unlock.error} />
        <Table>
          <THead>
            <TR>
              <TH>ID</TH>
              <TH>아이디</TH>
              <TH>이름</TH>
              <TH>역할</TH>
              <TH>팀</TH>
              <TH>로그인 실패</TH>
              <TH>상태</TH>
              <TH />
            </TR>
          </THead>
          <TBody>
            {staff.data?.map((s) => (
              <TR key={s.id}>
                <TD>{s.id}</TD>
                <TD>{s.loginId}</TD>
                <TD>{s.name}</TD>
                <TD>{s.roleLabel}</TD>
                <TD>{s.teamId ?? '-'}</TD>
                <TD className="num">{s.failedAttempts}</TD>
                <TD>
                  {s.locked ? (
                    <Badge className="border-red-200 bg-red-50 text-red-700">잠김</Badge>
                  ) : (
                    <Badge className="border-emerald-200 bg-emerald-50 text-emerald-700">정상</Badge>
                  )}
                </TD>
                <TD>
                  {s.locked && (
                    <Button size="sm" variant="outline" onClick={() => unlock.mutate(s.id)}>
                      잠금 해제
                    </Button>
                  )}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function AccessLogs() {
  const logs = useQuery({ queryKey: queryKeys.accessLogs, queryFn: endpoints.accessLogs })
  return (
    <Card>
      <CardHeader>
        <CardTitle>접근 기록 (최근 100건)</CardTitle>
      </CardHeader>
      <CardContent>
        <ErrorMessage error={logs.error} />
        <Table>
          <THead>
            <TR>
              <TH>시각</TH>
              <TH>계정</TH>
              <TH>IP</TH>
              <TH>업무</TH>
              <TH>대상</TH>
              <TH>사유</TH>
            </TR>
          </THead>
          <TBody>
            {logs.data?.length === 0 && <EmptyRow colSpan={6} />}
            {logs.data?.map((l, i) => (
              <TR key={`${l.accessedAt}-${i}`}>
                <TD>{formatDateTime(l.accessedAt)}</TD>
                <TD>{l.loginId}</TD>
                <TD>{l.ip ?? '-'}</TD>
                <TD>{l.action}</TD>
                <TD>
                  {l.targetType ?? ''} {l.targetId ?? ''}
                </TD>
                <TD>{l.reason ?? ''}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

export function AdminPage() {
  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">운영 관리</h1>
      <Closing />
      <StaffList />
      <AccessLogs />
    </div>
  )
}
