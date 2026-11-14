# README용 화면 GIF 녹화

`docs/media/`의 GIF 3개(payment-replay, collection-limit, search-masking)를 만든다. 앱 코드는 건드리지 않고,
녹화하는 브라우저 페이지에만 자막 띠·커서 표시·애니메이션 끄기를 주입한다.

## 준비 (한 번)

```powershell
winget install --id Gyan.FFmpeg -e
cd tools/demo-capture
npm install
npx playwright install chromium
```

## 다시 녹화

시나리오가 데이터를 바꾸므로(매칭·삭제·정정 이동·연락 기록) **매번 새 DB에서** 시작한다.
녹화 전용 DB `arche_demo`(소유자 `arche`)를 쓰고 기존 `arche` DB는 건드리지 않는다.

```powershell
# 1) 백엔드를 끈 뒤 arche_demo를 비운다 (소유자 arche라 관리자 권한 불필요)
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U arche -d arche_demo -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

# 2) 업무일자를 2026-06-12에 고정해 백엔드 기동 (golden G11·G27·G28 재현 시점)
cd backend
$env:DB_URL = "jdbc:postgresql://localhost:5432/arche_demo"; $env:ARCHE_CATCH_UP = "false"
.\gradlew.bat bootRun        # 또는 빌드한 jar: java -jar build\libs\arche-0.0.1-SNAPSHOT.jar

# 3) 프런트(:5173)를 켠 상태에서 녹화
cd tools/demo-capture
npm run capture              # 셋 다. 일부만: npm run capture -- search
```

- 로그인은 `demo`, 당일 입금 취소·이동 단계(R27: 처리자 본인 또는 채권팀장만)는 같은 창에서 `collead`로 바꿔 진행한다.
- 직원 한 명당 세션은 1개(`maximumSessions(1)`)다. 녹화 중에 다른 브라우저에서 `demo`·`collead`로 로그인하면 녹화 세션이 끊긴다.
- 단계마다 화면 값을 확인하고, 기대와 다르면 GIF를 만들지 않고 `out/<시나리오>-FAILED.png`와 실패한 API 응답을 남기고 멈춘다.
- 검증용 프레임은 완성된 GIF에서 뽑아 `out/frames/`에 둔다.
- 용량: 녹화 영상의 압축 잡음 때문에 `mpdecimate`로 거의 같은 프레임을 버리고, 5MB를 넘으면 더 강한 단계로 다시 변환한다(`src/lib.ts`의 `GIF_LEVELS`).
- 추심 '연락 가능 시간(08:00~21:00)' 차단은 서버의 실제 시계로 판정하므로 낮에 녹화하면 나오지 않는다(한도 차단만 나옴).
