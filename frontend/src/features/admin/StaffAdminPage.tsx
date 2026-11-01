import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  type CreatedStaff,
  type RoleOptions,
  staffAccountKeys,
  staffAccounts,
  staffRoleKeys,
  staffRoles,
} from '@/api/adminConsole'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { StaffRow } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDate, formatDateTime } from '@/lib/format'
import { useSort } from '@/lib/useSort'
import { StaffRoleDialog } from './StaffRoleDialog'

type Filter = 'ACTIVE' | 'RESIGNED' | 'ALL'

const ACTION_LABEL = { CREATE: '등록', RESIGN: '퇴사', REINSTATE: '복직' } as const

/** 직원 관리 (R71·R75): 직원 목록, 등록, 역할·팀 변경, 퇴사(담당 건 이관), 복직, 이력 */
export function StaffAdminPage() {
  return (
    <div className="space-y-3">
      <StaffList />
      <StatusChanges />
      <RoleChanges />
    </div>
  )
}

function useRefreshStaff() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: queryKeys.staff }),
      client.invalidateQueries({ queryKey: staffAccountKeys.changes }),
    ])
}

function StaffList() {
  const { me } = useAuth()
  const refresh = useRefreshStaff()
  const staff = useQuery({ queryKey: queryKeys.staff, queryFn: endpoints.staff })
  const options = useQuery({ queryKey: staffRoleKeys.options, queryFn: staffRoles.options })
  const [filter, setFilter] = useState<Filter>('ACTIVE')
  const [search, setSearch] = useState('')
  const [dialog, setDialog] = useState<
    { kind: 'create' } | { kind: 'resign'; staff: StaffRow } | { kind: 'reinstate'; staff: StaffRow } | null
  >(null)
  const unlock = useMutation({ mutationFn: endpoints.unlockStaff, onSuccess: refresh })
  const teamName = (id: number | null) => options.data?.teams.find((t) => t.id === id)?.name ?? '-'
  const q = search.trim()
  const rows = (staff.data ?? [])
    .filter((s) => (filter === 'ALL' ? true : filter === 'ACTIVE' ? s.enabled : !s.enabled))
    .filter((s) => !q || s.name.includes(q) || s.loginId.includes(q) || teamName(s.teamId).includes(q))
  const { sorted, header } = useSort(rows, {
    id: (s) => s.id,
    loginId: (s) => s.loginId,
    name: (s) => s.name,
    role: (s) => s.roleLabel,
    team: (s) => teamName(s.teamId),
    failed: (s) => s.failedAttempts,
    status: (s) => (!s.enabled ? 2 : s.locked ? 1 : 0),
  })
  const activeCount = staff.data?.filter((s) => s.enabled).length ?? 0
  const resignedCount = (staff.data?.length ?? 0) - activeCount
  const lockedReason = (s: StaffRow) =>
    s.id === me?.id ? '자기 자신은 바꿀 수 없습니다' : s.role === 'DEMO_ALL' ? '데모 전용 계정입니다' : undefined

  return (
    <Card>
      <CardHeader>
        <CardTitle>직원 관리</CardTitle>
        <Button size="sm" disabled={!options.data} onClick={() => setDialog({ kind: 'create' })}>
          직원 등록
        </Button>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Select aria-label="재직 구분" value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
            <option value="ACTIVE">재직 ({activeCount})</option>
            <option value="RESIGNED">퇴사 ({resignedCount})</option>
            <option value="ALL">전체</option>
          </Select>
          <Input
            aria-label="직원 찾기"
            className="w-56"
            placeholder="이름·아이디·팀"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <ErrorMessage error={staff.error ?? options.error ?? unlock.error} />
        <Table>
          <THead>
            <TR>
              <SortTH {...header('id')}>ID</SortTH>
              <SortTH {...header('loginId')}>아이디</SortTH>
              <SortTH {...header('name')}>이름</SortTH>
              <SortTH {...header('role')}>역할</SortTH>
              <SortTH {...header('team')}>팀</SortTH>
              <SortTH {...header('failed')}>로그인 실패</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              <TH />
            </TR>
          </THead>
          <TBody>
            {staff.data && rows.length === 0 && <EmptyRow colSpan={8}>해당하는 직원이 없습니다</EmptyRow>}
            {sorted.map((s) => (
              <TR key={s.id} className={s.enabled ? undefined : 'text-muted-foreground'}>
                <TD>{s.id}</TD>
                <TD>{s.loginId}</TD>
                <TD>{s.name}</TD>
                <TD>{s.roleLabel}</TD>
                <TD>{teamName(s.teamId)}</TD>
                <TD className="num">{s.failedAttempts}</TD>
                <TD>
                  {!s.enabled ? (
                    <Badge className="border-slate-300 bg-slate-100 text-slate-600">
                      퇴사 {s.resignedAt ? formatDate(s.resignedAt.slice(0, 10)) : ''}
                    </Badge>
                  ) : s.locked ? (
                    <Badge className="border-red-200 bg-red-50 text-red-700">잠김</Badge>
                  ) : (
                    <Badge className="border-emerald-200 bg-emerald-50 text-emerald-700">재직</Badge>
                  )}
                </TD>
                <TD className="space-x-1 whitespace-nowrap">
                  {s.enabled && options.data && (
                    <StaffRoleDialog staff={s} options={options.data} disabledReason={lockedReason(s)} />
                  )}
                  {s.enabled && s.locked && (
                    <Button size="sm" variant="outline" onClick={() => unlock.mutate(s.id)}>
                      잠금 해제
                    </Button>
                  )}
                  {s.enabled ? (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={!!lockedReason(s)}
                      title={lockedReason(s)}
                      onClick={() => setDialog({ kind: 'resign', staff: s })}
                    >
                      퇴사 처리
                    </Button>
                  ) : (
                    <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'reinstate', staff: s })}>
                      복직
                    </Button>
                  )}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
      {dialog?.kind === 'create' && options.data && (
        <CreateDialog options={options.data} onClose={() => setDialog(null)} onDone={refresh} />
      )}
      {dialog?.kind === 'resign' && (
        <ResignDialog staff={dialog.staff} onClose={() => setDialog(null)} onDone={refresh} />
      )}
      {dialog?.kind === 'reinstate' && (
        <ReinstateDialog staff={dialog.staff} onClose={() => setDialog(null)} onDone={refresh} />
      )}
    </Card>
  )
}

