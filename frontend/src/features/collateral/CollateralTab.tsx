import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import type { z } from 'zod'
import type { LoanDetail } from '@/api/types'
import { PagedList } from '@/components/Pagination'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { Can } from '@/features/auth/auth'
import { type Collateral, sliceApi, sliceKeys, sliceLabel, sliceTones } from '@/features/communication/api'
import { collateralSchema, guarantorSchema } from '@/features/communication/schemas'
import { formatDate, formatMoney } from '@/lib/format'
import { usePaging } from '@/lib/usePaging'
import { useSort } from '@/lib/useSort'
import { cn } from '@/lib/utils'

/** 시세표의 차종 구분 (Mock 고정 시세표, ADR 0008) */
const VEHICLE_CLASSES = ['경차', '소형', '중형', '대형', 'SUV', '승합', '화물']

/** 계약 상세 > 담보·보증인 탭 (guide 13.2): 담보·시세 이력·인정가액·LTV(R60), 보증인 (마스킹) */
export function CollateralTab({ loan }: { loan: LoanDetail }) {
  return (
    <div className="space-y-3">
      <CollateralCard loan={loan} />
      <GuarantorCard loan={loan} />
    </div>
  )
}

function CollateralCard({ loan }: { loan: LoanDetail }) {
  const client = useQueryClient()
  const q = useQuery({ queryKey: sliceKeys.collaterals(loan.id), queryFn: () => sliceApi.collaterals(loan.id) })
  const refresh = () => client.invalidateQueries({ queryKey: sliceKeys.collaterals(loan.id) })
  const valuate = useMutation({ mutationFn: (cid: number) => sliceApi.valuate(loan.id, cid), onSuccess: refresh })
  const release = useMutation({
    mutationFn: (cid: number) => sliceApi.releaseCollateral(loan.id, cid),
    onSuccess: refresh,
  })
  const s = q.data?.summary
  const { sorted, header } = useSort(q.data?.collaterals, {
    type: (c) => sliceLabel(c.type),
    description: (c) => c.description,
    identifier: (c) => c.identifier,
    seniorClaim: (c) => c.seniorClaim,
    lienAmount: (c) => c.lienAmount,
    price: (c) => c.latest?.price,
    valuedOn: (c) => c.latest?.valuedOn,
    recognizedValue: (c) => c.recognizedValue,
    status: (c) => sliceLabel(c.status),
  })
  const paging = usePaging(sorted)
  return (
    <Card>
      <CardHeader>
        <CardTitle>담보 — 인정가액 = 시세 × (차량 70% / 부동산 60%) − 선순위</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <ErrorMessage error={q.error} />
        {s && (
          <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs md:grid-cols-6" aria-label="담보 요약">
            <dt className="text-muted-foreground">시세 합계</dt>
            <dd className="num">{formatMoney(s.totalPrice)}</dd>
            <dt className="text-muted-foreground">인정가액 합계</dt>
            <dd className="num">{formatMoney(s.totalRecognized)}</dd>
            <dt className="text-muted-foreground">LTV (잔액+선순위 ÷ 시세)</dt>
            <dd className={cn('num font-semibold', s.ltv !== null && s.ltv > 100 && 'text-red-700')}>
              {s.ltv === null ? '시세 없음' : `${s.ltv}%`}
            </dd>
            <dt className="text-muted-foreground">원금 잔액</dt>
            <dd className="num">{formatMoney(s.principalBalance)}</dd>
            <dt className="text-muted-foreground">선순위 합계</dt>
            <dd className="num">{formatMoney(s.totalSenior)}</dd>
            <dt className="text-muted-foreground">담보 여유</dt>
            <dd className={cn('num', s.margin < 0 && 'text-red-700')}>
              {formatMoney(s.margin)} {s.margin < 0 && '(부족)'}
            </dd>
          </dl>
        )}
        {s && s.staleCount > 0 && <Alert tone="warn">시세 조회 후 90일이 지난 담보가 {s.staleCount}건 있습니다. 재조회하세요.</Alert>}
        <PagedList paging={paging.props}>
          <Table>
            <THead>
              <TR>
                <SortTH {...header('type')}>유형</SortTH>
                <SortTH {...header('description')}>설명</SortTH>
                <SortTH {...header('identifier')}>식별</SortTH>
                <SortTH {...header('seniorClaim')} className="text-right">
                  선순위
                </SortTH>
                <SortTH {...header('lienAmount')} className="text-right">
                  근저당 설정액
                </SortTH>
                <SortTH {...header('price')} className="text-right">
                  최근 시세
                </SortTH>
                <SortTH {...header('valuedOn')}>조회일</SortTH>
                <SortTH {...header('recognizedValue')} className="text-right">
                  인정가액
                </SortTH>
                <SortTH {...header('status')}>상태</SortTH>
                <TH />
              </TR>
            </THead>
            <TBody>
              {q.data?.collaterals.length === 0 && <EmptyRow colSpan={10}>등록된 담보가 없습니다</EmptyRow>}
              {paging.rows.map((c) => (
                <CollateralRow
                  key={c.id}
                  c={c}
                  paidOff={loan.status === 'PAID_OFF'}
                  onValuate={() => valuate.mutate(c.id)}
                  onRelease={() => release.mutate(c.id)}
                  busy={valuate.isPending || release.isPending}
                />
              ))}
            </TBody>
          </Table>
        </PagedList>
        <ErrorMessage error={valuate.error ?? release.error} />
        {loan.status !== 'PAID_OFF' && (
          <Can permission="loan.term.change">
            <CollateralForm loanId={loan.id} />
          </Can>
        )}
      </CardContent>
    </Card>
  )
}

