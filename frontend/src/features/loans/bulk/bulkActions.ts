/**
 * 계약 목록 일괄 작업 등록표 (ADR 0010). 선택한 계약으로 띄울 작업을 여기에 하나씩 더한다.
 * 새 작업 = 항목 하나 + 대화상자 컴포넌트 하나 + 서버 API 하나 (권한·데이터 범위·건너뛰기 규칙은 작업마다 서버가 판단).
 *
 * 다음에 붙일 작업 예:
 * - 담당자 일괄 변경: { key: 'assign', label: '담당자 변경', permission: 'loan.term.change', Dialog: BulkAssignDialog }
 * - 속성 일괄 변경(상태 메모·관리 구분 등)
 */
import type * as React from 'react'
import type { LoanListItem } from '@/api/types'
import { BulkSmsDialog } from './BulkSmsDialog'
import { PrintBulkDialog } from './PrintBulkDialog'

/** 일괄 작업 대화상자가 받는 값 */
export interface BulkActionProps {
  /** 선택한 계약 (페이지를 넘겨도 유지) */
  selected: LoanListItem[]
  /** 대화상자를 닫음 */
  onClose: () => void
  /** 작업이 끝나 선택을 비워야 할 때 */
  onDone: () => void
}

export interface BulkAction {
  key: string
  label: string
  /** 이 권한이 모두 있어야 버튼이 보임 */
  permissions: string[]
  /** 한 번에 고를 수 있는 최대 계약 수 (서버 제한과 같게) */
  max: number
  Dialog: React.ComponentType<BulkActionProps>
}

export const bulkActions: BulkAction[] = [
  {
    key: 'print',
    label: '양식 일괄 인쇄',
    permissions: ['loan.read', 'document.print'],
    max: 200,
    Dialog: PrintBulkDialog,
  },
  {
    // 7일 7회·연락 유예·추심 중지는 계약마다 서버가 검사하고 막힌 계약은 건너뜀 (ADR 0011)
    key: 'sms',
    label: '문자 일괄 발송',
    permissions: ['loan.read', 'collection.activity'],
    max: 200,
    Dialog: BulkSmsDialog,
  },
]
