/**
 * 화면 전체가 쓰는 단일 포맷터. 금액은 천 단위 구분 + "원", 날짜는 yyyy-MM-dd.
 * 서버 금액은 원 단위 정수(long)이므로 소수 처리를 하지 않는다.
 */

const numberFormat = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 })

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-'
  return numberFormat.format(value)
}

export function formatMoney(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-'
  return `${numberFormat.format(value)}원`
}

/** "2026-06-12" 또는 ISO 시각을 yyyy-MM-dd로 */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '-'
  return value.length >= 10 ? value.slice(0, 10) : value
}

/** ISO 시각 → yyyy-MM-dd HH:mm */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 이율(%) — 19.9 → "19.9%" */
export function formatRate(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '-'
  return `${Number(value).toString()}%`
}

/** 입력란 "1,234,000" → 1234000 */
export function parseMoney(input: string): number {
  const digits = input.replace(/[^0-9-]/g, '')
  return digits === '' || digits === '-' ? Number.NaN : Number(digits)
}

export const statusLabels: Record<string, string> = {
  NORMAL: '정상',
  DELINQUENT: '연체',
  ACCELERATED: '기한이익상실',
  WORKOUT: '채무조정',
  CHARGED_OFF: '상각',
  SOLD: '매각',
  PAID_OFF: '완제',
  PLANNED: '예정',
  PAID: '납부',
  PARTIALLY_PAID: '일부납부',
  OVERDUE: '미납',
  REPLACED: '대체됨',
  ALLOCATED: '충당',
  UNMATCHED: '미확인',
  RECEIVED: '접수',
  REVERSED: '은행취소',
  CANCELLED: '취소',
  OPEN: '미처리',
  MATCHED: '매칭',
  REQUESTED: '승인대기',
  APPROVED: '승인',
  APPLIED: '처리완료',
  REJECTED: '반려',
  PENDING: '진행중',
  EXECUTED: '상실',
  REVOKED: '소급취소',
  ACTIVE: '유효',
  ALLOWED: '허용',
  SCHEDULED: '예약',
  BLOCKED: '차단',
  TERMINATED: '해제',
  COMPLETED: '완료',
  CALL: '전화',
  SMS: '문자',
  VISIT: '방문',
  STATUTORY_NOTICE: '법정 통지',
  PAYMENT: '일부 변제(입금)',
  PAYMENT_ORDER_FILED: '지급명령 신청',
  SEIZURE: '압류',
  JUDGMENT_CONFIRMED: '판결·지급명령 확정',
  LIMITATION_IMMINENT: '소멸시효 임박',
  LIMITATION_COMPLETED: '소멸시효 완성',
  PROMISE_MISSED: '약속 불이행',
  PROMISE_TERMINATED: '약속 해제',
  NONE: '-',
  NOTIFY_STAFF: '담당자 알림',
  TERMINATE: '약속 해제',
  SENT: '발송',
  SKIPPED: '건너뜀',
  ENDED: '종료',
  PAYMENT_REMINDER: '납입 안내 (3일 전)',
  DELINQUENCY_1: '연체 안내 (1일째)',
  DELINQUENCY_7: '연체 안내 (7일째)',
  DELINQUENCY_30: '연체 안내 (30일째)',
  PAYOFF: '완제 안내',
  PROMISE_REMINDER: '약속 납부 안내',
  REPURCHASED: '환매',
  RELEASED: '해제',
  CONSUMED: '사용됨',
  INTEREST_SUSPENSION: '이자 부과 중단',
  BUYER_TRANSFER: '양수인 이전',
  SETOFF_IN: '상계 (받음)',
  SETOFF_OUT: '상계 (보냄)',
}

export function statusLabel(code: string | null | undefined): string {
  if (!code) return '-'
  return statusLabels[code] ?? code
}
