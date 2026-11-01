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
  SPLIT: '일괄배분',
  CUSTOMER: '본인',
  GUARANTOR: '보증인',
  THIRD_PARTY: '제3자',
  OLDEST_DELINQUENCY_FIRST: '연체 우선',
  PROPORTIONAL: '잔액 비례',
  MANUAL: '수동 지정',
  BANK_TRANSFER: '계좌이체',
  VIRTUAL_ACCOUNT: '가상계좌',
  AUTO_DEBIT: '자동이체',
  CASH: '현금',
  AUCTION_DISTRIBUTION: '경매 배당금',
  COURT_DEPOSIT: '공탁금 출급',
  REPURCHASED: '환매',
  RELEASED: '해제',
  CONSUMED: '사용됨',
  INTEREST_SUSPENSION: '이자 부과 중단',
  BUYER_TRANSFER: '양수인 이전',
  SETOFF_IN: '상계 (받음)',
  SETOFF_OUT: '상계 (보냄)',
}

/** 법조치 종류·단계·비용 (R51·R52). 계약 상태 SOLD(매각)와 겹쳐 따로 둔다 */
export const legalLabels: Record<string, string> = {
  PAYMENT_ORDER: '지급명령',
  SEIZURE: '압류',
  AUCTION: '경매',
  FILED: '신청',
  ISSUED: '발령',
  SERVED: '송달',
  OBJECTED: '이의신청',
  CONFIRMED: '확정',
  ORDERED: '압류 결정',
  COLLECTED: '회수',
  COMMENCED: '개시 결정',
  SALE_SCHEDULED: '매각기일',
  SOLD: '매각 허가',
  DISTRIBUTED: '배당',
  CLOSED: '종결',
  WITHDRAWN: '취하',
  DISMISSED: '각하·기각',
  RELEASED: '압류 해제',
  COURT_FEE: '인지대 등',
  SERVICE_FEE: '송달료',
  EXECUTION_FEE: '집행·감정 수수료',
  AGENT_FEE: '대리인 비용',
  OTHER: '기타',
}

export function legalLabel(code: string | null | undefined): string {
  if (!code) return '-'
  return legalLabels[code] ?? code
}

export function statusLabel(code: string | null | undefined): string {
  if (!code) return '-'
  return statusLabels[code] ?? code
}