/** 임시 비밀번호는 서버가 이 응답에서 한 번만 주므로 창을 닫기 전에 전달하게 한다 */
function TemporaryPassword({ created }: { created: CreatedStaff }) {
  const [copied, setCopied] = useState(false)
  return (
    <Alert tone="success">
      <p>
        아이디 <b>{created.loginId}</b>의 임시 비밀번호입니다. 이 창을 닫으면 다시 볼 수 없으니 본인에게 직접 전달하세요.
        첫 로그인 때 비밀번호를 바꾸게 됩니다.
      </p>
      <p className="mt-1 flex items-center gap-2">
        <code className="rounded bg-background px-2 py-0.5 font-mono text-sm" data-testid="temporary-password">
          {created.temporaryPassword}
        </code>
        <Button
          size="sm"
          variant="outline"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(created.temporaryPassword)
              setCopied(true)
            } catch {
              setCopied(false)
            }
          }}
        >
          {copied ? '복사됨' : '복사'}
        </Button>
      </p>
    </Alert>
  )
}

function CreateDialog({ options, onClose, onDone }: { options: RoleOptions; onClose: () => void; onDone: () => void }) {
  const [loginId, setLoginId] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState(options.roles[0]?.code ?? '')
  const [teamId, setTeamId] = useState(options.teams[0]?.id ?? 0)
  const create = useMutation({
    mutationFn: () => staffAccounts.create({ loginId: loginId.trim(), name: name.trim(), role, teamId }),
    onSuccess: onDone,
  })
  const idOk = /^[a-z][a-z0-9._-]{2,29}$/.test(loginId.trim())
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="직원 등록" description="임시 비밀번호는 시스템이 만들어 한 번만 보여 줍니다. (R75)">
        {create.data ? (
          <TemporaryPassword created={create.data} />
        ) : (
          <div className="space-y-2 text-xs">
            <label className="block space-y-1">
              <span className="block text-muted-foreground">로그인 ID (영문 소문자로 시작, 소문자·숫자·. - _ 3~30자)</span>
              <Input aria-label="로그인 ID" value={loginId} onChange={(e) => setLoginId(e.target.value)} />
            </label>
            {loginId && !idOk && <p className="text-destructive">로그인 ID 형식을 확인하세요</p>}
            <label className="block space-y-1">
              <span className="block text-muted-foreground">이름</span>
              <Input aria-label="이름" maxLength={50} value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <div className="grid grid-cols-2 gap-2">
              <label className="block space-y-1">
                <span className="block text-muted-foreground">역할</span>
                <Select aria-label="역할" className="w-full" value={role} onChange={(e) => setRole(e.target.value)}>
                  {options.roles.map((r) => (
                    <option key={r.code} value={r.code}>
                      {r.label}
                    </option>
                  ))}
                </Select>
              </label>
              <label className="block space-y-1">
                <span className="block text-muted-foreground">팀</span>
                <Select
                  aria-label="팀"
                  className="w-full"
                  value={teamId}
                  onChange={(e) => setTeamId(Number(e.target.value))}
                >
                  {options.teams.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </Select>
              </label>
            </div>
            <ErrorMessage error={create.error} />
          </div>
        )}
        <DialogFooter>
          {create.data ? (
            <Button onClick={onClose}>닫기</Button>
          ) : (
            <>
              <Button variant="outline" onClick={onClose}>
                취소
              </Button>
              <Button disabled={!idOk || !name.trim() || create.isPending} onClick={() => create.mutate()}>
                등록
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** 퇴사 처리 (R75): 넘길 담당 건을 보여 주고 후임을 골라 한 번에 이관 */
function ResignDialog({ staff, onClose, onDone }: { staff: StaffRow; onClose: () => void; onDone: () => void }) {
  const workload = useQuery({
    queryKey: staffAccountKeys.workload(staff.id),
    queryFn: () => staffAccounts.workload(staff.id),
    staleTime: 0,
  })
  const [reason, setReason] = useState('')
  const [loanTo, setLoanTo] = useState('')
  const [appTo, setAppTo] = useState('')
  const resign = useMutation({
    mutationFn: () =>
      staffAccounts.resign(staff.id, {
        reason: reason.trim(),
        loanSuccessorId: loanTo ? Number(loanTo) : null,
        applicationSuccessorId: appTo ? Number(appTo) : null,
      }),
    onSuccess: onDone,
  })
  const w = workload.data
  const needLoan = !!w && w.loans + w.alerts > 0
  const needApp = !!w && w.applications > 0
  const ready =
    !!w && reason.trim().length >= 2 && (!needLoan || !!loanTo) && (!needApp || !!appTo) && !resign.isPending
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        title={`${staff.name} 퇴사 처리`}
        description="로그인이 막히고 지금 접속 중이면 바로 끊깁니다. 기록은 지우지 않습니다. (R75)"
      >
        {resign.data ? (
          <Alert tone="success">
            퇴사 처리했습니다. {resign.data.handover ? `담당 건 이관: ${resign.data.handover}` : '넘길 담당 건은 없었습니다.'}
          </Alert>
        ) : (
          <div className="space-y-3 text-xs">
            <ErrorMessage error={workload.error ?? resign.error} />
            {w && (
              <ul className="space-y-0.5 rounded border p-2" aria-label="넘길 담당 건">
                <li>
                  완제 전 담당 계약 <b>{w.loans}</b>건 · 읽지 않은 추심 알림 <b>{w.alerts}</b>건
                </li>
                <li>
                  심사 진행 중 신청(배정·심사 중·승인·약정) <b>{w.applications}</b>건
                </li>
              </ul>
            )}
            {needLoan && (
              <label className="block space-y-1">
                <span className="block text-muted-foreground">계약·추심 알림을 넘겨받을 직원 (채권담당·채권팀장)</span>
                <Select
                  aria-label="계약 후임"
                  className="w-full"
                  value={loanTo}
                  onChange={(e) => setLoanTo(e.target.value)}
                >
                  <option value="">선택하세요</option>
                  {w.loanSuccessors.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} · {c.roleLabel} · {c.team ?? '-'}
                    </option>
                  ))}
                </Select>
              </label>
            )}
            {needApp && (
              <label className="block space-y-1">
                <span className="block text-muted-foreground">
                  신청을 넘겨받을 심사 직원 (담당 상품이 아니거나 부재 중이면 거절됩니다)
                </span>
                <Select aria-label="심사 후임" className="w-full" value={appTo} onChange={(e) => setAppTo(e.target.value)}>
                  <option value="">선택하세요</option>
                  {w.applicationSuccessors.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} · {c.roleLabel} · {c.team ?? '-'}
                    </option>
                  ))}
                </Select>
              </label>
            )}
            <label className="block space-y-1">
              <span className="block text-muted-foreground">퇴사 사유 (2~200자)</span>
              <Textarea aria-label="퇴사 사유" maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />
            </label>
          </div>
        )}
        <DialogFooter>
          {resign.data ? (
            <Button onClick={onClose}>닫기</Button>
          ) : (
            <>
              <Button variant="outline" onClick={onClose}>
                취소
              </Button>
              <Button variant="destructive" disabled={!ready} onClick={() => resign.mutate()}>
                퇴사 처리
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ReinstateDialog({ staff, onClose, onDone }: { staff: StaffRow; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const reinstate = useMutation({ mutationFn: () => staffAccounts.reinstate(staff.id, reason.trim()), onSuccess: onDone })
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        title={`${staff.name} 복직`}
        description="다시 로그인할 수 있게 하고 새 임시 비밀번호를 발급합니다. 퇴사 때 넘긴 담당 건은 되돌리지 않습니다."
      >
        {reinstate.data ? (
          <TemporaryPassword created={reinstate.data} />
        ) : (
          <div className="space-y-2 text-xs">
            <ErrorMessage error={reinstate.error} />
            <label className="block space-y-1">
              <span className="block text-muted-foreground">복직 사유 (2~200자)</span>
              <Textarea aria-label="복직 사유" maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />
            </label>
          </div>
        )}
        <DialogFooter>
          {reinstate.data ? (
            <Button onClick={onClose}>닫기</Button>
          ) : (
            <>
              <Button variant="outline" onClick={onClose}>
                취소
              </Button>
              <Button
                disabled={reason.trim().length < 2 || reinstate.isPending}
                onClick={() => reinstate.mutate()}
              >
                복직
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function StatusChanges() {
  const changes = useQuery({ queryKey: staffAccountKeys.changes, queryFn: staffAccounts.changes })
  return (
    <Card>
      <CardHeader>
        <CardTitle>등록·퇴사·복직 이력</CardTitle>
      </CardHeader>
      <CardContent>
        <ErrorMessage error={changes.error} />
        <Table>
          <THead>
            <TR>
              <TH>일시</TH>
              <TH>직원</TH>
              <TH>구분</TH>
              <TH>사유</TH>
              <TH>담당 건 이관</TH>
              <TH>처리자</TH>
            </TR>
          </THead>
          <TBody>
            {changes.data?.length === 0 && <EmptyRow colSpan={6}>이력이 없습니다</EmptyRow>}
            {changes.data?.map((c) => (
              <TR key={c.id}>
                <TD className="whitespace-nowrap">{formatDateTime(c.changedAt)}</TD>
                <TD>
                  {c.staffName} ({c.loginId})
                </TD>
                <TD>{ACTION_LABEL[c.action]}</TD>
                <TD>{c.reason}</TD>
                <TD>{c.detail ?? '-'}</TD>
                <TD>{c.changedBy}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function RoleChanges() {
  const changes = useQuery({ queryKey: staffRoleKeys.changes, queryFn: staffRoles.changes })
  return (
    <Card>
      <CardHeader>
        <CardTitle>역할 변경 이력</CardTitle>
      </CardHeader>
      <CardContent>
        <ErrorMessage error={changes.error} />
        <Table>
          <THead>
            <TR>
              <TH>일시</TH>
              <TH>직원</TH>
              <TH>변경 전</TH>
              <TH>변경 후</TH>
              <TH>처리자</TH>
              <TH>사유</TH>
            </TR>
          </THead>
          <TBody>
            {changes.data?.length === 0 && <EmptyRow colSpan={6}>역할을 바꾼 직원이 없습니다</EmptyRow>}
            {changes.data?.map((c) => (
              <TR key={c.id}>
                <TD className="whitespace-nowrap">{formatDateTime(c.changedAt)}</TD>
                <TD>
                  {c.staffName} ({c.loginId})
                </TD>
                <TD>
                  {c.fromRole} · {c.fromTeam ?? '-'}
                </TD>
                <TD>
                  {c.toRole} · {c.toTeam ?? '-'}
                </TD>
                <TD>{c.changedBy}</TD>
                <TD>{c.reason}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}
