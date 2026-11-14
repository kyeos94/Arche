/**
 * README 데모 표 기준 시나리오 3개. 화면에 보이는 값을 expect로 확인하며 진행하고,
 * 기대값과 다르면 예외로 멈춰 GIF를 만들지 않는다.
 */
import { type Page, expect } from 'playwright/test'
import { type Recorder, pause, summary } from './lib.js'

export interface Accounts {
  a: { id: number; accountNo: string }
  b: { id: number; accountNo: string }
  /** 채권팀장 collead로 로그인해 둔 세션 (당일 입금 취소·이동은 처리자 본인 또는 채권팀장만, R27) */
  colleadState: string
}

const A_RESIDENT_NO = '800101-1000001' // 데모 계약 A 홍길동 (가상)
const B_RESIDENT_NO = '850505-1000002' // 데모 계약 B 김철수 (가상)
export const A_PHONE = '010-1234-0001'
export const DEPOSIT = '1,135,616'

/** 녹화 전에 API로 계약 A·B를 찾는다 (화면에는 나오지 않음) */
export async function findAccounts(page: Page, appUrl: string): Promise<Omit<Accounts, 'colleadState'>> {
  const find = async (residentNo: string) => {
    const res = await page.request.get(`${appUrl}/api/loans/search?q=${encodeURIComponent(residentNo)}`)
    expect(res.ok(), `검색 API ${res.status()}`).toBeTruthy()
    const hits = (await res.json()) as { id: number; accountNo: string }[]
    expect(hits, `${residentNo.slice(0, 6)} 계약 수`).toHaveLength(1)
    return { id: hits[0].id, accountNo: hits[0].accountNo }
  }
  return { a: await find(A_RESIDENT_NO), b: await find(B_RESIDENT_NO) }
}

/** 계약 상세 기본정보의 값 칸 (dt 바로 다음 dd) */
const dd = (page: Page, label: string) =>
  page.locator('dt').filter({ hasText: new RegExp(`^${label}$`) }).first().locator('xpath=following-sibling::dd[1]')

/** 상단 요약 바의 항목 값 (라벨 바로 다음 칸) */
const barValue = (page: Page, label: string) =>
  summary(page).getByText(label, { exact: true }).locator('xpath=following-sibling::span[1]')

async function expectSummary(page: Page, v: { status: string; days: string; principal: string }) {
  const bar = summary(page)
  await expect(bar.getByText(v.status, { exact: true })).toBeVisible()
  await expect(barValue(page, '연체일수')).toHaveText(v.days)
  await expect(barValue(page, '원금 잔액')).toHaveText(`${v.principal}원`)
}

async function matchDeposit(r: Recorder, account: { id: number; accountNo: string }) {
  const page = r.page
  await r.goto('/unmatched')
  const row = page.getByRole('row').filter({ hasText: DEPOSIT })
  await expect(row).toHaveCount(1)
  await pause(page, 700)
  await r.click(row.getByRole('button', { name: '계약 매칭' }), 600)
  const dialog = page.getByRole('dialog')
  await r.type(dialog.getByLabel('계약번호'), account.accountNo, 300)
  await r.click(dialog.getByRole('button', { name: '매칭', exact: true }), 300)
  // 매칭되면 목록이 새로 고쳐져 그 행(과 대화상자)이 사라진다
  await expect(row).toHaveCount(0)
  await pause(page, 900)
  await r.goto(`/loans/${account.id}`)
}

/**
 * 정정 [처리]: 같은 업무일 입금이라 승인 없이 바로 처리된다('승인 요청'이면 여기서 멈춤).
 * 처리되면 입금 상태가 바뀌어 그 행의 정정 버튼(과 대화상자)이 사라진다.
 */
async function submitCorrection(r: Recorder) {
  const dialog = r.page.getByRole('dialog')
  await r.click(dialog.getByRole('button', { name: '처리', exact: true }), 300)
  await expect(dialog).toHaveCount(0)
  await r.page.waitForLoadState('networkidle')
  await pause(r.page, 700)
}

async function openPaymentsTab(r: Recorder) {
  await r.click(r.page.getByRole('tab', { name: '입금내역' }), 700)
}

