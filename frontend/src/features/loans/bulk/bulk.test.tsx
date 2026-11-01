import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LoanListItem } from '@/api/types'
import { buildPrintDocument } from '@/lib/print'
import { PrintBulkDialog } from './PrintBulkDialog'

vi.mock('@/lib/print', async (orig) => ({ ...(await orig<typeof import('@/lib/print')>()), printHtml: vi.fn() }))
const { printHtml } = await import('@/lib/print')

function withQuery(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

const rows = [{ id: 1, accountNo: 'L1' }, { id: 2, accountNo: 'L2' }] as LoanListItem[]

function mockServer() {
  const bodies: unknown[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const json = (data: unknown) =>
        new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url === '/api/documents/templates') {
        return json([
          { code: 'DEMAND_LETTER', name: '변제 독촉장', description: '', collectionNotice: true, placeholders: [] },
        ])
      }
      const body = JSON.parse(String(init?.body))
      bodies.push(body)
      return json({
        templateCode: 'DEMAND_LETTER',
        templateName: '변제 독촉장',
        css: '.doc{}',
        batchNo: body.purpose === 'PRINT' ? 'P20261101-00001' : null,
        documents: [{ accountId: 1, accountNo: 'L1', customerName: '홍길동', documentNo: 'x', html: '<p>독촉</p>' }],
        skipped: [{ accountId: 2, accountNo: 'L2', reason: '연체 중이 아닙니다' }],
      })
    }),
  )
  return bodies
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.mocked(printHtml).mockClear()
})

describe('양식 일괄 인쇄 (R72)', () => {
  it('대상 확인 후에만 인쇄할 수 있고, 제외 이유를 보여 준 뒤 대상만 인쇄한다', async () => {
    const bodies = mockServer()
    const onDone = vi.fn()
    render(withQuery(<PrintBulkDialog selected={rows} onClose={() => {}} onDone={onDone} />))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('option', { name: '변제 독촉장' })
    const print = within(dialog).getByRole('button', { name: '인쇄' })
    expect(print).toBeDisabled()

    await userEvent.click(within(dialog).getByRole('button', { name: '대상 확인' }))
    expect(await within(dialog).findByText(/인쇄 대상 1건 · 제외 1건/)).toBeInTheDocument()
    expect(within(dialog).getByText(/연체 중이 아닙니다/)).toBeInTheDocument()
    expect(printHtml).not.toHaveBeenCalled()

    await userEvent.click(within(dialog).getByRole('button', { name: '인쇄' }))
    expect(await within(dialog).findByText(/1건을 인쇄했습니다 \(배치 P20261101-00001\)/)).toBeInTheDocument()
    expect(printHtml).toHaveBeenCalledTimes(1)
    expect(bodies).toEqual([
      { templateCode: 'DEMAND_LETTER', accountIds: [1, 2], purpose: 'PREVIEW' },
      { templateCode: 'DEMAND_LETTER', accountIds: [1, 2], purpose: 'PRINT' },
    ])
    await userEvent.click(within(dialog).getByRole('button', { name: '닫고 선택 비우기' }))
    expect(onDone).toHaveBeenCalled()
  })
})

describe('인쇄 문서', () => {
  it('문서마다 A4 한 장으로 묶고 제목을 이스케이프한다', () => {
    const html = buildPrintDocument({
      templateName: '<독촉장>',
      css: '.doc{color:red}',
      documents: [
        { accountId: 1, accountNo: 'L1', customerName: 'a', documentNo: '1', html: '<p>1</p>' },
        { accountId: 2, accountNo: 'L2', customerName: 'b', documentNo: '2', html: '<p>2</p>' },
      ],
    })
    expect(html.match(/<section class="page">/g)).toHaveLength(2)
    expect(html).toContain('<title>&lt;독촉장&gt;</title>')
    expect(html).toContain('.doc{color:red}')
    expect(html).toContain('@page { size: A4')
  })
})
