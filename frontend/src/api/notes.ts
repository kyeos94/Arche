/** 쪽지 (R74). 백엔드 note.internal.NoteController와 같은 모양 */
import { api } from './client'

export type NoteRefType = 'LOAN' | 'APPLICATION'

export interface NoteRef {
  type: NoteRefType
  id: number
  label: string
}

export interface InboxItem {
  id: number
  /** null이면 시스템 쪽지 */
  sender: string | null
  subject: string
  ref: NoteRef | null
  sentAt: string
  readAt: string | null
}

export interface SentItem {
  id: number
  subject: string
  recipients: string
  ref: NoteRef | null
  sentAt: string
  recipientCount: number
  readCount: number
}

export interface NoteDetail {
  id: number
  senderId: number | null
  sender: string | null
  subject: string
  body: string
  ref: NoteRef | null
  recipientSummary: string
  sentAt: string
  mine: boolean
  /** 내가 보낸 쪽지일 때만: 받는 사람별 읽음 */
  recipients: { name: string; team: string | null; readAt: string | null }[] | null
}

export interface NoteDirectory {
  staff: { id: number; name: string; role: string; teamId: number | null; team: string | null }[]
  teams: { id: number; name: string; members: number }[]
}

export interface SendNote {
  staffIds: number[]
  teamIds: number[]
  subject: string
  body: string
  ref: NoteRef | null
}

export const noteKeys = {
  all: ['notes'] as const,
  inbox: ['notes', 'inbox'] as const,
  sent: ['notes', 'sent'] as const,
  unread: ['notes', 'unread'] as const,
  directory: ['notes', 'directory'] as const,
}

export const notes = {
  inbox: () => api.get<InboxItem[]>('/api/notes/inbox'),
  sent: () => api.get<SentItem[]>('/api/notes/sent'),
  unreadCount: () => api.get<{ count: number }>('/api/notes/unread-count'),
  directory: () => api.get<NoteDirectory>('/api/notes/directory'),
  send: (body: SendNote) => api.post<{ id: number }>('/api/notes', body),
  open: (id: number) => api.post<NoteDetail>(`/api/notes/${id}/open`),
  hide: (id: number) => api.post<void>(`/api/notes/${id}/hide`),
}

/** 붙인 계약·신청 화면 주소 */
export function refPath(ref: NoteRef): string {
  return ref.type === 'LOAN' ? `/loans/${ref.id}` : `/applications/${ref.id}`
}
