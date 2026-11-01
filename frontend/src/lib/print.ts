/**
 * 양식 인쇄 (ADR 0010): 서버가 채운 HTML 조각을 A4 문서 한 장씩으로 묶어 숨긴 iframe에서 인쇄한다.
 * iframe이라 양식 CSS가 업무 화면에 섞이지 않고, 팝업 차단에도 걸리지 않는다.
 */
import type { RenderResult } from '@/api/documents'

/*
 * 종이 여백은 0으로 두고 .page 안쪽 여백으로 대신한다. 그래야 화면 미리보기와 인쇄의 쪽 크기가 같아서
 * 화면에서 잰 높이로 한 장에 맞출 수 있다(fitPages). 높이를 297mm보다 조금 작게 두는 것은
 * 소수점 반올림으로 빈 쪽이 하나 더 나오는 것을 막기 위해서다.
 */
const PAGE_CSS = `
@page { size: A4; margin: 0; }
html, body { margin: 0; background: #fff; }
.page { box-sizing: border-box; width: 210mm; height: 296mm; padding: 14mm 16mm; overflow: hidden; background: #fff; break-inside: avoid; }
.page + .page { break-before: page; page-break-before: always; }
@media screen {
  body { background: #e5e7eb; padding: 12px 0; }
  .page { margin: 0 auto 12px; box-shadow: 0 1px 4px rgba(0,0,0,.2); }
}
`

/** 인쇄용 HTML 문서 전체 (미리보기 iframe의 srcdoc에도 그대로 쓴다) */
export function buildPrintDocument(result: Pick<RenderResult, 'templateName' | 'css' | 'documents'>): string {
  const pages = result.documents.map((d) => `<section class="page"><div class="fit">${d.html}</div></section>`).join('\n')
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8" /><title>${escapeText(result.templateName)}</title><style>${PAGE_CSS}\n${result.css}</style></head><body>${pages}</body></html>`
}

function escapeText(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}

/** 이보다 작게는 줄이지 않는다 (글자가 너무 작아져 읽기 어렵다) */
const MIN_ZOOM = 0.7

/**
 * 내용이 A4 한 장을 넘는 쪽은 넘친 만큼 줄여 한 장에 맞춘다 (긴 주소, 양식 수정 등).
 * zoom은 글자 폭도 줄여 줄바꿈이 다시 일어나므로 몇 번 반복해 맞춘다. 줄인 배율을 쪽마다 돌려준다.
 */
export function fitPages(doc: Document | null | undefined): number[] {
  if (!doc) return []
  return Array.from(doc.querySelectorAll<HTMLElement>('section.page')).map((page) => {
    const fit = page.querySelector<HTMLElement>(':scope > .fit')
    if (!fit) return 1
    let zoom = 1
    fit.style.zoom = ''
    for (let i = 0; i < 4 && page.scrollHeight > page.clientHeight + 1 && zoom > MIN_ZOOM; i++) {
      zoom = Math.max(MIN_ZOOM, zoom * (page.clientHeight / page.scrollHeight) * 0.99)
      fit.style.zoom = String(zoom)
    }
    return zoom
  })
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
    fitPages(frame.contentDocument)
    win.focus()
    win.print()
  }
  frame.srcdoc = html
  document.body.appendChild(frame)
}
