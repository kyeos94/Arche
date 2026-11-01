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
}
