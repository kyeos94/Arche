import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { type RoleOptions, staffRoleKeys, staffRoles } from '@/api/adminConsole'
import { queryKeys } from '@/api/endpoints'
import type { StaffRow } from '@/api/types'
import { ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Input, Select } from '@/components/ui/input'

/** 직원 역할·팀 변경 (R71). 바꾸면 그 직원은 즉시 로그아웃되고 새 역할로 다시 로그인한다 */
export function StaffRoleDialog({
  staff,
  options,
  disabledReason,
}: {
  staff: StaffRow
  options: RoleOptions
  disabledReason?: string
}) {
  const client = useQueryClient()
  const initialTeam = staff.teamId ?? options.teams[0]?.id ?? 0
  const [open, setOpen] = useState(false)
  const [role, setRole] = useState(staff.role)
  const [teamId, setTeamId] = useState(initialTeam)
  const [reason, setReason] = useState('')
  const change = useMutation({
    mutationFn: () => staffRoles.change(staff.id, { role, teamId, reason }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: queryKeys.staff })
      await client.invalidateQueries({ queryKey: staffRoleKeys.changes })
      setOpen(false)
    },
  })
  const unchanged = role === staff.role && teamId === staff.teamId
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (o) {
          setRole(staff.role)
          setTeamId(initialTeam)
          setReason('')
          change.reset()
        }
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" disabled={!!disabledReason} title={disabledReason}>
          역할 변경
        </Button>
      </DialogTrigger>
      <DialogContent
        title={`역할 변경 · ${staff.name} (${staff.loginId})`}
        description="권한은 역할에서 따라옵니다. 바꾸면 이 직원의 로그인이 바로 끊기고, 다시 로그인하면 새 역할의 권한이 적용됩니다. (R71)"
      >
        <div className="space-y-3 text-xs">
          <label className="block space-y-1">
            <span className="block text-muted-foreground">역할</span>
            <Select aria-label="역할" value={role} onChange={(e) => setRole(e.target.value)} className="w-full">
              {options.roles.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.code === staff.role ? `${r.label} (현재)` : r.label}
                </option>
              ))}
            </Select>
          </label>
          <label className="block space-y-1">
            <span className="block text-muted-foreground">팀</span>
            <Select
              aria-label="팀"
              value={teamId}
              onChange={(e) => setTeamId(Number(e.target.value))}
              className="w-full"
            >
              {options.teams.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.id === staff.teamId ? `${t.name} (현재)` : t.name}
                </option>
              ))}
            </Select>
          </label>
          <label className="block space-y-1">
            <span className="block text-muted-foreground">변경 사유 (2~200자)</span>
            <Input aria-label="변경 사유" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
          </label>
          {staff.role === 'COLLECTOR' && role !== 'COLLECTOR' && (
            <p className="text-amber-600">채권담당의 담당 계약은 그대로 남습니다. 필요하면 담당자를 따로 재배정하세요.</p>
          )}
          <ErrorMessage error={change.error} />
        </div>
        <DialogFooter>
          <Button disabled={unchanged || reason.trim().length < 2 || change.isPending} onClick={() => change.mutate()}>
            변경
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
