import { ApiError, type ApiErrorBody, readCookie } from './client'

/** Content-Disposition에서 파일명 (filename*=UTF-8''… 우선) */
export function filenameFrom(header: string | null): string | null {
  if (!header) return null
  const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(header)
  if (star) return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''))
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1] : null
}

/**
 * 엑셀 다운로드 (POST + CSRF). 사유는 본문으로만 보내 URL에 남기지 않는다 (ADR 0009).
 * 실패하면 서버 오류 본문으로 ApiError, 성공하면 저장한 파일명을 돌려준다.
 */
export async function downloadXlsx(url: string, body: unknown, fallbackName = 'download.xlsx'): Promise<string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = readCookie('XSRF-TOKEN')
  if (token) headers['X-XSRF-TOKEN'] = token
  const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body), credentials: 'include' })
  if (!res.ok) {
    let data: unknown
    try {
      data = JSON.parse(await res.text())
    } catch {
      data = undefined
    }
    const err =
      data && typeof data === 'object' && 'message' in data
        ? (data as ApiErrorBody)
        : { code: `HTTP_${res.status}`, message: `다운로드에 실패했습니다 (${res.status})` }
    throw new ApiError(res.status, err)
  }
  const blob = await res.blob()
  const name = filenameFrom(res.headers.get('Content-Disposition')) ?? fallbackName
  const href = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = href
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(href)
  return name
}