function CollateralRow({
  c,
  paidOff,
  onValuate,
  onRelease,
  busy,
}: {
  c: Collateral
  paidOff: boolean
  onValuate: () => void
  onRelease: () => void
  busy: boolean
}) {
  const active = c.status === 'ACTIVE'
  return (
    <TR className={cn(!active && 'text-muted-foreground')}>
      <TD>{sliceLabel(c.type)}</TD>
      <TD>
        {c.description}
        <span className="block text-[11px] text-muted-foreground">
          {c.type === 'VEHICLE' ? `${c.vehicleClass} · ${c.modelYear}년식` : `${c.address} · ${c.areaM2}㎡`}
        </span>
      </TD>
      <TD>{c.identifier ?? '-'}</TD>
      <TD className="num">{formatMoney(c.seniorClaim)}</TD>
      <TD className="num">
        {formatMoney(c.lienAmount)} ({c.lienRank}순위)
      </TD>
      <TD className="num" title={c.latest?.basis}>
        {formatMoney(c.latest?.price)}
      </TD>
      <TD className={cn(c.stale && c.latest && 'text-amber-800')}>{formatDate(c.latest?.valuedOn)}</TD>
      <TD className="num">{formatMoney(c.recognizedValue)}</TD>
      <TD>
        <Badge className={sliceTones[c.status] ?? 'bg-emerald-50 text-emerald-700 border-emerald-200'}>
          {sliceLabel(c.status)}
        </Badge>
      </TD>
      <TD>
        {active && (
          <Can permission="loan.term.change">
            <div className="flex gap-1">
              <Button size="sm" variant="outline" disabled={busy} onClick={onValuate}>
                시세 조회
              </Button>
              {paidOff && (
                <Button size="sm" variant="outline" disabled={busy} onClick={onRelease}>
                  해지
                </Button>
              )}
            </div>
          </Can>
        )}
      </TD>
    </TR>
  )
}

type CollateralIn = z.input<typeof collateralSchema>
type CollateralOut = z.output<typeof collateralSchema>