/** payment-replay.gif: G11 → G27 → G28 */
export async function paymentReplay(r: Recorder, acc: Accounts) {
  const page = r.page
  await r.goto(`/loans/${acc.a.id}`)
  await r.caption('① 계약 A(홍길동) — 1회차만 납부해 연체 18일 · 원금 11,000,000원')
  await expectSummary(page, { status: '연체', days: '18일', principal: '11,000,000' })
  r.mark('A 매칭 전: 연체 18일')
  await pause(page, 1500)

  await r.caption('② 05-26 미확인 입금 1,135,616원을 계약 A에 매칭 (기산일 소급)')
  await matchDeposit(r, acc.a)
  await r.caption('② 매칭 후 재계산 — 정상 · 연체 0일 · 원금 10,000,000원 (G11)')
  await expectSummary(page, { status: '정상', days: '0일', principal: '10,000,000' })
  await expect(dd(page, '미납 정상이자')).toHaveText('73,972원')
  r.mark('A 매칭 후: 정상')
  await pause(page, 1500)

  await r.caption('③ 당일 입금 취소는 처리자 본인 또는 채권팀장만 (R27) — 채권팀장 collead로 전환')
  await r.switchAccount(acc.colleadState)
  await expect(page.getByRole('link', { name: /채권팀장/ })).toBeVisible()
  await pause(page, 1200)
  await r.caption('③ 그 입금을 삭제 — 원장은 지우지 않고 취소 거래 + 재계산')
  await openPaymentsTab(r)
  const payRow = page.getByRole('row').filter({ hasText: DEPOSIT }).filter({ has: page.getByRole('button', { name: '입금 삭제' }) })
  await r.click(payRow.getByRole('button', { name: '입금 삭제' }), 600)
  let dialog = page.getByRole('dialog')
  await r.type(dialog.getByLabel('사유'), '다른 고객의 입금', 300)
  await submitCorrection(r)
  await r.caption('③ 삭제 후 재계산 — 연체 부활 · 연체 18일 · 원금 11,000,000원 (G27)')
  await expectSummary(page, { status: '연체', days: '18일', principal: '11,000,000' })
  r.mark('A 삭제 후: 연체 부활')
  await pause(page, 1500)

  await r.caption('④ 돌아온 입금을 A에 다시 매칭한 뒤, 실제 주인 B로 정정 이동')
  await matchDeposit(r, acc.a)
  await openPaymentsTab(r)
  const again = page.getByRole('row').filter({ hasText: DEPOSIT }).filter({ has: page.getByRole('button', { name: '계약 이동' }) })
  await r.click(again.getByRole('button', { name: '계약 이동' }), 600)
  dialog = page.getByRole('dialog')
  await r.type(dialog.getByLabel('옮길 계약번호'), acc.b.accountNo, 200)
  await r.type(dialog.getByLabel('사유'), '실제 주인은 B', 300)
  await submitCorrection(r)

  await r.goto(`/loans/${acc.b.id}`)
  await r.caption('④ 계약 B(김철수) — 05-26 소급 입금: 이자 9,616원 · 원금 1,126,000원 충당 (G28)')
  await r.click(page.getByRole('tab', { name: '거래원장' }), 500)
  const ledgerRow = page.getByRole('row').filter({ hasText: DEPOSIT }).filter({ hasText: '2026-05-26' })
  await expect(ledgerRow).toHaveCount(1)
  // 거래원장 열: # · 유형 · 기산일 · 처리일 · 금액 · 원금 · 정상이자 ...
  await expect(ledgerRow.getByRole('cell').nth(5)).toHaveText(/^1,126,000/)
  await expect(ledgerRow.getByRole('cell').nth(6)).toHaveText(/^9,616/)
  await expect(barValue(page, '원금 잔액')).toHaveText('1,874,000원')
  await ledgerRow.first().scrollIntoViewIfNeeded()
  await ledgerRow.first().hover()
  r.mark('B 원장: 이자 9,616 · 원금 1,126,000')
  await pause(page, 2500)
}

