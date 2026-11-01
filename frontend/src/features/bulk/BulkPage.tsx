import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { endpoints, queryKeys } from '@/api/endpoints'
import type { BulkJob } from '@/api/types'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { EmptyRow, SortTH, TBody, TD, THead, TR, Table } from '@/components/ui/table'
import { useInvalidateLoan } from '@/features/loans/hooks'
import { formatNumber } from '@/lib/format'
import { useSort } from '@/lib/useSort'

const rowTone: Record<string, string> = {
  VALID: 'border-sky-200 bg-sky-50 text-sky-700',
  INVALID: 'border-red-200 bg-red-50 text-red-700',
  SUCCEEDED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  FAILED: 'border-red-200 bg-red-50 text-red-700',
}

/** 입금 일괄 등록: 업로드 → 검증(미리보기) → 실행 → 실패 행 다운로드 (guide 12.3) */
export function BulkPage() {
  const [file, setFile] = useState<File | null>(null)
  const [jobId, setJobId] = useState<number | null>(null)
  const invalidate = useInvalidateLoan()
  const upload = useMutation({
    mutationFn: (f: File) => endpoints.uploadBulk(f),
    onSuccess: (job) => setJobId(job.id),
  })
  const job = useQuery({
    queryKey: queryKeys.bulkJob(jobId ?? 0),
    queryFn: () => endpoints.bulkJob(jobId!),
    enabled: jobId !== null,
    refetchInterval: (query) => ((query.state.data as BulkJob | undefined)?.status === 'RUNNING' ? 1000 : false),
  })
  const execute = useMutation({
    mutationFn: () => endpoints.executeBulk(jobId!),
    onSuccess: () => job.refetch(),
  })
  const data = job.data ?? upload.data
  const completed = data?.status === 'COMPLETED'
  // 앞의 200행만 보여 주므로 그 안에서 정렬
  const { sorted, header } = useSort(data?.rows.slice(0, 200), {
    rowNo: (r) => r.rowNo,
    status: (r) => r.status,
    payload: (r) => r.payload,
    message: (r) => r.message,
  })
  useEffect(() => {
    if (completed) void invalidate()
  }, [completed])

  return (
    <div className="space-y-3">
      <h1 className="text-base font-semibold">입금 일괄 등록</h1>
      <Card>
        <CardHeader>
          <CardTitle>엑셀 업로드</CardTitle>
          <a className="text-xs text-primary hover:underline" href={endpoints.bulkTemplateUrl}>
            양식 내려받기
          </a>
        </CardHeader>
        <CardContent className="flex items-center gap-2">
          <Input
            type="file"
            accept=".xlsx"
            className="w-80 py-1"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          <Button disabled={!file || upload.isPending} onClick={() => file && upload.mutate(file)}>
            업로드·검증
          </Button>
          <ErrorMessage error={upload.error} />
        </CardContent>
      </Card>
      {data && (
        <Card>
          <CardHeader>
            <CardTitle>
              {data.fileName} · <span className="font-normal">{data.status}</span>
            </CardTitle>
            <div className="flex gap-2">
              {data.status === 'VALIDATED' && (
                <Button disabled={data.validRows === 0 || execute.isPending} onClick={() => execute.mutate()}>
                  유효 {formatNumber(data.validRows)}건 실행
                </Button>
              )}
              {(data.failedRows > 0 || data.totalRows > data.validRows) && (
                <Button asChild variant="outline">
                  <a href={endpoints.bulkFailuresUrl(data.id)}>실패 행 내려받기</a>
                </Button>
              )}
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            <p className="text-xs">
              전체 {formatNumber(data.totalRows)} · 유효 {formatNumber(data.validRows)} · 성공{' '}
              {formatNumber(data.successRows)} · 실패 {formatNumber(data.failedRows)}
            </p>
            {data.status === 'RUNNING' && (
              <div className="h-2 w-full rounded bg-muted">
                <div
                  className="h-2 rounded bg-primary"
                  style={{
                    width: `${data.validRows ? ((data.successRows + data.failedRows) / data.validRows) * 100 : 0}%`,
                  }}
                />
              </div>
            )}
            {data.status === 'COMPLETED' && <Alert tone="success">일괄 처리가 끝났습니다.</Alert>}
            <ErrorMessage error={execute.error ?? job.error} />
            <Table>
              <THead>
                <TR>
                  <SortTH {...header('rowNo')}>행</SortTH>
                  <SortTH {...header('status')}>상태</SortTH>
                  <SortTH {...header('payload')}>내용</SortTH>
                  <SortTH {...header('message')}>메시지</SortTH>
                </TR>
              </THead>
              <TBody>
                {data.rows.length === 0 && <EmptyRow colSpan={4} />}
                {sorted.map((r) => (
                  <TR key={r.rowNo}>
                    <TD>{r.rowNo}</TD>
                    <TD>
                      <Badge className={rowTone[r.status]}>{r.status}</Badge>
                    </TD>
                    <TD className="text-muted-foreground">{r.payload}</TD>
                    <TD>{r.message ?? ''}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            {data.rows.length > 200 && (
              <p className="text-xs text-muted-foreground">앞의 200행만 표시합니다.</p>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