function CollateralForm({ loanId }: { loanId: number }) {
  const client = useQueryClient()
  const form = useForm<CollateralIn, unknown, CollateralOut>({
    resolver: zodResolver(collateralSchema),
    defaultValues: { type: 'VEHICLE', description: '', vehicleClass: '중형', modelYear: '', seniorClaim: '0', lienAmount: '0' },
  })
  const save = useMutation({
    mutationFn: (v: CollateralOut) => sliceApi.registerCollateral(loanId, v),
    onSuccess: async () => {
      form.reset()
      await client.invalidateQueries({ queryKey: sliceKeys.collaterals(loanId) })
    },
  })
  const type = form.watch('type')
  const e = form.formState.errors
  return (
    <form
      className="flex flex-wrap items-end gap-2 border-t pt-3"
      aria-label="담보 등록"
      onSubmit={form.handleSubmit((v) => save.mutate(v))}
    >
      <Field label="유형" htmlFor="cType">
        <Select id="cType" {...form.register('type')}>
          <option value="VEHICLE">차량</option>
          <option value="REAL_ESTATE">부동산</option>
        </Select>
      </Field>
      <Field label="설명" htmlFor="cDesc" error={e.description?.message}>
        <Input id="cDesc" {...form.register('description')} />
      </Field>
      <Field label={type === 'VEHICLE' ? '차량번호' : '등기 고유번호'} htmlFor="cId">
        <Input id="cId" {...form.register('identifier')} />
      </Field>
      {type === 'VEHICLE' ? (
        <>
          <Field label="차종 구분" htmlFor="cClass" error={e.vehicleClass?.message}>
            <Select id="cClass" {...form.register('vehicleClass')}>
              {VEHICLE_CLASSES.map((v) => (
                <option key={v}>{v}</option>
              ))}
            </Select>
          </Field>
          <Field label="연식" htmlFor="cYear" error={e.modelYear?.message}>
            <Input id="cYear" className="w-20" inputMode="numeric" {...form.register('modelYear')} />
          </Field>
        </>
      ) : (
        <>
          <Field label="소재지" htmlFor="cAddr" error={e.address?.message}>
            <Input id="cAddr" {...form.register('address')} />
          </Field>
          <Field label="전용면적(㎡)" htmlFor="cArea" error={e.areaM2?.message}>
            <Input id="cArea" className="w-24" inputMode="decimal" {...form.register('areaM2')} />
          </Field>
        </>
      )}
      <Field label="선순위 채권액" htmlFor="cSenior">
        <Input id="cSenior" className="w-32" inputMode="numeric" {...form.register('seniorClaim')} />
      </Field>
      <Field label="근저당 설정액" htmlFor="cLien">
        <Input id="cLien" className="w-32" inputMode="numeric" {...form.register('lienAmount')} />
      </Field>
      <Button type="submit" size="sm" disabled={save.isPending}>
        담보 등록
      </Button>
      <div className="w-full">
        <ErrorMessage error={save.error} />
      </div>
    </form>
  )
}

type GuarantorIn = z.input<typeof guarantorSchema>
type GuarantorOut = z.output<typeof guarantorSchema>

