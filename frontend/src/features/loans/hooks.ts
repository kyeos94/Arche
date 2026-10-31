import { useQueryClient } from '@tanstack/react-query'

/** 계약을 바꾼 뒤: 그 계약의 모든 쿼리 + 목록·미확인 입금·정정 목록을 다시 읽는다 */
export function useInvalidateLoan() {
  const client = useQueryClient()
  return async (loanId?: number) => {
    await Promise.all([
      loanId !== undefined
        ? client.invalidateQueries({ queryKey: ['loan', loanId] })
        : client.invalidateQueries({ queryKey: ['loan'] }),
      client.invalidateQueries({ queryKey: ['loans'] }),
      client.invalidateQueries({ queryKey: ['unmatched'] }),
      client.invalidateQueries({ queryKey: ['corrections'] }),
    ])
  }
}

export function today(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
