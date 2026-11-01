import type { DailyReport, JournalReport, Provision, ReportLine, RollRate } from '@/api/reportTypes'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { EmptyRow, SortTH, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDateTime, formatMoney, formatNumber } from '@/lib/format'
import { useSort } from '@/lib/useSort'
import { cn } from '@/lib/utils'

/** 일보 구역 표시 순서. 모르는 구역(새 항목)은 뒤에 붙인다 */
const SECTION_ORDER = ['DISBURSEMENT', 'CASH', 'ALLOCATION', 'ACCRUAL', 'POSTING', 'BALANCE', 'STATUS', 'BUCKET']
/** 건수가 의미 없는 구역 */
const AMOUNT_ONLY = new Set(['ALLOCATION', 'ACCRUAL'])

export function SnapshotBadge({ closed }: { closed: boolean }) {
  return closed ? (
    <Badge className="border-emerald-200 bg-emerald-50 text-emerald-700">마감 스냅샷</Badge>
  ) : (
    <Badge className="border-amber-200 bg-amber-50 text-amber-800">미리보기 (마감 전)</Badge>
  )
}

function BalanceCheck({ debit, credit }: { debit: number; credit: number }) {
  const ok = debit === credit
  return (
    <Badge
      data-testid="journal-check"
      className={ok ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-red-200 bg-red-50 text-red-700'}
    >
      {ok ? '차변 = 대변' : `차변 ≠ 대변 (차이 ${formatMoney(debit - credit)})`}
    </Badge>
  )
}

export function groupLines(lines: ReportLine[]): { section: string; label: string; lines: ReportLine[] }[] {
  const map = new Map<string, { section: string; label: string; lines: ReportLine[] }>()
  for (const l of lines) {
    const g = map.get(l.section) ?? { section: l.section, label: l.sectionLabel, lines: [] }
    g.lines.push(l)
    map.set(l.section, g)
  }
  const rank = (s: string) => {
    const i = SECTION_ORDER.indexOf(s)
    return i < 0 ? SECTION_ORDER.length : i
  }
  return [...map.values()].sort((a, b) => rank(a.section) - rank(b.section))
}

export function DailyReportView({ data }: { data: DailyReport }) {
  const groups = groupLines(data.lines)
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <SnapshotBadge closed={data.closed} />
        {data.snapshotAt && <span className="text-muted-foreground">저장 {formatDateTime(data.snapshotAt)}</span>}
        <span>
          분개 차변 {formatMoney(data.journalDebit)} / 대변 {formatMoney(data.journalCredit)}
        </span>
        <BalanceCheck debit={data.journalDebit} credit={data.journalCredit} />
        {data.unmappedCount > 0 && (
          <Badge className="border-red-200 bg-red-50 text-red-700">미매핑 거래유형 {data.unmappedCount}</Badge>
        )}
      </div>
      {!data.balancesAvailable && (
        <Alert tone="warn">이 날은 보고서 기능 도입 전에 마감되어 잔액·상태·연체 구간 스냅샷이 없습니다. 원장 거래 행만 보여 줍니다.</Alert>
      )}
      <div className="grid gap-3 lg:grid-cols-2">
        {groups.map((g) => (
          <section key={g.section} aria-label={g.label} className="rounded-md border">
            <h3 className="border-b bg-muted px-2 py-1 text-xs font-semibold">{g.label}</h3>
            <Table>
              <THead>
                <TR>
                  <TH>항목</TH>
                  {!AMOUNT_ONLY.has(g.section) && <TH className="text-right">건수</TH>}
                  <TH className="text-right">금액</TH>
                </TR>
              </THead>
              <TBody>
                {g.lines.map((l) => (
                  <TR key={l.item}>
                    <TD title={l.item}>{l.label}</TD>
                    {!AMOUNT_ONLY.has(g.section) && <TD className="num">{formatNumber(l.count)}</TD>}
                    <TD className={cn('num', l.amount < 0 && 'text-destructive')}>{formatMoney(l.amount)}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </section>
        ))}
      </div>
      <p className="text-[11px] text-muted-foreground">
        거래는 처리일 기준입니다. 당일 취소는 원 거래와 상계되어 나타나지 않고(R27), 마감 후 취소는 처리일의 “정정”으로
        나옵니다(R28). 연체 구간은 R24에 따라 다음 날 날짜로 판정된 값입니다.
      </p>
    </div>
  )
}

function pct(v: number) {
  return `${Number(v).toFixed(1)}%`
}

export function RollRateView({ data }: { data: RollRate }) {
  const header = data.rows[0]?.cells ?? []
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span>
          {data.previousMonth} 말 → {data.month} 말 (기준일 {data.asOf})
        </span>
        <SnapshotBadge closed={data.currentClosed} />
      </div>
      {!data.previousAvailable && (
        <Alert tone="warn">{data.previousMonth} 월마감 스냅샷이 없어 모든 계약이 “신규” 행에 나옵니다.</Alert>
      )}
      <Table>
        <THead>
          <TR>
            <TH>전월 말 구간</TH>
            <TH className="text-right">건수</TH>
            <TH className="text-right">잔액</TH>
            {header.map((c) => (
              <TH key={c.to} className="text-right">
                → {c.toLabel}
              </TH>
            ))}
            <TH className="text-right">악화율</TH>
            <TH className="text-right">개선율</TH>
          </TR>
        </THead>
        <TBody>
          {data.rows.map((r) => (
            <TR key={r.from}>
              <TD className="font-medium">{r.fromLabel}</TD>
              <TD className="num">{formatNumber(r.count)}</TD>
              <TD className="num">{formatMoney(r.balance)}</TD>
              {r.cells.map((c) => (
                <TD
                  key={c.to}
                  className={cn('num', c.to === r.from && 'bg-muted/60')}
                  title={`${formatNumber(c.count)}건 · ${formatMoney(c.balance)} · 건수 기준 ${pct(c.countRate)}`}
                >
                  {c.count === 0 ? '-' : r.from === 'NEW' ? `${formatNumber(c.count)}건` : pct(c.balanceRate)}
                </TD>
              ))}
              <TD className="num">{r.from === 'NEW' ? '-' : pct(r.worsenRate)}</TD>
              <TD className="num">{r.from === 'NEW' ? '-' : pct(r.cureRate)}</TD>
            </TR>
          ))}
        </TBody>
      </Table>
      <p className="text-[11px] text-muted-foreground">
        비율 = 전월 말 구간 잔액 중 당월 말 각 구간으로 간 잔액(R66). 셀에 마우스를 올리면 건수와 금액이 보입니다.
      </p>
    </div>
  )
}

export function ProvisionView({ data }: { data: Provision }) {
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span>
          {data.month ? `${data.month} 월마감` : '현재 (마지막 마감 직후 잔액)'} · 기준일 {data.asOf}
        </span>
        <SnapshotBadge closed={data.closed} />
      </div>
      <Table>
        <THead>
          <TR>
            <TH>구간</TH>
            <TH className="text-right">건수</TH>
            <TH className="text-right">채권 잔액</TH>
            <TH className="text-right">적립률</TH>
            <TH className="text-right">충당금</TH>
          </TR>
        </THead>
        <TBody>
          {data.rows.map((r) => (
            <TR key={r.bucket}>
              <TD>{r.label}</TD>
              <TD className="num">{formatNumber(r.count)}</TD>
              <TD className="num">{formatMoney(r.claim)}</TD>
              <TD className="num">{Number(r.rate).toFixed(2)}%</TD>
              <TD className="num">{formatMoney(r.provision)}</TD>
            </TR>
          ))}
          <TR className="font-semibold">
            <TD>합계</TD>
            <TD className="num">{formatNumber(data.count)}</TD>
            <TD className="num">{formatMoney(data.claim)}</TD>
            <TD />
            <TD className="num" data-testid="provision-total">
              {formatMoney(data.provision)}
            </TD>
          </TR>
        </TBody>
      </Table>
      <p className="text-[11px] text-muted-foreground">
        내부 관리용 기초 집계입니다(R67). 채권 잔액 = 원금 + 확정·미납 이자 + 미회수 비용 + 양수 전 이자, 구간 합계 ×
        적립률(원 미만 절사). 적립률은 가상 값입니다.
      </p>
    </div>
  )
}

const LINE_LIMIT = 500

export function JournalView({ data }: { data: JournalReport }) {
  // 계정별 합계 표는 계정 순서·합계 행이 고정이라 정렬하지 않는다. 거래별 분개 행만 정렬 (정렬 후 앞 LINE_LIMIT행)
  const { sorted, header } = useSort(data.lines, {
    entry: (l) => l.entryId,
    accountNo: (l) => l.accountNo,
    posting: (l) => l.postingLabel,
    account: (l) => l.accountCode,
    // 빈 칸(0)은 방향과 관계없이 뒤로
    debit: (l) => l.debit || null,
    credit: (l) => l.credit || null,
    memo: (l) => l.memo,
  })
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <SnapshotBadge closed={data.closed} />
        <BalanceCheck debit={data.debit} credit={data.credit} />
        {data.closed && !data.matchesSnapshot && (
          <Badge className="border-amber-200 bg-amber-50 text-amber-800">
            현재 매핑으로 다시 만든 분개가 마감 일계와 다릅니다
          </Badge>
        )}
      </div>
      {data.unmapped.length > 0 && (
        <Alert tone="error">
          매핑 규칙이 없는 거래유형:{' '}
          {data.unmapped.map((u) => `${u.postingType} ${formatNumber(u.count)}건 ${formatMoney(u.amount)}`).join(', ')}
          {' '}— 미결산 계정으로 분개했습니다 (R68).
        </Alert>
      )}
      <Table>
        <THead>
          <TR>
            <TH>계정</TH>
            <TH>계정명</TH>
            <TH className="text-right">차변</TH>
            <TH className="text-right">대변</TH>
          </TR>
        </THead>
        <TBody>
          {data.totals.length === 0 && <EmptyRow colSpan={4} />}
          {data.totals.map((t) => (
            <TR key={t.accountCode}>
              <TD className="font-mono">{t.accountCode}</TD>
              <TD>{t.accountName}</TD>
              <TD className="num">{formatMoney(t.debit)}</TD>
              <TD className="num">{formatMoney(t.credit)}</TD>
            </TR>
          ))}
          <TR className="font-semibold">
            <TD colSpan={2}>합계</TD>
            <TD className="num">{formatMoney(data.debit)}</TD>
            <TD className="num">{formatMoney(data.credit)}</TD>
          </TR>
        </TBody>
      </Table>
      <details className="text-xs">
        <summary className="cursor-pointer">거래별 분개 {formatNumber(data.lines.length)}행</summary>
        <Table className="mt-2">
          <THead>
            <TR>
              <SortTH {...header('entry')}>원장</SortTH>
              <SortTH {...header('accountNo')}>계약번호</SortTH>
              <SortTH {...header('posting')}>거래유형</SortTH>
              <SortTH {...header('account')}>계정</SortTH>
              <SortTH {...header('debit')} className="text-right">
                차변
              </SortTH>
              <SortTH {...header('credit')} className="text-right">
                대변
              </SortTH>
              <SortTH {...header('memo')}>적요</SortTH>
            </TR>
          </THead>
          <TBody>
            {sorted.slice(0, LINE_LIMIT).map((l, i) => (
              <TR key={`${l.entryId}-${i}`} className={cn(l.unmapped && 'text-destructive')}>
                <TD>#{l.entryId}</TD>
                <TD>{l.accountNo}</TD>
                <TD>{l.postingLabel}</TD>
                <TD>
                  {l.accountCode} {l.accountName}
                </TD>
                <TD className="num">{l.debit ? formatMoney(l.debit) : ''}</TD>
                <TD className="num">{l.credit ? formatMoney(l.credit) : ''}</TD>
                <TD>{l.memo}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
        {data.lines.length > LINE_LIMIT && (
          <p className="mt-1 text-muted-foreground">앞 {LINE_LIMIT}행만 보여 줍니다. 전체는 엑셀로 내려받으세요.</p>
        )}
      </details>
    </div>
  )
}
