/**
 * 주 메뉴. 화면 목록(SCREENS)은 코드가 정하고(경로·기본 이름·필요 권한), 대분류와 순서·이름은 메뉴 관리(R70)에서
 * 서버에 저장한 값을 쓴다. 서버 메뉴를 아직 못 받았으면 DEFAULT_GROUPS를 쓴다.
 * 메뉴가 보이는지는 화면의 필요 권한으로만 정한다 (권한은 권한 관리 R69). 권한이 없는 메뉴만 남은 대분류는 숨긴다.
 */
export interface Screen {
  label: string
  /** 이 권한이 있어야 보임 */
  permission?: string
  /** 이 중 하나라도 있으면 보임 */
  anyOf?: string[]
}

export interface NavItem extends Screen {
  to: string
}

export interface NavGroup {
  key: string
  label: string
  items: NavItem[]
}

/** 메뉴에 넣을 수 있는 화면 전체 */
export const SCREENS: Record<string, Screen> = {
  '/applications': { label: '신청 목록', permission: 'application.review' },
  '/underwriters': { label: '심사자 배정', permission: 'application.review' },
  '/brokers': { label: '중개업자', permission: 'application.review' },
  '/products': { label: '상품', permission: 'application.review' },
  '/mock-platform': { label: 'Mock 플랫폼', permission: 'application.review' },
  '/loans': { label: '계약 목록', permission: 'loan.read' },
  '/loans/new': { label: '계약 등록', permission: 'loan.create' },
  '/adjustments': { label: '조정 결재', permission: 'loan.adjust.request' },
  '/charge-off': { label: '상각 후보', permission: 'loan.chargeoff.confirm' },
  '/unmatched': { label: '미확인 입금', permission: 'payment.register' },
  '/payment-splits': { label: '일괄 입금 배분', permission: 'payment.register' },
  '/bulk': { label: '입금 일괄 등록', permission: 'payment.register' },
  '/corrections': { label: '정정 승인', permission: 'payment.cancel.closed.approve' },
  '/withdrawals': {
    label: '출금 작성·승인',
    anyOf: ['withdrawal.create', 'withdrawal.approve.small', 'withdrawal.approve.large'],
  },
  '/alerts': { label: '추심 알림', permission: 'loan.read' },
  '/legal-actions': { label: '법조치 현황', permission: 'loan.read' },
  '/reports': { label: '보고서', permission: 'report.read' },
  '/admin': { label: '운영 관리', permission: 'admin.manage' },
  '/admin/staff': { label: '직원 관리', permission: 'admin.manage' },
  '/admin/menus': { label: '메뉴 관리', permission: 'admin.manage' },
  '/admin/permissions': { label: '권한 관리', permission: 'admin.manage' },
  '/admin/holidays': { label: '휴일 관리', permission: 'admin.manage' },
  '/message-templates': { label: '문자 템플릿', permission: 'admin.manage' },
  '/credit-reports': { label: '신용정보 보고', permission: 'admin.manage' },
  '/account': { label: '내 계정' },
  '/notes': { label: '쪽지함' },
}

/** 메뉴에서 뺄 수 없는 화면 (서버 MenuAdminService.REQUIRED_PATHS와 같음) */
export const REQUIRED_PATHS = ['/admin/menus', '/admin/permissions']

/** 서버 메뉴 모양 (대분류 → 화면 경로·이름) */
export interface MenuGroupInput {
  id?: number | null
  label: string
  items: { path: string; label: string }[]
}

/** 처음 설치했을 때의 메뉴 (V18·V22·V23·V24 시드와 같음, '/admin/staff'는 V24에서 '/admin' 바로 아래) */
export const DEFAULT_MENU: MenuGroupInput[] = [
  {
    label: '대출신청관리',
    items: ['/applications', '/underwriters', '/brokers', '/products', '/mock-platform'].map(seed),
  },
  { label: '여신관리', items: ['/loans', '/loans/new', '/adjustments', '/charge-off'].map(seed) },
  {
    label: '입출금관리',
    items: ['/unmatched', '/payment-splits', '/bulk', '/corrections', '/withdrawals'].map(seed),
  },
  { label: '회수관리', items: ['/alerts', '/legal-actions'].map(seed) },
  { label: '보고서', items: ['/reports'].map(seed) },
  {
    label: '시스템설정',
    // '/admin/holidays'(V22)·'/notes'(V23)는 마이그레이션에서 그룹 끝에 붙였으므로 여기서도 끝에 둔다
    items: [
      '/admin',
      '/admin/staff',
      '/admin/menus',
      '/admin/permissions',
      '/message-templates',
      '/credit-reports',
      '/account',
      '/admin/holidays',
      '/notes',
    ].map(seed),
  },
]

function seed(path: string) {
  return { path, label: SCREENS[path].label }
}

/** 서버 메뉴 → 화면 메뉴. 코드에 없는 화면 경로는 버린다 */
export function toNavGroups(menu: MenuGroupInput[]): NavGroup[] {
  return menu.map((g, i) => ({
    key: g.id != null ? `g${g.id}` : `d${i}`,
    label: g.label,
    items: g.items.flatMap((item) => {
      const screen = SCREENS[item.path]
      return screen ? [{ ...screen, to: item.path, label: item.label || screen.label }] : []
    }),
  }))
}

export const NAV_GROUPS: NavGroup[] = toNavGroups(DEFAULT_MENU)

/** 화면을 볼 수 있는 권한인지 */
export function canSee(screen: Screen, can: (permission: string) => boolean): boolean {
  return (!screen.permission || can(screen.permission)) && (!screen.anyOf || screen.anyOf.some((p) => can(p)))
}

/** 권한으로 거른 대분류·메뉴 (빈 대분류 제외) */
export function visibleGroups(can: (permission: string) => boolean, groups: NavGroup[] = NAV_GROUPS): NavGroup[] {
  return groups
    .map((g) => ({ ...g, items: g.items.filter((n) => canSee(n, can)) }))
    .filter((g) => g.items.length > 0)
}

/** 현재 경로가 속한 메뉴: 경로 앞부분이 가장 길게 맞는 메뉴 (/loans/12 → 계약 목록, /loans/new → 계약 등록) */
export function activeItem(groups: NavGroup[], pathname: string): { group: NavGroup; item: NavItem } | null {
  let best: { group: NavGroup; item: NavItem } | null = null
  for (const group of groups) {
    for (const item of group.items) {
      const hit = pathname === item.to || pathname.startsWith(item.to + '/')
      if (hit && (!best || item.to.length > best.item.to.length)) best = { group, item }
    }
  }
  return best
}
