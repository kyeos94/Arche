import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { endpoints, queryKeys } from '@/api/endpoints'
import { type Underwriter, origination, originationKeys } from '@/api/origination'
import { PagedList } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { useAuth } from '@/features/auth/auth'
import { formatDateTime } from '@/lib/format'
import { usePaging } from '@/lib/usePaging'
import { useSort } from '@/lib/useSort'

function ProfileEditor({ u, codes }: { u: Underwriter; codes: string[] }) {
  const client = useQueryClient()
  const [products, setProducts] = useState<string[]>(u.allProducts ? codes : u.productCodes)
  const [available, setAvailable] = useState(u.available)
  const save = useMutation({
    mutationFn: () => origination.saveProfile(u.staffId, { productCodes: products, available }),
    onSuccess: () => client.invalidateQueries({ queryKey: originationKeys.underwriters }),
  })
  return (
    <div className="flex flex-wrap items-center gap-2">
      {codes.map((c) => (
        <label key={c} className="flex items-center gap-1 text-[11px]">
          <input
            type="checkbox"
            checked={products.includes(c)}
            onChange={(e) => setProducts(e.target.checked ? [...products, c] : products.filter((p) => p !== c))}
          />
          {c}
        </label>
      ))}
      <label className="flex items-center gap-1 text-[11px]">
        <input type="checkbox" checked={!available} onChange={(e) => setAvailable(!e.target.checked)} /> 부재
      </label>
      <Button size="sm" variant="outline" disabled={save.isPending} onClick={() => save.mutate()}>
        저장
      </Button>
      <ErrorMessage error={save.error} />
    </div>
  )
}

/** 심사자 배정 현황 (정책서 7장, R55): 미처리 건수·마지막 배정·담당 상품·부재 */
export function UnderwritersPage() {
  const { can } = useAuth()
  const q = useQuery({ queryKey: originationKeys.underwriters, queryFn: origination.underwriters })
  const products = useQuery({ queryKey: queryKeys.products, queryFn: endpoints.products })
  const codes = products.data?.map((p) => p.code).filter((c) => c !== 'N1') ?? []
  const editable = can('application.assign')
  const { sorted, header } = useSort(q.data?.underwriters, {
    name: (u) => u.name,
    role: (u) => (u.role === 'UNDERWRITER' ? '심사역' : '심사팀장'),
    pending: (u) => u.pending,
    lastAssigned: (u) => u.lastAssignedAt,
    products: (u) => (u.allProducts ? '전 상품' : u.productCodes.join(', ') || '없음'),
    status: (u) => (u.available ? '배정 가능' : '부재'),
  })
  const paging = usePaging(sorted)
  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">심사자 배정</h1>
      <Card>
        <CardHeader>
          <CardTitle>배정 전략: {q.data?.strategy === 'round-robin' ? '라운드로빈' : '처리 건수 균등 (정책서 7장)'}</CardTitle>
        </CardHeader>
        <CardContent className="text-[11px] text-muted-foreground">
          담당 상품이 맞고 배정 가능한 심사역 중 미처리(배정 + 심사 중) 건수가 가장 적은 사람, 동률이면 마지막 배정이 오래된
          순입니다. 프로필이 없는 심사역은 전 상품 담당으로 봅니다. 수동 배정·재배정과 프로필 관리는 심사팀장·본부장입니다.
        </CardContent>
      </Card>
      <ErrorMessage error={q.error} />
      <PagedList paging={paging.props}>
        <Table>
          <THead>
            <TR>
              <SortTH {...header('name')}>이름</SortTH>
              <SortTH {...header('role')}>역할</SortTH>
              <SortTH {...header('pending')} className="text-right">
                미처리
              </SortTH>
              <SortTH {...header('lastAssigned')}>마지막 배정</SortTH>
              <SortTH {...header('products')}>담당 상품</SortTH>
              <SortTH {...header('status')}>상태</SortTH>
              {editable && <TH>프로필</TH>}
            </TR>
          </THead>
          <TBody>
            {q.data?.underwriters.length === 0 && <EmptyRow colSpan={editable ? 7 : 6} />}
            {paging.rows.map((u) => (
              <TR key={u.staffId}>
                <TD>
                  {u.name} <span className="text-muted-foreground">({u.loginId})</span>
                </TD>
                <TD>{u.role === 'UNDERWRITER' ? '심사역' : '심사팀장'}</TD>
                <TD className="num">{u.pending}</TD>
                <TD>{formatDateTime(u.lastAssignedAt)}</TD>
                <TD>{u.allProducts ? '전 상품' : u.productCodes.join(', ') || '없음'}</TD>
                <TD>{u.available ? '배정 가능' : '부재'}</TD>
                {editable && (
                  <TD>
                    <ProfileEditor key={`${u.staffId}-${u.productCodes.join()}-${u.available}`} u={u} codes={codes} />
                  </TD>
                )}
              </TR>
            ))}
          </TBody>
        </Table>
      </PagedList>
    </div>
  )
}
