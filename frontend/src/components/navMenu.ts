/**
 * 주 메뉴 구성. 업무 대분류 → 메뉴. 권한이 없는 메뉴는 숨기고, 메뉴가 하나도 남지 않은 대분류도 숨긴다.
 * 대분류 순서는 업무 흐름(신청 → 계약 → 입출금 → 회수 → 보고 → 설정)을 따른다.
 */
export interface NavItem {
  to: string
  label: string
  /** 이 권한이 있어야 보임 */
  permission?: string
  /** 이 중 하나라도 있으면 보임 */
  anyOf?: string[]
}

export interface NavGroup {
  key: string
  label: string
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'origination',
    label: '대출신청관리',
    items: [
      { to: '/applications', label: '신청 목록', permission: 'application.review' },
      { to: '/underwriters', label: '심사자 배정', permission: 'application.review' },
      { to: '/brokers', label: '중개업자', permission: 'application.review' },
      { to: '/products', label: '상품', permission: 'application.review' },
      { to: '/mock-platform', label: 'Mock 플랫폼', permission: 'application.review' },
    ],
  },
  {
    key: 'servicing',
    label: '여신관리',
    items: [
      { to: '/loans', label: '계약 목록', permission: 'loan.read' },
      { to: '/loans/new', label: '계약 등록', permission: 'loan.create' },
      { to: '/adjustments', label: '조정 결재', permission: 'loan.adjust.request' },
      { to: '/charge-off', label: '상각 후보', permission: 'loan.chargeoff.confirm' },
    ],
  },
  {
    key: 'payment',
    label: '입출금관리',
    items: [
      { to: '/unmatched', label: '미확인 입금', permission: 'payment.register' },
      { to: '/payment-splits', label: '일괄 입금 배분', permission: 'payment.register' },
      { to: '/bulk', label: '입금 일괄 등록', permission: 'payment.register' },
      { to: '/corrections', label: '정정 승인', permission: 'payment.cancel.closed.approve' },
      {
        to: '/withdrawals',
        label: '출금 작성·승인',
        anyOf: ['withdrawal.create', 'withdrawal.approve.small', 'withdrawal.approve.large'],
      },
    ],
  },
  {
    key: 'collection',
    label: '회수관리',
    items: [
      { to: '/alerts', label: '추심 알림', permission: 'loan.read' },
      { to: '/legal-actions', label: '법조치 현황', permission: 'loan.read' },
    ],
  },
  {
    key: 'reports',
    label: '보고서',
    items: [{ to: '/reports', label: '보고서', permission: 'report.read' }],
  },
  {
    key: 'system',
    label: '시스템설정',
    items: [
      { to: '/admin', label: '운영 관리', permission: 'admin.manage' },
      { to: '/message-templates', label: '문자 템플릿', permission: 'admin.manage' },
      { to: '/credit-reports', label: '신용정보 보고', permission: 'admin.manage' },
      { to: '/account', label: '내 계정' },
    ],
  },
]

/** 권한으로 거른 대분류·메뉴 (빈 대분류 제외) */
export function visibleGroups(can: (permission: string) => boolean): NavGroup[] {
  return NAV_GROUPS.map((g) => ({
    ...g,
    items: g.items.filter(
      (n) => (!n.permission || can(n.permission)) && (!n.anyOf || n.anyOf.some((p) => can(p))),
    ),
  })).filter((g) => g.items.length > 0)
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
