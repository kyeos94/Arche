import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { NotesPage } from './NotesPage'

function withProviders(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  )
}

function mockServer() {
  const calls: { method: string; url: string; body?: unknown }[] = []
  let read = false
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, url, body })
      const json = (data: unknown) =>
        new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
      const ref = { type: 'LOAN', id: 7, label: 'L00000007' }
      if (url === '/api/notes/inbox') {
        return json([
          { id: 1, sender: '김채권', subject: '상환 문의', ref, sentAt: '2026-11-01T10:00:00+09:00', readAt: read ? 'x' : null },
          { id: 2, sender: null, subject: '[결재 요청] 수기 조정', ref, sentAt: '2026-11-01T09:00:00+09:00', readAt: 'x' },
        ])
      }
      if (url === '/api/notes/1/open') {
        read = true
        return json({
          id: 1,
          senderId: 5,
          sender: '김채권',
          subject: '상환 문의',
          body: '고객이 전화했습니다.\n확인 부탁드립니다.',
          ref,
          recipientSummary: '박담당 (1명)',
          sentAt: '2026-11-01T10:00:00+09:00',
          mine: false,
          recipients: null,
        })
      }
      if (url === '/api/notes/directory') {
        return json({
          staff: [
            { id: 5, name: '김채권', role: 'COLLECTOR', teamId: 2, team: '채권1팀' },
            { id: 6, name: '이출납', role: 'TELLER', teamId: 4, team: '출납팀' },
          ],
          teams: [
            { id: 2, name: '채권1팀', members: 1 },
            { id: 4, name: '출납팀', members: 1 },
          ],
        })
      }
      if (url === '/api/notes' && method === 'POST') return json({ id: 3 })
      return json([])
    }),
  )
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('쪽지함 (R74)', () => {
  it('받은 쪽지를 열면 내용과 계약 연결이 보이고, 답장은 보낸 사람·제목·계약을 채워 보낸다', async () => {
    const calls = mockServer()
    render(withProviders(<NotesPage />))
    expect(await screen.findByRole('tab', { name: '받은 쪽지 (1)' })).toBeInTheDocument()
    expect(screen.getByText('시스템')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /상환 문의/ }))
    expect(await screen.findByText(/확인 부탁드립니다/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /계약 L00000007 열기/ })).toHaveAttribute('href', '/loans/7')

    await userEvent.click(screen.getByRole('button', { name: '답장' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('제목')).toHaveValue('RE: 상환 문의')
    expect(
      await within(dialog).findByText((_, el) => el?.tagName === 'P' && /^받는 사람 1명/.test(el.textContent ?? '')),
    ).toBeInTheDocument()
    await userEvent.type(within(dialog).getByLabelText('내용'), '확인했습니다')
    await userEvent.click(within(dialog).getByRole('button', { name: '보내기' }))
    expect(calls).toContainEqual({
      method: 'POST',
      url: '/api/notes',
      body: {
        staffIds: [5],
        teamIds: [],
        subject: 'RE: 상환 문의',
        body: '확인했습니다',
        ref: { type: 'LOAN', id: 7, label: 'L00000007' },
      },
    })
  })

  it('주민등록번호 형식이 있으면 보내기를 막는다', async () => {
    mockServer()
    render(withProviders(<NotesPage />))
    await userEvent.click(await screen.findByRole('button', { name: '쪽지 쓰기' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(await within(dialog).findByRole('checkbox', { name: /^출납팀/ }))
    await userEvent.type(within(dialog).getByLabelText('제목'), '확인')
    await userEvent.type(within(dialog).getByLabelText('내용'), '900101-1234567 고객')
    expect(within(dialog).getByRole('alert')).toHaveTextContent('주민등록번호')
    expect(within(dialog).getByRole('button', { name: '보내기' })).toBeDisabled()
    await userEvent.clear(within(dialog).getByLabelText('내용'))
    await userEvent.type(within(dialog).getByLabelText('내용'), '계약 확인 부탁')
    expect(within(dialog).getByRole('button', { name: '보내기' })).toBeEnabled()
  })
})
