import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { ReportKind } from '@/api/reportTypes'
import { ErrorMessage } from '@/components/ui/alert'
import { Input, Label, Select } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useAuth } from '@/features/auth/auth'
import { ExcelDownloadDialog } from './ExcelDownloadDialog'
import { DailyReportView, JournalView, ProvisionView, RollRateView } from './views'

const TABS: { value: string; label: string; kind: ReportKind }[] = [
  { value: 'daily', label: '일보', kind: 'DAILY' },
  { value: 'roll-rate', label: '연체 전이율', kind: 'ROLL_RATE' },
  { value: 'provision', label: '대손충당금', kind: 'PROVISION' },
  { value: 'journal', label: '회계 분개', kind: 'JOURNAL' },
]

/**
 * 보고서 (guide 13.3): 일보, 연체 현황·전이율, 대손충당금 기초 집계, 회계 분개. 탭·날짜·월은 URL과 동기화.
 */
export function ReportsPage() {
  const { can } = useAuth()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'daily'
  const date = params.get('date') ?? ''
  const month = params.get('month') ?? ''
  const { data: bd } = useQuery({ queryKey: queryKeys.businessDate, queryFn: endpoints.businessDate })
  const today = bd?.businessDate ?? ''
  const months = useQuery({ queryKey: queryKeys.reportMonths, queryFn: endpoints.reportMonths })

  const update = (next: Record<string, string>) => {
    const merged = new URLSearchParams(params)
    for (const [k, v] of Object.entries(next)) {
      if (v) merged.set(k, v)
      else merged.delete(k)
    }
    setParams(merged)
  }

  const daily = useQuery({
    queryKey: queryKeys.reportDaily(date),
    queryFn: () => endpoints.reportDaily(date),
    enabled: tab === 'daily',
  })
  const journal = useQuery({
    queryKey: queryKeys.reportJournal(date),
    queryFn: () => endpoints.reportJournal(date),
    enabled: tab === 'journal',
  })
  const roll = useQuery({
    queryKey: queryKeys.reportRollRate(month),
    queryFn: () => endpoints.reportRollRate(month),
    enabled: tab === 'roll-rate',
  })
  const provision = useQuery({
    queryKey: queryKeys.reportProvision(month),
    queryFn: () => endpoints.reportProvision(month),
    enabled: tab === 'provision',
  })

  const current = TABS.find((t) => t.value === tab) ?? TABS[0]
  const byDate = tab === 'daily' || tab === 'journal'
  const shownDate = date || today
  const summary = byDate
    ? `${current.label} ${shownDate}`
    : `${current.label} ${month || (tab === 'provision' ? '현재' : '당월')}`

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <h1 className="text-base font-semibold">보고서</h1>
        <div className="flex flex-wrap items-end gap-2">
          {byDate ? (
            <div className="flex flex-col gap-1">
              <Label htmlFor="reportDate">업무일자</Label>
              <Input
                id="reportDate"
                type="date"
                className="w-40"
                max={today || undefined}
                value={shownDate}
                onChange={(e) => update({ date: e.target.value === today ? '' : e.target.value })}
              />
            </div>
          ) : (
            <div className="flex flex-col gap-1">
              <Label htmlFor="reportMonth">월마감</Label>
              <Select
                id="reportMonth"
                className="w-44"
                value={month}
                onChange={(e) => update({ month: e.target.value })}
              >
                <option value="">{tab === 'provision' ? '현재 (마지막 마감)' : '당월 (미리보기)'}</option>
                {months.data?.map((m) => (
                  <option key={m.month} value={m.month}>
                    {m.month} (기준일 {m.asOf})
                  </option>
                ))}
              </Select>
            </div>
          )}
          {can('excel.download') && (
            <ExcelDownloadDialog
              title={`${current.label} 엑셀 다운로드`}
              summary={summary}
              onDownload={(reason) =>
                endpoints.exportReport({
                  kind: current.kind,
                  date: byDate ? date || undefined : undefined,
                  month: byDate ? undefined : month || undefined,
                  reason,
                })
              }
            />
          )}
        </div>
      </div>
      <Tabs value={current.value} onValueChange={(v) => update({ tab: v === 'daily' ? '' : v })}>
        <TabsList>
          {TABS.map((t) => (
            <TabsTrigger key={t.value} value={t.value}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="daily">
          <ErrorMessage error={daily.error} />
          {daily.data && <DailyReportView data={daily.data} />}
        </TabsContent>
        <TabsContent value="roll-rate">
          <ErrorMessage error={roll.error} />
          {roll.data && <RollRateView data={roll.data} />}
        </TabsContent>
        <TabsContent value="provision">
          <ErrorMessage error={provision.error} />
          {provision.data && <ProvisionView data={provision.data} />}
        </TabsContent>
        <TabsContent value="journal">
          <ErrorMessage error={journal.error} />
          {journal.data && <JournalView data={journal.data} />}
        </TabsContent>
      </Tabs>
    </div>
  )
}
