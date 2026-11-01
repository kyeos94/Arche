import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HolidayAdminPage, monthCells } from './HolidayAdminPage'

function withQuery(ui: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

function mockServer() {
  const holidays = [{ date: '2026-12-25', name: '성탄절' }]
  const calls: { method: string; url: string; body?: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, url, body })
      const json = (data: unknown) =>
        new Response(JSON.stringify(data), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      if (url === '/api/operation/business-date') return json({ businessDate: '2026-11-02', lastClosedAt: null })
      if (url.startsWith('/api/admin/holidays/changes')) return json([])
      if (url === '/api/admin/holidays' && method === 'POST') holidays.push(body)
      if (url === '/api/admin/holidays/removal')
        holidays.splice(
          holidays.findIndex((h) => h.date === body.date),
          1,
        )
      return json({
        year: 2026,
        businessDate: '2026-11-02',
        editableFrom: '2026-11-03',
        editableTo: '2029-11-02',
        holidays: [...holidays].sort((a, b) => a.date.localeCompare(b.date)),
      })
    }),
  )
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('휴일 관리 (R73)', () => {
  it('달력 칸: 2026년 11월은 일요일에 시작하고 30일', () => {
    const cells = monthCells(2026, 10)
    expect(cells[0]).toBe('2026-11-01')
    expect(cells.filter(Boolean)).toHaveLength(30)
    expect(monthCells(2026, 0).slice(0, 5)).toEqual([null, null, null, null, '2026-01-01']) // 목요일
  })

  it('평일을 누르면 휴일로 지정하고, 휴일을 누르면 해제한다. 주말·업무일자 이전은 누를 수 없다', async () => {
    const calls = mockServer()
    render(withQuery(<HolidayAdminPage />))
    const day = await screen.findByRole('button', {
      name: '2026-11-03 영업일',
    })
    expect(screen.getByRole('button', { name: '2026-11-07 주말' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '2026-11-02 영업일' })).toBeDisabled() // 업무일자
    expect(screen.getByRole('button', { name: '2026-10-30 영업일' })).toBeDisabled()

    await userEvent.clear(screen.getByLabelText('휴일 이름'))
    await userEvent.type(screen.getByLabelText('휴일 이름'), '창립기념일')
    await userEvent.click(day)
    expect(await screen.findByText(/휴일\(창립기념일\)로 지정했습니다/)).toBeInTheDocument()
    expect(calls).toContainEqual({
      method: 'POST',
      url: '/api/admin/holidays',
      body: { date: '2026-11-03', name: '창립기념일' },
    })

    await userEvent.click(await screen.findByRole('button', { name: '2026-11-03 휴일 창립기념일' }))
    expect(await screen.findByText(/휴일을 해제했습니다/)).toBeInTheDocument()
    expect(calls).toContainEqual({
      method: 'POST',
      url: '/api/admin/holidays/removal',
      body: { date: '2026-11-03' },
    })
    expect(await screen.findByRole('button', { name: '2026-11-03 영업일' })).toBeEnabled()
  })
})