/** collection-limit.gif: G14 직전 7일 7회 */
export async function collectionLimit(r: Recorder, acc: Accounts) {
  const page = r.page
  await r.goto(`/loans/${acc.a.id}`)
  await r.caption('① 계약 A 추심 탭 — 직전 7일(당일 포함) 7회까지만 연락할 수 있다')
  await r.click(page.getByRole('tab', { name: '추심' }), 700)
  const title = page.getByText(/오늘 남은 횟수/)
  await expect(title).toContainText('남은 횟수 7')
  r.mark('추심 탭: 남은 7회')
  await pause(page, 800)

  // 오른쪽 연락 타임라인(빠른 입력)에도 같은 이름의 버튼이 있어 추심 탭 안으로 좁힌다
  const tab = page.getByRole('tabpanel', { name: '추심' })
  const channel = tab.getByLabel('수단')
  const memo = tab.locator('#contactMemo')
  const submit = tab.getByRole('button', { name: '연락 기록' })
  const plan: [string, string][] = [
    ['CALL', '전화 — 부재'],
    ['SMS', '납입 안내 문자'],
    ['CALL', '전화 — 통화, 이번 주 입금 약속'],
    ['VISIT', '방문 — 부재'],
    ['SMS', '재안내 문자'],
    ['CALL', '전화 — 부재'],
    ['SMS', '입금 확인 요청'],
  ]
  for (let i = 0; i < plan.length; i++) {
    const [ch, text] = plan[i]
    await r.caption(`② 연락 기록 ${i + 1}번째 — 허용 (남은 횟수 ${7 - i} → ${6 - i})`)
    await r.select(channel, ch, 150)
    await memo.fill(text)
    await r.click(submit, 450)
    await expect(title).toContainText(`남은 횟수 ${6 - i}`)
  }
  r.mark('7회 기록 후: 남은 0')
  await pause(page, 600)

  await r.caption('③ 8번째 연락 시도 — 7일 7회 한도 초과로 차단 (차단된 시도도 기록은 남김)')
  await r.select(channel, 'CALL', 150)
  await memo.fill('전화 시도')
  await r.click(submit, 600)
  const alert = tab.getByRole('alert').filter({ hasText: '차단' })
  await expect(alert).toBeVisible()
  await expect(tab.getByRole('row').filter({ hasText: '전화 시도' })).toContainText('차단')
  r.mark('8번째: 차단')
  await pause(page, 1500)

  await r.caption('④ 법정·계약상 통지는 한도에서 제외 — 같은 날에도 허용')
  await r.select(channel, 'STATUTORY_NOTICE', 150)
  await memo.fill('기한이익상실 예고 통지')
  await r.click(submit, 600)
  await expect(tab.getByRole('row').filter({ hasText: '기한이익상실 예고 통지' })).toContainText('허용')
  r.mark('법정 통지: 허용')
  await pause(page, 2000)
}

/** search-masking.gif: 블라인드 인덱스 검색 + 마스킹 */
export async function searchMasking(r: Recorder, acc: Accounts) {
  const page = r.page
  await r.goto('/loans')
  await r.caption('① 상단 통합검색 — 주민등록번호 전체를 입력')
  const box = page.getByRole('searchbox', { name: '통합 검색' }).or(page.getByLabel('통합 검색'))
  await pause(page, 1200)
  await r.type(box, A_RESIDENT_NO, 800)
  await r.caption('① DB에는 암호문과 블라인드 인덱스만 — 인덱스 일치로 찾아 바로 계약 상세로')
  await pause(page, 1800)
  await box.press('Enter')
  await page.waitForURL(`**/loans/${acc.a.id}`)
  await page.waitForLoadState('networkidle')
  await r.caption('② 화면에는 마스킹된 주민등록번호·연락처만 보인다')
  const masked = async () => {
    await expect(dd(page, '주민등록번호')).toHaveText('800101-1******')
    await expect(dd(page, '연락처')).toHaveText('010-****-0001')
    await expect(page.locator('main')).not.toContainText(A_RESIDENT_NO)
    await expect(page.locator('main')).not.toContainText(A_PHONE)
  }
  await masked()
  await r.click(dd(page, '주민등록번호'), 300)
  r.mark('주민번호 검색 결과: 마스킹')
  await pause(page, 2500)

  await r.goto('/loans')
  await r.caption('③ 휴대폰번호도 같은 방식 — 하이픈 없이 입력해도 같은 인덱스로 찾는다')
  await pause(page, 1000)
  await r.type(box, A_PHONE.replaceAll('-', ''), 1000)
  await box.press('Enter')
  await page.waitForURL(`**/loans/${acc.a.id}`)
  await page.waitForLoadState('networkidle')
  await r.caption('③ 결과 화면의 휴대폰번호도 마스킹 값만 (010-****-0001)')
  await masked()
  await r.click(dd(page, '연락처'), 300)
  r.mark('휴대폰 검색 결과: 마스킹')
  await pause(page, 2500)

  await r.caption('④ 앞 6자리만으로는 찾지 않는다 — 블라인드 인덱스는 전체 일치만 (부분 검색 불가)')
  await pause(page, 1200)
  await r.type(box, A_RESIDENT_NO.slice(0, 6), 800)
  await box.press('Enter')
  await expect(page.getByText('조회 범위 안에서 찾은 계약이 없습니다.')).toBeVisible()
  r.mark('앞 6자리: 결과 없음')
  await pause(page, 3000)
}
