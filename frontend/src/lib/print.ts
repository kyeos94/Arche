/**
 * 양식 인쇄 (ADR 0010): 서버가 채운 HTML 조각을 A4 문서 한 장씩으로 묶어 숨긴 iframe에서 인쇄한다.
 * iframe이라 양식 CSS가 업무 화면에 섞이지 않고, 팝업 차단에도 걸리지 않는다.
 */
import type { RenderResult } from '@/api/documents'

const PAGE_CSS = `
@page { size: A4; margin: 18mm 16mm; }
html, body { margin: 0; background: #fff; }
.page { box-sizing: border-box; }
.page + .page { break-before: page; page-break-before: always; }
@media screen {
  body { background: #e5e7eb; padding: 12px 0; }
  .page { width: 210mm; min-height: 297mm; margin: 0 auto 12px; padding: 18mm 16mm; background: #fff; box-shadow: 0 1px 4px rgba(0,0,0,.2); }
}
`

/** 인쇄용 HTML 문서 전체 (미리보기 iframe의 srcdoc에도 그대로 쓴다) */
export function buildPrintDocument(result: Pick<RenderResult, 'templateName' | 'css' | 'documents'>): string {
  const pages = result.documents.map((d) => `<section class="page">${d.html}</section>`).join('\n')
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8" /><title>${escapeText(result.templateName)}</title><style>${PAGE_CSS}\n${result.css}</style></head><body>${pages}</body></html>`
}

function escapeText(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}

/** 숨긴 iframe에 문서를 쓰고 인쇄 대화상자를 연다. 인쇄가 끝나면(또는 1분 뒤) iframe을 지운다 */
export function printHtml(html: string): void {
  const frame = document.createElement('iframe')
  frame.setAttribute('aria-hidden', 'true')
  frame.style.position = 'fixed'
  frame.style.right = '0'
  frame.style.bottom = '0'
  frame.style.width = '0'
  frame.style.height = '0'
  frame.style.border = '0'
  const cleanup = () => frame.remove()
  frame.onload = () => {
    const win = frame.contentWindow
    if (!win) return cleanup()
    win.addEventListener('afterprint', cleanup)
    setTimeout(cleanup, 60_000)
    win.focus()
    win.print()
  }
  frame.srcdoc = html
  document.body.appendChild(frame)
}
