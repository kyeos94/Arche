import { useQuery } from '@tanstack/react-query'
import { endpoints, queryKeys } from '@/api/endpoints'
import { origination, originationKeys } from '@/api/origination'
import { ErrorMessage } from '@/components/ui/alert'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatMoney, formatRate } from '@/lib/format'

const METHOD_LABEL: Record<string, string> = {
  EQUAL_INSTALLMENT: '원리금균등',
  EQUAL_PRINCIPAL: '원금균등',
  BULLET: '만기일시',
  GRACE_THEN_INSTALLMENT: '거치 후 원리금균등',
  REVOLVING: '자유상환',
  ACQUIRED: '매입채권',
}

/** 상품 조회 (정책서 1장) + 상품별 징구 서류 (R54). 변경은 새 버전으로만 (화면에서는 조회만) */
export function ProductsPage() {
  const products = useQuery({ queryKey: queryKeys.products, queryFn: endpoints.products })
  const reqs = useQuery({ queryKey: originationKeys.requirements, queryFn: origination.requirements })
  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">상품</h1>
      <p className="text-[11px] text-muted-foreground">
        상품 설정을 바꾸면 새 버전이 생기고 기존 계약은 실행 당시 버전을 유지합니다. 심사 시 금리는 기본값에서 최대 3.0%p까지
        낮출 수 있습니다.
      </p>
      <ErrorMessage error={products.error ?? reqs.error} />
      <Table>
        <THead>
          <TR>
            <TH>코드</TH>
            <TH>상품명</TH>
            <TH>버전</TH>
            <TH className="text-right">기본 금리</TH>
            <TH className="text-right">한도</TH>
            <TH>기간</TH>
            <TH>상환방식</TH>
            <TH>징구 서류 (* 필수)</TH>
          </TR>
        </THead>
        <TBody>
          {products.data?.length === 0 && <EmptyRow colSpan={8} />}
          {products.data?.map((p) => {
            const extra = p as typeof p & { productVersion?: number; collateralType?: string; prepaymentFeeRate?: number }
            const docs = reqs.data?.filter((r) => r.productCode === p.code) ?? []
            return (
              <TR key={p.id}>
                <TD>{p.code}</TD>
                <TD>{p.name}</TD>
                <TD>v{extra.productVersion ?? 1}</TD>
                <TD className="num">{formatRate(p.defaultRate)}</TD>
                <TD className="num">
                  {formatMoney(p.minAmount)} ~ {formatMoney(p.maxAmount)}
                </TD>
                <TD>
                  {p.minTermMonths}~{p.maxTermMonths}개월
                  {p.maxGraceMonths > 0 && ` (거치 ${p.minGraceMonths}~${p.maxGraceMonths})`}
                </TD>
                <TD>{METHOD_LABEL[p.repaymentMethod] ?? p.repaymentMethod}</TD>
                <TD className="text-muted-foreground">
                  {p.code === 'N1' ? '신청 대상 아님' : docs.map((d) => `${d.label}${d.required ? '*' : ''}`).join(', ')}
                </TD>
              </TR>
            )
          })}
        </TBody>
      </Table>
    </div>
  )
}
