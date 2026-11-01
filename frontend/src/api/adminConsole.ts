/**
 * 메뉴 관리(R70)·권한 관리(R69) API. 백엔드 staff.internal.MenuController·PermissionAdminController와 같은 모양.
 */
import { api, qs } from './client'

export interface MenuItemDto {
  path: string
  label: string
}

export interface MenuGroupDto {
  id?: number | null
  label: string
  items: MenuItemDto[]
}

export interface PermissionCell {
  role: string
  granted: boolean
  byDefault: boolean
  locked: boolean
}

export interface PermissionRow {
  code: string
  label: string
  cells: PermissionCell[]
}

export interface PermissionMatrix {
  roles: { code: string; label: string }[]
  permissions: PermissionRow[]
  changedCells: number
}

export interface PermissionChange {
  id: number
  role: string | null
  roleLabel: string | null
  permission: string | null
  permissionLabel: string | null
  action: 'GRANT' | 'REVOKE' | 'DEFAULT' | 'RESET_ALL'
  changedBy: string
  changedAt: string
  reason: string
}

export const adminConsoleKeys = {
  menus: ['menus'] as const,
  permissions: ['admin', 'permissions'] as const,
  permissionChanges: ['admin', 'permissions', 'changes'] as const,
  holidays: (year: number) => ['admin', 'holidays', year] as const,
  holidayChanges: ['admin', 'holidays', 'changes'] as const,
}

export const adminConsole = {
  menus: () => api.get<MenuGroupDto[]>('/api/menus'),
  saveMenus: (groups: MenuGroupDto[], reason: string) => api.put<MenuGroupDto[]>('/api/admin/menus', { groups, reason }),
  permissions: () => api.get<PermissionMatrix>('/api/admin/permissions'),
  setPermission: (body: { role: string; permission: string; granted: boolean; reason: string }) =>
    api.post<PermissionMatrix>('/api/admin/permissions', body),
  resetPermissions: (reason: string) => api.post<PermissionMatrix>('/api/admin/permissions/reset', { reason }),
  permissionChanges: (limit = 100) =>
    api.get<PermissionChange[]>(`/api/admin/permissions/changes${qs({ limit })}`),
  holidays: (year: number) => api.get<HolidayYear>(`/api/admin/holidays${qs({ year })}`),
  addHoliday: (date: string, name: string) => api.post<HolidayYear>('/api/admin/holidays', { date, name }),
  removeHoliday: (date: string) => api.post<HolidayYear>('/api/admin/holidays/removal', { date }),
  holidayChanges: (limit = 100) => api.get<HolidayChange[]>(`/api/admin/holidays/changes${qs({ limit })}`),
}

/** 휴일 관리 (R73) */
export interface HolidayYear {
  year: number
  businessDate: string
  /** 이 날부터 바꿀 수 있음 (업무일자 다음 날) */
  editableFrom: string
  editableTo: string
  holidays: { date: string; name: string }[]
}

export interface HolidayChange {
  id: number
  date: string
  action: 'ADD' | 'REMOVE'
  name: string
  businessDate: string
  changedBy: string
  changedAt: string
}

/** 직원 역할·팀 변경 (R71) */
export interface RoleOptions {
  roles: { code: string; label: string }[]
  teams: { id: number; name: string }[]
}

export interface StaffRoleChange {
  id: number
  staffId: number
  loginId: string
  staffName: string
  fromRole: string
  toRole: string
  fromTeam: string | null
  toTeam: string | null
  changedBy: string
  changedAt: string
  reason: string
}

export const staffRoleKeys = {
  options: ['admin', 'staff', 'role-options'] as const,
  changes: ['admin', 'staff', 'role-changes'] as const,
}

export const staffRoles = {
  options: () => api.get<RoleOptions>('/api/admin/staff/role-options'),
  change: (id: number, body: { role: string; teamId: number; reason: string }) =>
    api.post<StaffRoleChange[]>(`/api/admin/staff/${id}/role`, body),
  changes: () => api.get<StaffRoleChange[]>('/api/admin/staff/role-changes'),
}
