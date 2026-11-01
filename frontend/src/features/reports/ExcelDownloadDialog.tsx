import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogTrigger } from '@/components/ui/dialog'
import { Field, Input } from '@/components/ui/input'
import { type DownloadReasonForm, downloadReasonSchema } from '@/lib/schemas'

/**
 * 엑셀 다운로드 사유 입력 모달 (guide 13.1, 8.2 접근 기록, 정책서 R68 ②).
 * 사유는 접근 기록에 남고, 파일의 개인정보는 서버가 마스킹한다.
 */
export function ExcelDownloadDialog({
  title = '엑셀 다운로드',
  summary,
  onDownload,
}: {
  title?: string
  /** 무엇을 내려받는지 (검색 조건 등) */
  summary: string
  onDownload: (reason: string) => Promise<string>
}) {
  const [open, setOpen] = useState(false)
  const [saved, setSaved] = useState<string | null>(null)
  const form = useForm<DownloadReasonForm>({
    resolver: zodResolver(downloadReasonSchema),
    defaultValues: { reason: '' },
  })
  const download = useMutation({
    mutationFn: (v: DownloadReasonForm) => onDownload(v.reason),
    onSuccess: (name) => setSaved(name),
  })

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (!o) {
          setSaved(null)
          download.reset()
          form.reset()
        }
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          엑셀 다운로드
        </Button>
      </DialogTrigger>
      <DialogContent
        title={title}
        description="다운로드 사유는 접근 기록에 남습니다. 파일의 고객 이름·주민등록번호·휴대폰번호는 마스킹됩니다."
      >
        <p className="mb-3 text-xs">{summary}</p>
        {saved ? (
          <div className="space-y-3">
            <Alert tone="success">{saved} 파일을 저장했습니다.</Alert>
            <DialogFooter>
              <Button onClick={() => setOpen(false)}>닫기</Button>
            </DialogFooter>
          </div>
        ) : (
          <form className="space-y-3" onSubmit={form.handleSubmit((v) => download.mutate(v))}>
            <Field label="다운로드 사유" htmlFor="downloadReason" error={form.formState.errors.reason?.message}>
              <Input id="downloadReason" placeholder="예: 월간 연체 현황 점검" {...form.register('reason')} />
            </Field>
            <ErrorMessage error={download.error} />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>
                취소
              </Button>
              <Button type="submit" disabled={download.isPending}>
                {download.isPending ? '내려받는 중…' : '다운로드'}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
