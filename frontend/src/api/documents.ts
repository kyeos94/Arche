/**
 * 양식 인쇄 API (R72, ADR 0010). 백엔드 document.internal.DocumentController와 같은 모양.
 * 서버가 치환 문자열을 채운 HTML 조각을 주고, 인쇄는 브라우저가 한다 (lib/print.ts).
 */
import { api } from './client'

export type DocumentPurpose = 'PREVIEW' | 'PRINT'

export interface PrintTemplateInfo {
  code: string
  name: string
  description: string
  /** 추심 안내(연락 유예·추심 중지면 제외)인지. 법정 통지는 false */
  collectionNotice: boolean
  placeholders: string[]
}

export interface RenderedDocument {
  accountId: number
  accountNo: string
  customerName: string
  documentNo: string
  html: string
}

export interface SkippedDocument {
  accountId: number
  accountNo: string | null
  reason: string
}

export interface RenderResult {
  templateCode: string
  templateName: string
  css: string
  batchNo: string | null
  documents: RenderedDocument[]
  skipped: SkippedDocument[]
}

export interface PrintLogRow {
  id: number
  batchNo: string
  templateName: string
  documentNo: string
  businessDate: string
  printedBy: string
  printedAt: string
}

export const documentKeys = {
  templates: ['documents', 'templates'] as const,
  placeholders: ['documents', 'placeholders'] as const,
  preview: (code: string, accountId: number) => ['documents', 'preview', code, accountId] as const,
  printLog: (accountId: number) => ['documents', 'print-log', accountId] as const,
}

export const documents = {
  templates: () => api.get<PrintTemplateInfo[]>('/api/documents/templates'),
  placeholders: () => api.get<{ name: string; description: string }[]>('/api/documents/placeholders'),
  render: (body: { templateCode: string; accountIds: number[]; purpose: DocumentPurpose }) =>
    api.post<RenderResult>('/api/documents/render', body),
  printLog: (accountId: number) => api.get<PrintLogRow[]>(`/api/documents/print-log/${accountId}`),
}
