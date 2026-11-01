import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { SortTH, TBody, TD, THead, TR, Table } from '@/components/ui/table'
import { compareValues, nextSort, sortRows, useSort } from './useSort'

describe('정렬 (ADR 0011)', () => {
  it('오름차순 → 내림차순 → 해제', () => {
    const asc = nextSort(null, 'a')
    expect(asc).toEqual({ key: 'a', direction: 'asc' })
    const desc = nextSort(asc, 'a')
    expect(desc).toEqual({ key: 'a', direction: 'desc' })
    expect(nextSort(desc, 'a')).toBeNull()
    expect(nextSort(desc, 'b')).toEqual({ key: 'b', direction: 'asc' })
  })

  it('숫자는 숫자로, 글자는 한글·자연 순서로, 빈 값은 항상 뒤로', () => {
    expect(compareValues(2, 10, 'asc')).toBeLessThan(0)
    expect(compareValues('L10', 'L9', 'asc')).toBeGreaterThan(0)
    expect(compareValues('가', '나', 'desc')).toBeGreaterThan(0)
    const rows = [{ v: null }, { v: 3 }, { v: 1 }, { v: undefined }, { v: 2 }]
    expect(sortRows(rows, { key: 'v', direction: 'asc' }, { v: (r) => r.v }).map((r) => r.v)).toEqual([
      1,
      2,
      3,
      null,
      undefined,
    ])
    expect(sortRows(rows, { key: 'v', direction: 'desc' }, { v: (r) => r.v }).map((r) => r.v)).toEqual([
      3,
      2,
      1,
      null,
      undefined,
    ])
  })

  it('머리글을 누르면 행 순서가 바뀌고 aria-sort가 따라간다', async () => {
    const rows = [
      { name: '나', amount: 300 },
      { name: '가', amount: 1000 },
      { name: '다', amount: 20 },
    ]
    function Demo() {
      const { sorted, header } = useSort(rows, { name: (r) => r.name, amount: (r) => r.amount })
      return (
        <Table>
          <THead>
            <TR>
              <SortTH {...header('name')}>이름</SortTH>
              <SortTH {...header('amount')} className="text-right">
                금액
              </SortTH>
            </TR>
          </THead>
          <TBody>
            {sorted.map((r) => (
              <TR key={r.name}>
                <TD>{r.name}</TD>
                <TD>{r.amount}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      )
    }
    render(<Demo />)
    const names = () => screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[0].textContent)
    expect(names()).toEqual(['나', '가', '다'])
    await userEvent.click(screen.getByRole('button', { name: /금액/ }))
    expect(names()).toEqual(['다', '나', '가'])
    expect(screen.getByRole('columnheader', { name: /금액/ })).toHaveAttribute('aria-sort', 'ascending')
    await userEvent.click(screen.getByRole('button', { name: /금액/ }))
    expect(names()).toEqual(['가', '나', '다'])
    await userEvent.click(screen.getByRole('button', { name: /금액/ }))
    expect(names()).toEqual(['나', '가', '다'])
    expect(screen.getByRole('columnheader', { name: /금액/ })).toHaveAttribute('aria-sort', 'none')
  })
})