function GuarantorCard({ loan }: { loan: LoanDetail }) {
  const client = useQueryClient()
  const q = useQuery({ queryKey: sliceKeys.guarantors(loan.id), queryFn: () => sliceApi.guarantors(loan.id) })
  const refresh = () => client.invalidateQueries({ queryKey: sliceKeys.guarantors(loan.id) })
  const release = useMutation({ mutationFn: (gid: number) => sliceApi.releaseGuarantor(loan.id, gid), onSuccess: refresh })
  const form = useForm<GuarantorIn, unknown, GuarantorOut>({
    resolver: zodResolver(guarantorSchema),
    defaultValues: { name: '', residentNo: '', phone: '', relation: '', guaranteeLimit: '', startDate: loan.businessDate, endDate: '' },
  })
  const save = useMutation({
    mutationFn: (v: GuarantorOut) => sliceApi.registerGuarantor(loan.id, v),
    onSuccess: async () => {
      form.reset()
      await refresh()
    },
  })
  const e = form.formState.errors
  const { sorted, header } = useSort(q.data, {
    name: (g) => g.name,
    relation: (g) => g.relation,
    guaranteeLimit: (g) => g.guaranteeLimit,
    period: (g) => g.startDate,
    status: (g) => sliceLabel(g.status),
  })
  const paging = usePaging(sorted)
  return (
    <Card>
      <CardHeader>
        <CardTitle>보증인 — 식별번호·연락처는 암호화 저장, 화면에는 마스킹</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <ErrorMessage error={q.error} />
        <PagedList paging={paging.props}>
          <Table>
            <THead>
              <TR>
                <SortTH {...header('name')}>이름</SortTH>
                <TH>주민등록번호</TH>
                <TH>연락처</TH>
                <SortTH {...header('relation')}>관계</SortTH>
                <SortTH {...header('guaranteeLimit')} className="text-right">
                  보증 한도
                </SortTH>
                <SortTH {...header('period')}>보증 기간</SortTH>
                <SortTH {...header('status')}>상태</SortTH>
                <TH />
              </TR>
            </THead>
            <TBody>
              {q.data?.length === 0 && <EmptyRow colSpan={8}>등록된 보증인이 없습니다</EmptyRow>}
              {paging.rows.map((g) => (
                <TR key={g.id} className={cn(g.status !== 'ACTIVE' && 'text-muted-foreground')}>
                  <TD>{g.name}</TD>
                  <TD className="font-mono">{g.residentNoMasked}</TD>
                  <TD>{g.phoneMasked ?? '-'}</TD>
                  <TD>{g.relation}</TD>
                  <TD className="num">{formatMoney(g.guaranteeLimit)}</TD>
                  <TD>
                    {formatDate(g.startDate)} ~ {g.endDate ? formatDate(g.endDate) : ''}
                  </TD>
                  <TD>{sliceLabel(g.status)}</TD>
                  <TD>
                    {g.status === 'ACTIVE' && (
                      <Can permission="loan.term.change">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={release.isPending}
                          onClick={() => release.mutate(g.id)}
                        >
                          보증 해지
                        </Button>
                      </Can>
                    )}
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </PagedList>
        <ErrorMessage error={release.error} />
        {loan.status !== 'PAID_OFF' && (
          <Can permission="loan.term.change">
            <form
              className="flex flex-wrap items-end gap-2 border-t pt-3"
              aria-label="보증인 등록"
              onSubmit={form.handleSubmit((v) => save.mutate(v))}
            >
              <Field label="이름" htmlFor="gName" error={e.name?.message}>
                <Input id="gName" {...form.register('name')} />
              </Field>
              <Field label="주민등록번호" htmlFor="gRrn" error={e.residentNo?.message}>
                <Input id="gRrn" autoComplete="off" {...form.register('residentNo')} />
              </Field>
              <Field label="연락처" htmlFor="gPhone">
                <Input id="gPhone" inputMode="tel" {...form.register('phone')} />
              </Field>
              <Field label="관계" htmlFor="gRel" error={e.relation?.message}>
                <Input id="gRel" className="w-24" {...form.register('relation')} />
              </Field>
              <Field label="보증 한도" htmlFor="gLimit" error={e.guaranteeLimit?.message}>
                <Input id="gLimit" className="w-32" inputMode="numeric" {...form.register('guaranteeLimit')} />
              </Field>
              <Field label="시작일" htmlFor="gStart" error={e.startDate?.message}>
                <Input id="gStart" type="date" {...form.register('startDate')} />
              </Field>
              <Field label="종료일" htmlFor="gEnd" error={e.endDate?.message}>
                <Input id="gEnd" type="date" {...form.register('endDate')} />
              </Field>
              <Button type="submit" size="sm" disabled={save.isPending}>
                보증인 등록
              </Button>
              <div className="w-full">
                <ErrorMessage error={save.error} />
              </div>
            </form>
          </Can>
        )}
      </CardContent>
    </Card>
  )
}
