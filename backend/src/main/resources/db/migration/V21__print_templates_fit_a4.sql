-- 양식이 A4 한 장을 넘던 문제 (R72): 글자·줄 간격·여백을 줄여 내용이 A4(210×297mm, 안쪽 여백 14mm 16mm)에 들어가게 한다.
-- 본문(치환 문자열)은 그대로 두고 서식만 바꾸므로 버전만 올린다. 인쇄 이력의 template_version으로 어느 서식으로 찍었는지 구분된다.
-- 그래도 넘치는 경우(긴 주소 등)는 화면 쪽(lib/print.ts fitPages)이 한 장에 맞게 줄인다.

UPDATE print_template SET version = version + 1, css = $css$
.doc { font-family: 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; color: #111; font-size: 10pt; line-height: 1.45; }
.doc p { margin: 0 0 3mm; }
.doc .top { display: flex; justify-content: space-between; font-size: 8.5pt; color: #444; border-bottom: 2px solid #111; padding-bottom: 2.5mm; }
.doc h1 { text-align: center; font-size: 20pt; letter-spacing: 10pt; margin: 7mm 0 5mm; }
.doc .to { margin-bottom: 4mm; }
.doc .to .name { font-size: 12.5pt; font-weight: bold; }
.doc table { width: 100%; border-collapse: collapse; margin: 3mm 0; }
.doc th, .doc td { border: 1px solid #555; padding: 1.3mm 2.5mm; }
.doc th { background: #f0f0f0; width: 34%; text-align: left; font-weight: normal; }
.doc td.num { text-align: right; }
.doc tr.total th, .doc tr.total td { font-weight: bold; background: #fff7d6; }
.doc .box { border: 2px solid #111; padding: 2.5mm 4mm; margin: 4mm 0; }
.doc .notes { font-size: 8.5pt; color: #333; margin: 3mm 0; padding-left: 5mm; }
.doc .notes li { margin-bottom: 0.5mm; }
.doc .sign { margin-top: 7mm; text-align: center; }
.doc .sign .company { font-size: 14pt; font-weight: bold; letter-spacing: 2pt; }
.doc .stamp { display: inline-block; border: 2px solid #c00; color: #c00; border-radius: 50%; width: 16mm; height: 16mm; line-height: 16mm; font-size: 8.5pt; text-align: center; margin-left: 4mm; vertical-align: middle; }
.doc .foot { margin-top: 6mm; border-top: 1px solid #999; padding-top: 2mm; font-size: 8pt; color: #555; }
$css$
WHERE code = 'DEMAND_LETTER';

UPDATE print_template SET version = version + 1, css = $css$
.doc { font-family: 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; color: #111; font-size: 10pt; line-height: 1.45; }
.doc p { margin: 0 0 3mm; }
.doc .top { display: flex; justify-content: space-between; font-size: 8.5pt; color: #444; border-bottom: 3px double #111; padding-bottom: 2.5mm; }
.doc .mark { display: inline-block; border: 1px solid #111; padding: 0 2mm; font-size: 8pt; }
.doc h1 { text-align: center; font-size: 19pt; letter-spacing: 5pt; margin: 7mm 0 5mm; }
.doc .to { margin-bottom: 4mm; }
.doc .to .name { font-size: 12.5pt; font-weight: bold; }
.doc table { width: 100%; border-collapse: collapse; margin: 3mm 0; }
.doc th, .doc td { border: 1px solid #555; padding: 1.3mm 2.5mm; }
.doc th { background: #eef2f7; width: 36%; text-align: left; font-weight: normal; }
.doc td.num { text-align: right; }
.doc .key td, .doc .key th { font-weight: bold; }
.doc .warn { border-left: 4px solid #c00; padding: 2mm 4mm; margin: 4mm 0; background: #fff3f3; }
.doc .notes { font-size: 8.5pt; color: #333; margin: 3mm 0; padding-left: 5mm; }
.doc .notes li { margin-bottom: 0.5mm; }
.doc .sign { margin-top: 7mm; text-align: center; }
.doc .sign .company { font-size: 14pt; font-weight: bold; letter-spacing: 2pt; }
.doc .stamp { display: inline-block; border: 2px solid #c00; color: #c00; border-radius: 50%; width: 16mm; height: 16mm; line-height: 16mm; font-size: 8.5pt; text-align: center; margin-left: 4mm; vertical-align: middle; }
.doc .foot { margin-top: 6mm; border-top: 1px solid #999; padding-top: 2mm; font-size: 8pt; color: #555; }
$css$
WHERE code = 'ACCELERATION_NOTICE';
