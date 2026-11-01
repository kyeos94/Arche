import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { PagedList } from '@/components/Pagination'
import { usePaging } from './usePaging'

function Demo({ count }: { count: number }) {
  const rows = Array.from({ length: count }, (_, i) => i + 1)
  const paging = usePaging(rows)
  return (
    <PagedList paging={paging.props}>
      <ul aria-label="행">
        {paging.rows.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>
    </PagedList>
  )
}

describe('리스트 페이징 (ADR 0011)', () => {
  it('기본 20건, 건수를 바꾸면 첫 페이지로, 총 N건은 하단에', async () => {
    render(<Demo count={45} />)
    const items = () => screen.getAllByRole('listitem').map((li) => Number(li.textContent))
    expect(items()).toHaveLength(20)
    expect(screen.getByText('총 45건')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '3페이지' }))
    expect(items()).toEqual([41, 42, 43, 44, 45])
    await userEvent.selectOptions(screen.getByLabelText('한 페이지 건수'), '10')
    expect(items()).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    expect(screen.getByRole('button', { name: '5페이지' })).toBeInTheDocument()
    // 최소 선택지는 10건
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['10건', '20건', '50건', '100건', '200건'])
  })

  it('한 페이지에 다 들어가면 페이지 버튼 없이 총 건수만', () => {
    render(<Demo count={3} />)
    expect(screen.getByText('총 3건')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '1페이지' })).not.toBeInTheDocument()
  })
})
