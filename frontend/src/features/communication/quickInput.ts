import type { DispatchChannel, SendRequest } from './api'

export type QuickMode = 'CALL' | 'VISIT' | DispatchChannel | 'MEMO'

/** 빠른 입력 값 → 발송 요청. 템플릿을 고르면 제목·내용은 서버가 템플릿으로 채운다 */
export function buildSendRequest(
  channel: DispatchChannel,
  v: { templateId: string; title: string; body: string; faxNumber: string },
): SendRequest {
  const req: SendRequest = { channel }
  if (v.templateId !== '') {
    req.templateId = Number(v.templateId)
  } else {
    req.body = v.body.trim()
    if (channel !== 'SMS' && v.title.trim() !== '') req.title = v.title.trim()
  }
  if (channel === 'FAX') req.faxNumber = v.faxNumber.trim()
  return req
}
