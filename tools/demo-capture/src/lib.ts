/**
 * README용 화면 녹화 도우미. 앱 코드는 건드리지 않고, 녹화하는 브라우저 페이지에만
 * 자막 띠와 커서 표시를 주입한다.
 */
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { type Browser, type BrowserContext, type Locator, type Page, expect } from 'playwright/test'

export const APP_URL = process.env.ARCHE_APP_URL ?? 'http://localhost:5173'
export const VIEWPORT = { width: 1280, height: 800 }
export const CAPTION_HEIGHT = 40

/** 동작 사이 멈춤 (사람이 따라갈 수 있게 0.8~1.5초) */
export const pause = (page: Page, ms = 1000) => page.waitForTimeout(ms)

/** 자막 띠 + 커서 표시. 전체 새로고침에도 남도록 init script로 넣고, 자막 문구는 sessionStorage에 둔다 */
const OVERLAY_SCRIPT = `(() => {
  const H = ${CAPTION_HEIGHT};
  const install = () => {
    if (document.getElementById('__demo_caption')) return;
    const style = document.createElement('style');
    style.textContent = \`
      html { padding-top: \${H}px; scroll-padding-top: \${H}px; }
      /* 녹화용: 페이드·전환 애니메이션을 꺼서 화면 변화가 한 프레임에 끝나게 (GIF 용량) */
      *, *::before, *::after { transition: none !important; animation: none !important; caret-color: auto; }
      #__demo_caption { position: fixed; top: 0; left: 0; right: 0; height: \${H}px; z-index: 2147483646;
        display: flex; align-items: center; padding: 0 20px; gap: 10px; pointer-events: none;
        background: #111827; color: #fff; font: 600 16px/1.2 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif;
        box-shadow: 0 2px 6px rgba(0,0,0,.25); }
      #__demo_caption small { font-weight: 400; color: #9ca3af; font-size: 13px; margin-left: auto; }
      #__demo_cursor { position: fixed; z-index: 2147483647; width: 22px; height: 22px; margin: -11px 0 0 -11px;
        border-radius: 50%; border: 2px solid #ef4444; background: rgba(239,68,68,.25); pointer-events: none;
        transition: transform .12s ease-out; left: -50px; top: -50px; }
      #__demo_cursor.down { transform: scale(.6); background: rgba(239,68,68,.6); }
    \`;
    document.head.appendChild(style);
    const cap = document.createElement('div');
    cap.id = '__demo_caption';
    cap.innerHTML = '<span id="__demo_caption_text"></span><small>Arche 데모 · 가상 데이터</small>';
    document.body.appendChild(cap);
    const cur = document.createElement('div');
    cur.id = '__demo_cursor';
    document.body.appendChild(cur);
    const saved = sessionStorage.getItem('__demo_caption') || '';
    document.getElementById('__demo_caption_text').textContent = saved;
    const pos = sessionStorage.getItem('__demo_cursor');
    if (pos) { const [x, y] = pos.split(','); cur.style.left = x + 'px'; cur.style.top = y + 'px'; }
    window.addEventListener('mousemove', (e) => {
      cur.style.left = e.clientX + 'px'; cur.style.top = e.clientY + 'px';
      sessionStorage.setItem('__demo_cursor', e.clientX + ',' + e.clientY);
    }, true);
    window.addEventListener('mousedown', () => cur.classList.add('down'), true);
    window.addEventListener('mouseup', () => cur.classList.remove('down'), true);
  };
  if (document.body) install(); else document.addEventListener('DOMContentLoaded', install);
})();`

export class Recorder {
  readonly marks: { label: string; t: number }[] = []
  private readonly started = Date.now()
  private mouse = { x: VIEWPORT.width / 2, y: VIEWPORT.height / 2 }

  private constructor(
    readonly context: BrowserContext,
    readonly page: Page,
  ) {}

  /** 로그인 상태(storageState)를 받아 녹화 컨텍스트를 연다 */
  static async open(browser: Browser, storageState: string, videoDir: string) {
    const context = await browser.newContext({
      viewport: VIEWPORT,
      deviceScaleFactor: 1,
      locale: 'ko-KR',
      timezoneId: 'Asia/Seoul',
      storageState,
      recordVideo: { dir: videoDir, size: VIEWPORT },
    })
    await context.addInitScript(OVERLAY_SCRIPT)
    const page = await context.newPage()
    // 진단용: 실패한 API 응답을 콘솔에 남긴다
    page.on('response', async (res) => {
      if (res.url().includes('/api/') && res.status() >= 400) {
        const body = await res.text().catch(() => '')
        console.log(`  [API ${res.status()}] ${res.request().method()} ${res.url().replace(APP_URL, '')} ${body.slice(0, 200)}`)
      }
    })
    return new Recorder(context, page)
  }

  /** 녹화 시작 후 경과 초 */
  elapsed() {
    return (Date.now() - this.started) / 1000
  }

  /** 검증용 프레임을 뽑을 시점 표시 */
  mark(label: string) {
    this.marks.push({ label, t: this.elapsed() })
  }

  async caption(text: string) {
    await this.page.evaluate((t) => {
      sessionStorage.setItem('__demo_caption', t)
      const el = document.getElementById('__demo_caption_text')
      if (el) el.textContent = t
    }, text)
  }

  async goto(path: string) {
    await this.page.goto(APP_URL + path)
    await this.page.waitForLoadState('networkidle')
  }

