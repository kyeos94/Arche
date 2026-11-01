import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useState } from 'react'
import { type HolidayYear, adminConsole, adminConsoleKeys } from '@/api/adminConsole'
import { endpoints, queryKeys } from '@/api/endpoints'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { EmptyRow, TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'
import { formatDate, formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

/** 'YYYY-MM-DD' (시간대 영향을 받지 않도록 UTC로만 계산) */
function iso(year: number, month: number, day: number): string {
  return new Date(Date.UTC(year, month, day)).toISOString().slice(0, 10)
}

/** 한 달의 칸: 앞쪽 빈칸(null) + 날짜 */
export function monthCells(year: number, month: number): (string | null)[] {
  const first = new Date(Date.UTC(year, month, 1)).getUTCDay()
  const days = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
  return [...Array<null>(first).fill(null), ...Array.from({ length: days }, (_, i) => iso(year, month, i + 1))]
}

function isWeekend(date: string): boolean {
  const d = new Date(`${date}T00:00:00Z`).getUTCDay()
  return d === 0 || d === 6
}

/**
 * 휴일 관리 (R73): 달력에서 평일을 누르면 휴일로 지정, 지정한 휴일을 누르면 해제. 누르는 즉시 저장된다.
 * 바꿀 수 있는 날짜는 업무일자 다음 날부터 3년 뒤까지의 평일 (서버가 다시 검사).
 */
export function HolidayAdminPage() {
  const bd = useQuery({
    queryKey: queryKeys.businessDate,
    queryFn: endpoints.businessDate,
  })
  const [picked, setPicked] = useState<number | null>(null)
  const year = picked ?? (bd.data ? Number(bd.data.businessDate.slice(0, 4)) : null)
  return (
    <div className="space-y-3">
      {year === null ? <ErrorMessage error={bd.error} /> : <HolidayCalendar year={year} onYear={setPicked} />}
      <HolidayChanges />
    </div>
  )
}

function HolidayCalendar({ year, onYear }: { year: number; onYear: (y: number) => void }) {
  const client = useQueryClient()
  const data = useQuery({
    queryKey: adminConsoleKeys.holidays(year),
    queryFn: () => adminConsole.holidays(year),
  })
  const [name, setName] = useState('임시휴일')
  const [done, setDone] = useState<string | null>(null)
  const saved = async (result: HolidayYear, message: string) => {
    client.setQueryData(adminConsoleKeys.holidays(year), result)
    setDone(message)
    await client.invalidateQueries({
      queryKey: adminConsoleKeys.holidayChanges,
    })
  }
  const add = useMutation({
    mutationFn: (date: string) => adminConsole.addHoliday(date, name.trim()),
    onSuccess: (r, date) => saved(r, `${formatDate(date)}을 휴일(${name.trim()})로 지정했습니다.`),
  })
  const remove = useMutation({
    mutationFn: (date: string) => adminConsole.removeHoliday(date),
    onSuccess: (r, date) => saved(r, `${formatDate(date)} 휴일을 해제했습니다. 이제 영업일입니다.`),
  })
  const busy = add.isPending || remove.isPending
  const y = data.data
  const names = new Map(y?.holidays.map((h) => [h.date, h.name]))
  const editable = (date: string) => !!y && date >= y.editableFrom && date <= y.editableTo && !isWeekend(date)

  const click = (date: string) => {
    setDone(null)
    add.reset()
    remove.reset()
    if (names.has(date)) remove.mutate(date)
    else add.mutate(date)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>휴일 관리</CardTitle>
        <div className="flex items-center gap-1">
          <Button size="sm" variant="outline" aria-label="이전 연도" onClick={() => onYear(year - 1)}>
            ◀
          </Button>
          <span className="w-16 text-center font-semibold tabular-nums">{year}년</span>
          <Button size="sm" variant="outline" aria-label="다음 연도" onClick={() => onYear(year + 1)}>
            ▶
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-3 text-xs">
        <p className="text-muted-foreground">
          평일을 누르면 휴일로 지정되고, 빨간 휴일을 누르면 해제됩니다. 누르는 즉시 저장됩니다. 토·일요일은 항상
          휴일이고, 업무일자(
          {y ? formatDate(y.businessDate) : '-'})와 그 이전은 이미 마감·계산에 쓰여 바꿀 수 없습니다. 바뀐 휴일은 다음
          입금 처리·일마감 때부터 납부 기한(휴일 유예)·연체 판정·기한이익상실 통지 날짜 계산에 반영됩니다. 이미 보낸
          통보서의 날짜와 달라질 수 있으니 통지 기간 안의 날짜를 바꿀 때는 재통지를 검토하세요. (R73)
        </p>
        <label className="flex flex-wrap items-center gap-2">
          <span className="text-muted-foreground">새로 지정할 휴일 이름</span>
          <Input
            aria-label="휴일 이름"
            className="w-48"
            maxLength={50}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <div className="flex flex-wrap gap-3 text-muted-foreground">
          <Legend className="border-red-300 bg-red-100 text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
            지정 휴일
          </Legend>
          <Legend className="bg-muted text-red-500">토·일요일</Legend>
          <Legend className="ring-2 ring-primary">업무일자</Legend>
          <Legend className="opacity-40">바꿀 수 없음</Legend>
        </div>
        <ErrorMessage error={data.error ?? add.error ?? remove.error} />
        {done && <Alert tone="success">{done}</Alert>}
        {y && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {Array.from({ length: 12 }, (_, m) => (
              <section key={m} className="rounded border p-2" aria-label={`${m + 1}월`}>
                <h3 className="mb-1 text-center font-semibold">{m + 1}월</h3>
                <div className="grid grid-cols-7 gap-0.5 text-center">
                  {WEEKDAYS.map((w, i) => (
                    <div
                      key={w}
                      className={cn(
                        'py-0.5 text-[11px]',
                        i === 0 || i === 6 ? 'text-red-500' : 'text-muted-foreground',
                      )}
                    >
                      {w}
                    </div>
                  ))}
                  {monthCells(year, m).map((date, i) =>
                    date === null ? (
                      <div key={`blank-${i}`} />
                    ) : (
                      <DayCell
                        key={date}
                        date={date}
                        holiday={names.get(date)}
                        businessDate={date === y.businessDate}
                        editable={editable(date)}
                        busy={busy}
                        onClick={() => click(date)}
                      />
                    ),
                  )}
                </div>
              </section>
            ))}
          </div>
        )}
        {y && (
          <div>
            <h3 className="mb-1 font-semibold">
              {year}년 지정 휴일 {y.holidays.length}일
            </h3>
            <ul className="flex flex-wrap gap-x-4 gap-y-0.5 text-muted-foreground">
              {y.holidays.map((h) => (
                <li key={h.date}>
                  <span className="tabular-nums">{formatDate(h.date)}</span> {h.name}
                </li>
              ))}
              {y.holidays.length === 0 && <li>토·일요일 외에 지정한 휴일이 없습니다</li>}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function DayCell({
  date,
  holiday,
  businessDate,
  editable,
  busy,
  onClick,
}: {
  date: string
  holiday: string | undefined
  businessDate: boolean
  editable: boolean
  busy: boolean
  onClick: () => void
}) {
  const weekend = isWeekend(date)
  const day = Number(date.slice(8))
  const state = holiday ? `휴일 ${holiday}` : weekend ? '주말' : '영업일'
  return (
    <button
      type="button"
      aria-label={`${date} ${state}`}
      title={holiday ?? (weekend ? '토·일요일' : editable ? '눌러서 휴일로 지정' : undefined)}
      disabled={!editable || busy}
      onClick={onClick}
      className={cn(
        'h-7 rounded border border-transparent tabular-nums',
        holiday &&
          'border-red-300 bg-red-100 font-semibold text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300',
        !holiday && weekend && 'bg-muted text-red-500',
        businessDate && 'ring-2 ring-primary',
        editable ? 'cursor-pointer hover:border-foreground/40' : 'cursor-default opacity-40',
      )}
    >
      {day}
    </button>
  )
}

function Legend({ className, children }: { className: string; children: ReactNode }) {
  return (
    <span className="flex items-center gap-1">
      <span className={cn('inline-block size-4 rounded border border-transparent', className)} />
      {children}
    </span>
  )
}

function HolidayChanges() {
  const changes = useQuery({
    queryKey: adminConsoleKeys.holidayChanges,
    queryFn: () => adminConsole.holidayChanges(100),
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>휴일 변경 이력</CardTitle>
      </CardHeader>
      <CardContent>
        <ErrorMessage error={changes.error} />
        <Table>
          <THead>
            <TR>
              <TH>일시</TH>
              <TH>업무일자</TH>
              <TH>날짜</TH>
              <TH>변경</TH>
              <TH>휴일 이름</TH>
              <TH>변경한 직원</TH>
            </TR>
          </THead>
          <TBody>
            {changes.data?.length === 0 && <EmptyRow colSpan={6}>바꾼 휴일이 없습니다</EmptyRow>}
            {changes.data?.map((c) => (
              <TR key={c.id}>
                <TD className="whitespace-nowrap">{formatDateTime(c.changedAt)}</TD>
                <TD>{formatDate(c.businessDate)}</TD>
                <TD className="tabular-nums">{formatDate(c.date)}</TD>
                <TD>{c.action === 'ADD' ? '휴일 지정' : '휴일 해제'}</TD>
                <TD>{c.name}</TD>
                <TD>{c.changedBy}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </CardContent>
    </Card>
  )
}
