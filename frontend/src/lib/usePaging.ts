/**
 * 목록 페이징 (ADR 0011). 화면에 이미 받은 행을 브라우저에서 페이지로 나눈다.
 * 서버에서 페이지로 받는 목록(계약 목록)은 같은 PagedList에 서버 값을 넘긴다.
 */
import { useState } from 'react'

/** 한 페이지 건수 선택지 (최소 10건). 서버 페이징 목록도 같은 값 (LoanQuery.PAGE_SIZES) */
export const PAGE_SIZES = [10, 20, 50, 100, 200] as const
export const DEFAULT_PAGE_SIZE = 20

/** PagedList가 받는 값 */
export interface PagingProps {
  /** 0부터 */
  page: number
  totalPages: number
  totalElements: number
  size: number
  onPage: (page: number) => void
  onSize: (size: number) => void
}

export function usePaging<T>(rows: readonly T[] | undefined, initialSize: number = DEFAULT_PAGE_SIZE) {
  const [size, setSize] = useState(initialSize)
  const [requested, setPage] = useState(0)
  const all = rows ?? []
  const totalPages = Math.max(1, Math.ceil(all.length / size))
  // 행이 줄어(필터·삭제) 현재 페이지가 사라지면 마지막 페이지를 보여 준다
  const page = Math.min(requested, totalPages - 1)
  const pageRows = all.slice(page * size, page * size + size)
  const props: PagingProps = {
    page,
    totalPages,
    totalElements: all.length,
    size,
    onPage: setPage,
    onSize: (s) => {
      setSize(s)
      setPage(0)
    },
  }
  return { rows: pageRows, props, setPage }
}
