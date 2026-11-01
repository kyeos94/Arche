import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type * as React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MenuAdminPage } from './MenuAdminPage'

function withQuery(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}

const MENU = [
  { id: 1, label: '여신관리', items: [{ path: '/loans', label: '계약 목록' }] },
  {
    id: 2,
    label: '시스템설정',
    items: [
      { path: '/admin/menus', label: '메뉴 관리' },
      { path: '/admin/permissions', label: '권한 관리' },
    ],
  },
]

function mockFetch() {
  const calls: { method: string; url: string; body?: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, url, body })
      const data = method === 'PUT' ? (body as { groups: unknown }).groups : MENU
      return new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }),
  )
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('메뉴 관리 (R70)', () => {
  it('필수 화면은 뺄 수 없고, 메뉴가 남은 대분류는 지울 수 없다', async () => {
    mockFetch()
    render(withQuery(<MenuAdminPage />))
    expect(await screen.findByDisplayValue('계약 목록')).toBeInTheDocument()
    const remove = screen.getAllByRole('button', { name: '빼기' })
    expect(remove[0]).toBeEnabled() // 계약 목록
    expect(remove[1]).toBeDisabled() // 메뉴 관리
    expect(remove[2]).toBeDisabled() // 권한 관리
    screen.getAllByRole('button', { name: '대분류 삭제' }).forEach((b) => expect(b).toBeDisabled())
  })

  it('대분류를 추가하고 메뉴를 옮겨 사유와 함께 저장한다', async () => {
    const calls = mockFetch()
    render(withQuery(<MenuAdminPage />))
    await screen.findByDisplayValue('계약 목록')
    await userEvent.type(screen.getByLabelText('새 대분류 이름'), '경영정보')
    await userEvent.click(screen.getByRole('button', { name: '대분류 추가' }))
    await userEvent.selectOptions(screen.getByLabelText('추가할 화면'), '/reports')
    await userEvent.selectOptions(screen.getByLabelText('넣을 대분류'), '2')
    await userEvent.click(screen.getByRole('button', { name: '메뉴 추가' }))

    const save = screen.getByRole('button', { name: '메뉴 저장' })
    expect(save).toBeDisabled() // 사유 없음
    await userEvent.type(screen.getByLabelText('변경 사유'), '보고서 분리')
    await userEvent.click(save)
    expect(await screen.findByText(/메뉴를 저장했습니다/)).toBeInTheDocument()

    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe('/api/admin/menus')
    expect(put.body).toEqual({
      reason: '보고서 분리',
      groups: [
        { label: '여신관리', items: MENU[0].items },
        { label: '시스템설정', items: MENU[1].items },
        { label: '경영정보', items: [{ path: '/reports', label: '보고서' }] },
      ],
    })
  })
})
