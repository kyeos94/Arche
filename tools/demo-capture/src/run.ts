/**
 * README용 GIF 3개 녹화: npm run capture [-- payment collection search]
 *
 * 전제: 새 DB로 띄운 백엔드(ARCHE_CATCH_UP=false, 업무일자 2026-06-12)와 프런트(:5173).
 * 시나리오가 데이터를 바꾸므로(매칭·삭제·연락 기록) 다시 녹화하려면 DB를 새로 만들고 시작한다 (README 참고).
 * 각 단계의 화면 값을 확인하고, 하나라도 다르면 GIF를 만들지 않고 멈춘다.
 */
import { mkdirSync, renameSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { chromium, expect } from 'playwright/test'
import { APP_URL, GIF_LEVELS, Recorder, durationOf, findFfmpeg, frameAt, login, sizeMb, toGif } from './lib.js'
import { type Accounts, collectionLimit, findAccounts, paymentReplay, searchMasking } from './scenarios.js'

const ROOT = resolve(import.meta.dirname, '..', '..', '..')
const MEDIA = join(ROOT, 'docs', 'media')
const OUT = resolve(import.meta.dirname, '..', 'out')
const STATE = join(OUT, 'demo-state.json')
const FPS = Number(process.env.GIF_FPS ?? 10)
const MAX_MB = 5

const SCENARIOS = {
  payment: { file: 'payment-replay.gif', run: paymentReplay },
  collection: { file: 'collection-limit.gif', run: collectionLimit },
  search: { file: 'search-masking.gif', run: searchMasking },
} as const
type Key = keyof typeof SCENARIOS

async function main() {
  const picked = (process.argv.slice(2).filter((a) => a in SCENARIOS) as Key[]) ?? []
  const keys: Key[] = picked.length ? picked : ['payment', 'collection', 'search']
  findFfmpeg()
  mkdirSync(MEDIA, { recursive: true })
  mkdirSync(join(OUT, 'frames'), { recursive: true })

  // 개발자도구·알림 없이, 확대 100%
  const browser = await chromium.launch({ args: ['--force-device-scale-factor=1', '--disable-notifications'] })
  try {
    await login(browser, 'demo', process.env.ARCHE_DEMO_PASSWORD ?? 'arche1234!', STATE)

    const colleadState = join(OUT, 'collead-state.json')
    await login(browser, 'collead', process.env.ARCHE_DEMO_PASSWORD ?? 'arche1234!', colleadState)

    const probe = await browser.newContext({ storageState: STATE })
    const probePage = await probe.newPage()
    const bd = await (await probePage.request.get(`${APP_URL}/api/operation/business-date`)).json()
    expect(bd.businessDate, '업무일자 (ARCHE_CATCH_UP=false로 새 DB에서 시작했는지)').toBe('2026-06-12')
    const accounts: Accounts = { ...(await findAccounts(probePage, APP_URL)), colleadState }
    await probe.close()
    console.log(`업무일자 ${bd.businessDate}, 계약 A ${accounts.a.accountNo}, 계약 B ${accounts.b.accountNo}`)

    const report: string[] = []
    for (const key of keys) {
      const { file, run } = SCENARIOS[key]
      const videoDir = join(OUT, 'video', key)
      rmSync(videoDir, { recursive: true, force: true })
      const rec = await Recorder.open(browser, STATE, videoDir)
      let start = 0
      const origCaption = rec.caption.bind(rec)
      let first = true
      rec.caption = async (text: string) => {
        await origCaption(text)
        if (first) {
          first = false
          start = Math.max(rec.elapsed() - 0.2, 0)
        }
      }
      try {
        await run(rec, accounts)
      } catch (e) {
        await rec.page.screenshot({ path: join(OUT, `${key}-FAILED.png`) })
        await rec.close()
        throw new Error(`[${key}] 화면 값이 기대와 다릅니다. GIF를 만들지 않았습니다 (${join(OUT, `${key}-FAILED.png`)})\n${e}`)
      }
      const webm = await rec.close()
      if (!webm) throw new Error('녹화 파일이 없습니다')
      const kept = join(OUT, `${key}.webm`)
      renameSync(webm, kept)

      const gif = join(MEDIA, file)
      let level = 0
      toGif(kept, gif, { start, fps: FPS, level: GIF_LEVELS[level] })
      while (sizeMb(gif) > MAX_MB && level < GIF_LEVELS.length - 1) {
        level++
        toGif(kept, gif, { start, fps: FPS, level: GIF_LEVELS[level] })
      }
      rmSync(gif.replace(/\.gif$/, '.palette.png'), { force: true })

      // 검증 프레임은 완성된 GIF에서 뽑는다 (프레임을 버린 뒤에도 그 장면이 남았는지까지 확인)
      rec.marks.forEach((m, i) => {
        const png = join(OUT, 'frames', `${key}-${i + 1}.png`)
        frameAt(gif, m.t - start, png)
        report.push(`  ${file} @${(m.t - start).toFixed(1)}s  ${m.label}  → ${png}`)
      })
      const secs = durationOf(gif)
      const mb = sizeMb(gif)
      const { decimate, colors } = GIF_LEVELS[level]
      console.log(
        `${file}: ${secs.toFixed(1)}초, ${mb.toFixed(2)}MB, ${FPS}fps, 색 ${colors}, mpdecimate ${decimate}${mb > MAX_MB ? '  ⚠ 5MB 초과' : ''}`,
      )
    }
    console.log('검증 프레임:\n' + report.join('\n'))
  } finally {
    await browser.close()
  }
}

main().catch((e) => {
  console.error(String(e))
  process.exit(1)
})
