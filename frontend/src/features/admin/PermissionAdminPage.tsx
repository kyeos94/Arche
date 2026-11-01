import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { type PermissionCell, adminConsole, adminConsoleKeys } from '@/api/adminConsole'
import { queryKeys } from '@/api/endpoints'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

const ACTION_LABEL = { GRANT: '부여', REVOKE: '회수', DEFAULT: '기본값으로', RESET_ALL: '전체 기본값' } as const

/** 권한 관리 (R69): 정책서 9.1 기본값에서 역할별 권한을 칸 단위로 바꿈 */
export function PermissionAdminPage() {
  const client = useQueryClient()
  const matrix = useQuery({ queryKey: adminConsoleKeys.permissions, queryFn: adminConsole.permissions })
  const changes = useQuery({
    queryKey: adminConsoleKeys.permissionChanges,
    queryFn: () => adminConsole.permissionChanges(100),
  })
  const [reason, setReason] = useState('')
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: adminConsoleKeys.permissionChanges })
    // 내 권한이 바뀌었을 수 있으므로 메뉴·화면 권한도 다시 받음
    await client.invalidateQueries({ queryKey: queryKeys.me })
  }
  const set = useMutation({
    mutationFn: adminConsole.setPermission,
    onSuccess: async (data) => {
      client.setQueryData(adminConsoleKeys.permissions, data)
      await refresh()
    },
  })
  const reset = useMutation({
    mutationFn: () => adminConsole.resetPermissions(reason),
    onSuccess: async (data) => {
      client.setQueryData(adminConsoleKeys.permissions, data)
      await refresh()
    },
  })
  const reasonOk = reason.trim().length >= 2
  const busy = set.isPending || reset.isPending

  return (
    <div className="space-y-3">
      <Card>
        <CardHeader>
          <CardTitle>권한 관리</CardTitle>
          <Button
            size="sm"
            variant="destructive"
            disabled={!reasonOk || busy || !matrix.data?.changedCells}
            onClick={() => {
              if (window.confirm('바꾼 권한을 모두 정책서 9.1 기본값으로 되돌립니다.')) reset.mutate()
            }}
          >
            기본값으로 모두 되돌리기
          </Button>
        </CardHeader>
        <CardContent className="space-y-2 text-xs">
          <p className="text-muted-foreground">
            기본값은 정책서 9.1 역할별 권한표입니다. 칸을 누르면 바로 적용되고, 로그인 중인 직원도 다음 요청부터 바뀐 권한이
            적용됩니다. 금액별 전결·작성자 ≠ 승인자 같은 업무 규칙은 권한과 별도로 그대로 검사합니다. 시스템관리자의 관리
            권한은 바꿀 수 없습니다. (R69)
          </p>
          <label className="block space-y-1">
            <span className="block text-muted-foreground">변경 사유 (2~200자, 칸을 바꾸기 전에 입력)</span>
            <Input aria-label="변경 사유" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
          </label>
          <ErrorMessage error={matrix.error ?? set.error ?? reset.error} />
          {matrix.data && matrix.data.changedCells > 0 && (
            <Alert tone="warn">정책서 기본값과 다른 칸이 {matrix.data.changedCells}개 있습니다 (노란 칸).</Alert>
          )}
          {matrix.data && (
            <div className="overflow-x-auto">
              <Table>
                <THead>
                  <TR>
                    <TH className="min-w-56">권한</TH>
                    {matrix.data.roles.map((r) => (
                      <TH key={r.code} className="text-center whitespace-nowrap">
                        {r.label}
                      </TH>
                    ))}
                  </TR>
                </THead>
                <TBody>
                  {matrix.data.permissions.map((row) => (
                    <TR key={row.code}>
                      <TD>
                        {row.label} <span className="font-mono text-muted-foreground">{row.code}</span>
                      </TD>
                      {row.cells.map((cell) => (
                        <TD
                          key={cell.role}
                          className={cn(
                            'text-center',
                            cell.granted !== cell.byDefault && 'bg-amber-100 dark:bg-amber-900/40',
                          )}
                        >
                          <CellToggle
                            cell={cell}
                            label={`${row.label} · ${matrix.data.roles.find((r) => r.code === cell.role)?.label}`}
                            disabled={cell.locked || !reasonOk || busy}
                            onChange={(granted) =>
                              set.mutate({ role: cell.role, permission: row.code, granted, reason })
                            }
                          />
                        </TD>
                      ))}
                    </TR>
                  ))}
                </TBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>변경 이력</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <THead>
              <TR>
                <TH>일시</TH>
                <TH>변경</TH>
                <TH>역할</TH>
                <TH>권한</TH>
                <TH>처리자</TH>
                <TH>사유</TH>
              </TR>
            </THead>
            <TBody>
              {changes.data?.length === 0 && <EmptyRow colSpan={6}>바꾼 권한이 없습니다</EmptyRow>}
              {changes.data?.map((c) => (
                <TR key={c.id}>
                  <TD className="whitespace-nowrap">{formatDateTime(c.changedAt)}</TD>
                  <TD>
                    <Badge>{ACTION_LABEL[c.action]}</Badge>
                  </TD>
                  <TD>{c.roleLabel ?? '-'}</TD>
                  <TD>{c.permissionLabel ?? '-'}</TD>
                  <TD>{c.changedBy}</TD>
                  <TD>{c.reason}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}

function CellToggle({
  cell,
  label,
  disabled,
  onChange,
}: {
  cell: PermissionCell
  label: string
  disabled: boolean
  onChange: (granted: boolean) => void
}) {
  return (
    <label className="inline-flex flex-col items-center gap-0.5">
      <input
        type="checkbox"
        aria-label={label}
        checked={cell.granted}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 accent-primary"
      />
      {cell.locked && <span className="text-[10px] text-muted-foreground">고정</span>}
      {!cell.locked && cell.granted !== cell.byDefault && (
        <span className="text-[10px] text-amber-700 dark:text-amber-300">기본 {cell.byDefault ? 'O' : '—'}</span>
      )}
    </label>
  )
}
