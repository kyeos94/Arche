import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NotePopup } from './NotePopup'

vi.mock('@/features/auth/auth', () => ({ useAuth: () => ({ me: { id: 1 }, can: () => true }) }))

function Location() {
  return <output data-testid="location">{useLocation().pathname + useLocation().search}</output>
}

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/loans']}>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <NotePopup />
                <Location />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const note = (id: number, readAt: string | null = null) => ({
  id,
  sender: '김채권',
  subject: `쪽지 ${id}`,
  ref: null,
  sentAt: '2026-11-13T10:00:00+09:00',
  readAt,
})

function mockServer(items: ReturnType<typeof note>[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const json = (data: unknown) =>
        new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url === '/api/notes/unread-count') return json({ count: items.filter((n) => !n.readAt).length })
      if (url === '/api/notes/inbox') return json(items)
      return json([])
    }),
  )
}

beforeEach(() => window.localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

describe('새 쪽지 팝업 (R74)', () => {
  it('안 읽은 새 쪽지를 작은 창으로 알리고, [열기]는 그 쪽지로 이동하며 다시 띄우지 않는다', async () => {
    mockServer([note(3), note(2), note(1, 'x')])
    const view = setup()
    const popup = await screen.findByRole('alertdialog', { name: '새 쪽지' })
    expect(popup).toHaveTextContent('새 쪽지 2건')
    expect(popup).toHaveTextContent('쪽지 3')
    expect(popup).toHaveTextContent('외 1건')
    await userEvent.click(screen.getByRole('button', { name: '열기' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/notes?open=3')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    // 새로고침해도 이미 알린 쪽지는 다시 띄우지 않는다
    view.unmount()
    setup()
    await screen.findByTestId('location')
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('안 읽은 쪽지가 없으면 띄우지 않는다', async () => {
    mockServer([note(1, 'x')])
    setup()
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})
