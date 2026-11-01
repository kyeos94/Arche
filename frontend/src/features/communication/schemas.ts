import { z } from 'zod'
import { money } from '@/lib/schemas'

/** 서버 TemplateRenderer.PLACEHOLDERS와 같은 목록 */
export const PLACEHOLDERS = ['고객명', '계약번호', '연체일수', '완제금액', '원금잔액', '납입일', '기준일'] as const

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'yyyy-MM-dd 형식으로 입력하세요')

/** 모르는 치환자 찾기 (서버와 같은 규칙: 저장 전에 알려준다) */
export function unknownPlaceholders(body: string): string[] {
  return [...body.matchAll(/\{([^{}]+)}/g)]
    .map((m) => m[1])
    .filter((p) => !(PLACEHOLDERS as readonly string[]).includes(p))
}

export const templateSchema = z
  .object({
    code: z
      .string()
      .trim()
      .regex(/^[A-Z0-9_]{2,30}$/, '영문 대문자·숫자·밑줄 2~30자'),
    name: z.string().trim().min(1, '이름을 입력하세요').max(60),
    channel: z.enum(['SMS', 'FAX', 'POST']),
    purpose: z.enum(['COLLECTION', 'NOTICE']),
    title: z.string().max(100).optional(),
    body: z.string().trim().min(1, '내용을 입력하세요').max(2000),
    active: z.boolean().default(true),
  })
  .superRefine((v, ctx) => {
    const unknown = unknownPlaceholders(v.body)
    if (unknown.length > 0) {
      ctx.addIssue({ code: 'custom', path: ['body'], message: `알 수 없는 치환자: ${unknown.map((u) => `{${u}}`).join(', ')}` })
    }
    if (v.channel === 'SMS' && v.body.length > 500) {
      ctx.addIssue({ code: 'custom', path: ['body'], message: '문자는 500자까지입니다' })
    }
    if (v.channel !== 'SMS' && !v.title?.trim()) {
      ctx.addIssue({ code: 'custom', path: ['title'], message: '팩스·우편은 제목이 필요합니다' })
    }
  })

export const collateralSchema = z
  .object({
    type: z.enum(['VEHICLE', 'REAL_ESTATE']),
    description: z.string().trim().min(1, '설명을 입력하세요').max(200),
    identifier: z.string().max(60).optional(),
    vehicleClass: z.string().optional(),
    modelYear: z.string().optional(),
    address: z.string().max(200).optional(),
    areaM2: z.string().optional(),
    seniorClaim: z.string().optional(),
    lienAmount: z.string().optional(),
  })
  .superRefine((v, ctx) => {
    if (v.type === 'VEHICLE') {
      if (!v.vehicleClass) ctx.addIssue({ code: 'custom', path: ['vehicleClass'], message: '차종 구분을 고르세요' })
      if (!/^\d{4}$/.test(v.modelYear ?? '')) ctx.addIssue({ code: 'custom', path: ['modelYear'], message: '연식 4자리' })
    } else {
      if (!v.address?.trim()) ctx.addIssue({ code: 'custom', path: ['address'], message: '소재지를 입력하세요' })
      if (!(Number(v.areaM2) > 0)) ctx.addIssue({ code: 'custom', path: ['areaM2'], message: '전용면적(㎡)을 입력하세요' })
    }
  })
  .transform((v) => {
    const won = (s?: string) => (s ? Number(s.replace(/[^0-9]/g, '')) || 0 : 0)
    return {
      type: v.type,
      description: v.description,
      identifier: v.identifier?.trim() || undefined,
      vehicleClass: v.type === 'VEHICLE' ? v.vehicleClass : undefined,
      modelYear: v.type === 'VEHICLE' ? Number(v.modelYear) : undefined,
      address: v.type === 'REAL_ESTATE' ? v.address?.trim() : undefined,
      areaM2: v.type === 'REAL_ESTATE' ? Number(v.areaM2) : undefined,
      seniorClaim: won(v.seniorClaim),
      lienAmount: won(v.lienAmount),
    }
  })

export const guarantorSchema = z
  .object({
    name: z.string().trim().min(1, '이름을 입력하세요').max(50),
    residentNo: z
      .string()
      .trim()
      .regex(/^\d{6}-?\d{7}$/, '주민등록번호 13자리'),
    phone: z.string().max(20).optional(),
    relation: z.string().trim().min(1, '관계를 입력하세요').max(30),
    guaranteeLimit: money,
    startDate: date,
    endDate: z.string().optional(),
  })
  .refine((v) => !v.endDate || v.endDate >= v.startDate, { path: ['endDate'], message: '종료일이 시작일보다 이릅니다' })
  .transform((v) => ({ ...v, phone: v.phone?.trim() || undefined, endDate: v.endDate || undefined }))

export const withdrawalSchema = z.object({
  bankName: z.string().trim().min(1, '은행명을 입력하세요').max(40),
  accountNo: z
    .string()
    .trim()
    .regex(/^[0-9-]{6,20}$/, '계좌번호(숫자·-) 6~20자'),
  holderName: z.string().trim().min(1, '예금주를 입력하세요').max(50),
  amount: money,
  reason: z.string().trim().min(1, '사유를 입력하세요').max(200),
})
