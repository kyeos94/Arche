/**
 * 채권 생애주기 API (정책서 R39~R46, ADR 0005): 상각·매각·환매·양수인 이전, 수기 조정·상계·상태 되돌리기 결재, 추심 중지.
 * 백엔드 servicing.internal.LifecycleController·collection.internal.CollectionStopController의 record와 같은 모양.
 */
import { api, qs } from './client'
import type { AccountRef } from './types'

export interface SaleDto {
  id: number
  buyer: string
  salePrice: number
  soldOn: string
  noticeSentOn: string
  reason: string | null
  status: 'ACTIVE' | 'REPURCHASED' | 'CANCELLED'
  repurchaseDeadline: string
  repurchasedOn: string | null
  repurchasePrice: number | null
  repurchaseReason: string | null
  createdBy: string
}

export interface SetoffEventDto {
  eventId: number
  direction: 'IN' | 'OUT'
  date: string
  amount: number
  counterpartAccountId: number | null
  counterpartAccountNo: string | null
  source: 'REFUND' | 'SUSPENSE' | null
  status: string
  reason: string | null
}

export type AdjustmentType =
  | 'ADJUSTMENT'
  | 'SETOFF'
  | 'REVERT_CHARGE_OFF'
  | 'REVERT_SALE'
  | 'REVERT_SETOFF'
  | 'REVERT_PAYOFF'

export interface AdjustmentRequestDto {
  id: number
  requestNo: string
  type: AdjustmentType
  accountId: number
  accountNo: string | null
  sourceAccountId: number | null
  sourceAccountNo: string | null
  setoffSource: 'REFUND' | 'SUSPENSE' | null
  principalDown: number
  normalDown: number
  lateDown: number
  costDelta: number
  preAcqDown: number
  amount: number
  targetEventId: number | null
  reason: string
  status: 'REQUESTED' | 'APPLIED' | 'REJECTED' | 'CONSUMED'
  requestedBy: string
  requestedOn: string
  decidedBy: string | null
  decidedOn: string | null
  appliedEventId: number | null
  consumedOn: string | null
}

export interface Lifecycle {
  businessDate: string
  status: string
  acceleratedOn: string | null
  chargedOffOn: string | null
  soldOn: string | null
  transferPending: number
  claim: number
  refundPending: number
  suspenseBalance: number
  chargeOffCandidate: boolean
  chargeOffBlocker: string | null
  lastPaymentOn: string | null
  sales: SaleDto[]
  setoffs: SetoffEventDto[]
  requests: AdjustmentRequestDto[]
}

export interface ChargeOffCandidate {
  accountId: number
  accountNo: string
  productCode: string
  assignedStaffId: number | null
  acceleratedOn: string
  daysSinceAcceleration: number
  lastPaymentOn: string | null
  claim: number
}

export interface AdjustmentRequestBody {
  type: AdjustmentType
  accountId: number
  sourceAccountNo?: string
  setoffSource?: 'REFUND' | 'SUSPENSE'
  principalDown?: number
  normalDown?: number
  lateDown?: number
  costDelta?: number
  preAcqDown?: number
  amount?: number
  targetEventId?: number
  reason: string
}

export type StopReason = 'DECEASED' | 'REHABILITATION' | 'BANKRUPTCY' | 'PUBLIC_WORKOUT'

export interface CollectionStopDto {
  id: number
  groupNo: string
  accountId: number
  reason: StopReason
  reasonLabel: string
  suspendsInterest: boolean
  effectiveDate: string
  memo: string
  status: 'ACTIVE' | 'RELEASED'
  releasedOn: string | null
  releaseReason: string | null
  interestEventId: number | null
  reliefRate: number | null
  reliefFrom: string | null
  createdBy: string
  createdAt: string
}

export const lifecycleKeys = {
  lifecycle: (id: number) => ['loan', id, 'lifecycle'] as const,
  collectionStops: (id: number) => ['loan', id, 'collection-stops'] as const,
  candidates: ['charge-off-candidates'] as const,
  requests: (status: string) => ['adjustment-requests', status] as const,
}

export const lifecycleApi = {
  lifecycle: (id: number) => api.get<Lifecycle>(`/api/loans/${id}/lifecycle`),
  candidates: () => api.get<ChargeOffCandidate[]>('/api/loans/charge-off-candidates'),
  chargeOff: (id: number, reason: string) => api.post<AccountRef>(`/api/loans/${id}/charge-off`, { reason }),
  sell: (id: number, body: { buyer: string; price: number; noticeSentOn: string; reason: string }) =>
    api.post<AccountRef>(`/api/loans/${id}/sale`, body),
  repurchase: (id: number, reason: string) => api.post<AccountRef>(`/api/loans/${id}/repurchase`, { reason }),
  transferToBuyer: (id: number, body: { amount: number; memo?: string }) =>
    api.post<AccountRef>(`/api/loans/${id}/buyer-transfers`, body),

  requests: (status: string) => api.get<AdjustmentRequestDto[]>(`/api/adjustment-requests${qs({ status })}`),
  request: (body: AdjustmentRequestBody) => api.post<AdjustmentRequestDto>('/api/adjustment-requests', body),
  decide: (id: number, approve: boolean) =>
    api.post<AdjustmentRequestDto>(`/api/adjustment-requests/${id}/decision`, { approve }),

  collectionStops: (id: number) => api.get<CollectionStopDto[]>(`/api/loans/${id}/collection-stops`),
  registerStop: (id: number, body: { reason: StopReason; effectiveDate: string; memo: string }) =>
    api.post<CollectionStopDto[]>(`/api/loans/${id}/collection-stops`, body),
  releaseStop: (id: number, stopId: number, reason: string) =>
    api.post<CollectionStopDto[]>(`/api/loans/${id}/collection-stops/${stopId}/release`, { reason }),
  confirmRelief: (id: number, stopId: number, body: { rate: number; validFrom: string }) =>
    api.post<CollectionStopDto>(`/api/loans/${id}/collection-stops/${stopId}/confirm-relief`, body),
}

export const adjustmentTypeLabels: Record<AdjustmentType, string> = {
  ADJUSTMENT: '수기 조정',
  SETOFF: '상계',
  REVERT_CHARGE_OFF: '상각 취소',
  REVERT_SALE: '매각 취소',
  REVERT_SETOFF: '상계 취소',
  REVERT_PAYOFF: '완제 취소 (입금 취소 1건 허용)',
}

export const stopReasonLabels: Record<StopReason, string> = {
  DECEASED: '사망(상속)',
  REHABILITATION: '개인회생',
  BANKRUPTCY: '파산',
  PUBLIC_WORKOUT: '공적 채무조정(가상)',
}
