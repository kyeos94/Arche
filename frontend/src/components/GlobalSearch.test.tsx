import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SearchHit } from '@/api/types'
import { GlobalSearch } from './GlobalSearch'

const hit = (id: number, name: string): SearchHit => ({
  id,
  accountNo: `L0000100${id}`,
  customerName: name,
  residentNoMasked: '800101-1******',
  phoneMasked: '010-****-0001',
  productCode: 'P3',
  status: 'DELINQUENT',
  principalBalance: 11_000_000,
  matchedBy: '이름',
})

function renderSearch(hits: SearchHit[]) {
  const fetchMock = vi.fn(async (_input: RequestInfo | URL) => new Response(JSON.stringify(hits), { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/loans']}>
        <GlobalSearch />
        <Routes>
          <Route path="/loans" element={<p>목록</p>} />
          <Route path="/loans/:id" element={<p>상세 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return fetchMock
}

describe('GlobalSearch', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('결과가 하나면 바로 계약 상세로 이동한다', async () => {
    const fetchMock = renderSearch([hit(1, '홍길동')])
    await userEvent.type(screen.getByRole('textbox', { name: '통합 검색' }), '홍길동{Enter}')
    expect(await screen.findByText('상세 화면')).toBeInTheDocument()
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/loans/search?q=')
  })

  it('결과가 여럿이면 목록에서 고른다', async () => {
    renderSearch([hit(1, '홍길동'), hit(2, '홍길순')])
    await userEvent.type(screen.getByRole('textbox', { name: '통합 검색' }), '홍길{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: /홍길순/ }))
    expect(await screen.findByText('상세 화면')).toBeInTheDocument()
  })

  it('찾은 계약이 없으면 안내한다', async () => {
    renderSearch([])
    await userEvent.type(screen.getByRole('textbox', { name: '통합 검색' }), '없는사람{Enter}')
    expect(await screen.findByText(/찾은 계약이 없습니다/)).toBeInTheDocument()
  })
})
