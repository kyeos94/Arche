import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatDateTime, formatMoney, formatNumber } from '@/lib/format'
import { useSort } from '@/lib/useSort'

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
  const { sorted, header } = useSort(closings.data, {
    businessDate: (c) => c.businessDate,
    closedAt: (c) => c.closedAt,
    paymentCount: (c) => c.paymentCount,
    paymentTotal: (c) => c.paymentTotal,
    correctionTotal: (c) => c.correctionTotal,
    unmatchedTotal: (c) => c.unmatchedTotal,
    accountsJudged: (c) => c.accountsJudged,
    newNotices: (c) => c.newNotices,
    accelerations: (c) => c.accelerations,
    mismatches: (c) => c.mismatches,
  })
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
              <SortTH {...header('businessDate')}>업무일자</SortTH>
              <SortTH {...header('closedAt')}>마감 시각</SortTH>
              <SortTH {...header('paymentCount')} className="text-right">
                입금 건수
              </SortTH>
              <SortTH {...header('paymentTotal')} className="text-right">
                입금 합계
              </SortTH>
              <SortTH {...header('correctionTotal')} className="text-right">
                정정
              </SortTH>
              <SortTH {...header('unmatchedTotal')} className="text-right">
                미확인 잔액
              </SortTH>
              <SortTH {...header('accountsJudged')} className="text-right">
                판정
              </SortTH>
              <SortTH {...header('newNotices')} className="text-right">
                새 통지
              </SortTH>
              <SortTH {...header('accelerations')} className="text-right">
                상실
              </SortTH>
              <SortTH {...header('mismatches')} className="text-right">
                대사 불일치
              </SortTH>
            </TR>
          </THead>
          <TBody>
            {closings.data?.length === 0 && <EmptyRow colSpan={10} />}
            {sorted.map((c) => (
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

function AccessLogs() {
  const logs = useQuery({ queryKey: queryKeys.accessLogs, queryFn: endpoints.accessLogs })
  const { sorted, header } = useSort(logs.data, {
    accessedAt: (l) => l.accessedAt,
    loginId: (l) => l.loginId,
    ip: (l) => l.ip,
    action: (l) => l.action,
    reason: (l) => l.reason,
  })
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
              <SortTH {...header('accessedAt')}>시각</SortTH>
              <SortTH {...header('loginId')}>계정</SortTH>
              <SortTH {...header('ip')}>IP</SortTH>
              <SortTH {...header('action')}>업무</SortTH>
              <TH>대상</TH>
              <SortTH {...header('reason')}>사유</SortTH>
            </TR>
          </THead>
          <TBody>
            {logs.data?.length === 0 && <EmptyRow colSpan={6} />}
            {sorted.map((l, i) => (
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
      <p className="text-xs text-muted-foreground">
        직원 등록·역할 변경·퇴사는 <Link to="/admin/staff" className="text-primary hover:underline">시스템설정 &gt; 직원 관리</Link>에서
        합니다.
      </p>
      <AccessLogs />
    </div>
  )
}