  /** 커서를 부드럽게 옮긴 뒤 클릭하고 잠깐 멈춘다 */
  async click(target: Locator, after = 900) {
    await target.scrollIntoViewIfNeeded()
    const box = await target.boundingBox()
    if (!box) throw new Error(`보이지 않는 요소: ${target}`)
    const x = box.x + box.width / 2
    const y = box.y + box.height / 2
    await this.page.mouse.move(this.mouse.x, this.mouse.y)
    await this.page.mouse.move(x, y, { steps: 18 })
    this.mouse = { x, y }
    await pause(this.page, 250)
    await this.page.mouse.down()
    await this.page.mouse.up()
    await pause(this.page, after)
  }

  /** 입력칸을 눌러 사람처럼 입력한다 */
  async type(target: Locator, text: string, after = 500) {
    await this.click(target, 200)
    await target.pressSequentially(text, { delay: 45 })
    await pause(this.page, after)
  }

  async select(target: Locator, value: string, after = 400) {
    await this.click(target, 150)
    await target.selectOption(value)
    await pause(this.page, after)
  }

  /** 같은 녹화 창에서 다른 직원으로 바꾼다 (미리 로그인해 둔 세션 쿠키로 교체 후 새로고침) */
  async switchAccount(statePath: string) {
    const state = JSON.parse(readFileSync(statePath, 'utf8')) as { cookies: Parameters<BrowserContext['addCookies']>[0] }
    await this.context.clearCookies()
    await this.context.addCookies(state.cookies)
    await this.page.reload()
    await this.page.waitForLoadState('networkidle')
  }

  async close() {
    const video = this.page.video()
    await this.context.close()
    return video ? await video.path() : null
  }
}

/** 로그인만 하는 비녹화 컨텍스트: 세션·XSRF 쿠키를 파일로 저장 */
export async function login(browser: Browser, loginId: string, password: string, statePath: string) {
  const context = await browser.newContext({ viewport: VIEWPORT, locale: 'ko-KR' })
  const page = await context.newPage()
  await page.goto(APP_URL + '/')
  await page.getByLabel('아이디').fill(loginId)
  await page.getByLabel('비밀번호').fill(password)
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByTestId('business-date')).toBeVisible({ timeout: 15_000 })
  await context.storageState({ path: statePath })
  await context.close()
}

/** 계약 상세 상단 요약 바의 값 */
export function summary(page: Page) {
  return page.getByRole('region', { name: '계약 요약' })
}

// ------------------------------------------------------------------ ffmpeg

export function findFfmpeg(): string {
  if (process.env.FFMPEG && existsSync(process.env.FFMPEG)) return process.env.FFMPEG
  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' })
    return 'ffmpeg'
  } catch {
    // winget 설치 직후에는 새 PATH가 현재 셸에 없을 수 있다
  }
  const base = join(process.env.LOCALAPPDATA ?? '', 'Microsoft', 'WinGet', 'Packages')
  if (existsSync(base)) {
    for (const pkg of readdirSync(base).filter((d) => d.startsWith('Gyan.FFmpeg'))) {
      for (const build of readdirSync(join(base, pkg))) {
        const exe = join(base, pkg, build, 'bin', 'ffmpeg.exe')
        if (existsSync(exe)) return exe
      }
    }
  }
  throw new Error('ffmpeg를 찾지 못했습니다. winget install --id Gyan.FFmpeg -e 후 새 터미널에서 실행하거나 FFMPEG 환경변수에 경로를 넣으세요.')
}

const ffmpeg = (args: string[]) => execFileSync(findFfmpeg(), ['-hide_banner', '-loglevel', 'error', '-y', ...args])

/**
 * 용량 단계: 녹화 영상(VP8/9)의 압축 잡음 때문에 멈춘 화면도 프레임마다 조금씩 달라 GIF가 커진다.
 * mpdecimate로 거의 같은 프레임을 버리면(fps 필터가 직전 프레임을 그대로 복제) 정지 구간이 거의 0바이트가 된다.
 * 앞 단계부터 시도해 5MB 이하가 되는 첫 설정을 쓴다.
 */
export const GIF_LEVELS = [
  { decimate: 'hi=2000:lo=600:frac=0.33', colors: 128 },
  { decimate: 'hi=4000:lo=1200:frac=0.33', colors: 128 },
  { decimate: 'hi=4000:lo=1200:frac=0.33', colors: 64 },
  { decimate: 'hi=8000:lo=2400:frac=0.2', colors: 64 },
] as const

/** webm → GIF (palettegen → paletteuse 2단계). start초부터 자른다 */
export function toGif(webm: string, gif: string, opts: { start: number; fps: number; level: (typeof GIF_LEVELS)[number] }) {
  const filters = `mpdecimate=${opts.level.decimate},fps=${opts.fps},scale=1280:-1:flags=lanczos`
  const palette = gif.replace(/\.gif$/, '.palette.png')
  ffmpeg([
    '-ss', String(opts.start), '-i', webm,
    '-vf', `${filters},palettegen=max_colors=${opts.level.colors}:stats_mode=diff`, palette,
  ])
  ffmpeg([
    '-ss', String(opts.start), '-i', webm, '-i', palette,
    '-lavfi', `${filters}[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle`,
    '-loop', '0', gif,
  ])
  return palette
}

export function frameAt(gifOrVideo: string, seconds: number, png: string) {
  ffmpeg(['-ss', seconds.toFixed(2), '-i', gifOrVideo, '-frames:v', '1', png])
}

export function durationOf(file: string): number {
  const probe = findFfmpeg().replace(/ffmpeg(\.exe)?$/, (m) => m.replace('ffmpeg', 'ffprobe'))
  const out = execFileSync(probe, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file]).toString()
  return Number(out.trim())
}

export const sizeMb = (file: string) => statSync(file).size / 1024 / 1024
