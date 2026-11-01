-- 양식 인쇄 (R72, 가정·검수 필요): 양식 원본(HTML + CSS)과 인쇄 이력
-- 치환 문자열은 이중 중괄호로 감싼 이름(예: 고객명) 형식. 쓸 수 있는 이름은 코드의 DocumentPlaceholders가 정하고, 모르는 이름은 저장할 때 거절한다.
-- 회사명·주소·등록번호는 모두 가상 값이다 (실제 회사·기관 아님).

CREATE TABLE print_template (
    code        VARCHAR(30)  PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(200) NOT NULL,
    -- 대상 판정 규칙 (DEMAND: 연체·상실 계약, ACCELERATION: 기한이익상실 통지·상실 계약)
    target_rule VARCHAR(20)  NOT NULL CHECK (target_rule IN ('DEMAND', 'ACCELERATION')),
    -- 추심 연락 제한(연락 유예·소멸시효 완성·추심 중지)을 적용하는 양식인지. 법정 통지는 false
    collection_notice BOOLEAN NOT NULL,
    css         TEXT         NOT NULL,
    body        TEXT         NOT NULL,
    version     INT          NOT NULL DEFAULT 1,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL
);

-- 인쇄 이력: 추가만 한다. 미리보기는 남기지 않는다
CREATE TABLE document_print_log (
    id            BIGSERIAL    PRIMARY KEY,
    batch_no      VARCHAR(20)  NOT NULL,
    template_code VARCHAR(30)  NOT NULL REFERENCES print_template (code),
    template_version INT       NOT NULL,
    account_id    BIGINT       NOT NULL REFERENCES loan_account (id),
    document_no   VARCHAR(30)  NOT NULL UNIQUE,
    business_date DATE         NOT NULL,
    printed_by    BIGINT       NOT NULL REFERENCES staff (id),
    printed_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX document_print_log_account_idx ON document_print_log (account_id, id);
CREATE SEQUENCE document_print_batch_seq;

CREATE FUNCTION forbid_print_log_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'document_print_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_print_log_append_only
    BEFORE UPDATE OR DELETE ON document_print_log
    FOR EACH ROW EXECUTE FUNCTION forbid_print_log_change();

-- 두 양식이 같이 쓰는 A4 서식
INSERT INTO print_template (code, name, description, target_rule, collection_notice, css, body, sort_order) VALUES
('DEMAND_LETTER', '변제 독촉장', '연체 중인 계약에 미납금 납부를 요청하는 안내문 (기한이익상실 계약은 완제 금액 청구)',
 'DEMAND', TRUE,
$css$
.doc { font-family: 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; color: #111; font-size: 10.5pt; line-height: 1.6; }
.doc .top { display: flex; justify-content: space-between; font-size: 9pt; color: #444; border-bottom: 2px solid #111; padding-bottom: 4mm; }
.doc h1 { text-align: center; font-size: 22pt; letter-spacing: 12pt; margin: 10mm 0 8mm; }
.doc .to { margin-bottom: 6mm; }
.doc .to .name { font-size: 13pt; font-weight: bold; }
.doc table { width: 100%; border-collapse: collapse; margin: 4mm 0; }
.doc th, .doc td { border: 1px solid #555; padding: 2mm 3mm; }
.doc th { background: #f0f0f0; width: 32%; text-align: left; font-weight: normal; }
.doc td.num { text-align: right; }
.doc tr.total th, .doc tr.total td { font-weight: bold; background: #fff7d6; }
.doc .box { border: 2px solid #111; padding: 4mm; margin: 6mm 0; }
.doc .notes { font-size: 9pt; color: #333; }
.doc .notes li { margin-bottom: 1mm; }
.doc .sign { margin-top: 12mm; text-align: center; }
.doc .sign .company { font-size: 15pt; font-weight: bold; letter-spacing: 2pt; }
.doc .stamp { display: inline-block; border: 2px solid #c00; color: #c00; border-radius: 50%; width: 18mm; height: 18mm; line-height: 18mm; font-size: 9pt; margin-left: 4mm; vertical-align: middle; }
.doc .foot { margin-top: 10mm; border-top: 1px solid #999; padding-top: 3mm; font-size: 8.5pt; color: #555; }
$css$,
$body$
<div class="doc">
  <div class="top">
    <span>문서번호 {{문서번호}}</span>
    <span>발행일 {{발행일}}</span>
  </div>
  <h1>변제 독촉장</h1>
  <div class="to">
    <div class="name">{{고객명}} 귀하</div>
    <div>{{고객주소}}</div>
  </div>
  <p>
    귀하께서 {{대출일}}에 당사와 체결한 대출 계약(계약번호 {{계약번호}}, {{상품명}})의 상환금이
    {{연체시작일}}부터 {{연체일수}}일째 납부되지 않고 있어, 아래 금액을 납부 기한까지 납부하여 주실 것을 요청드립니다.
  </p>
  <table>
    <tr><th>계약번호</th><td>{{계약번호}}</td></tr>
    <tr><th>대출금액 / 약정이율</th><td>{{대출금액}}원 / 연 {{약정이율}}</td></tr>
    <tr><th>원금 잔액</th><td class="num">{{원금잔액}}원</td></tr>
    <tr><th>미납 회차 (최초 미납 약정일)</th><td>{{미납회차}}회차 ({{최초미납약정일}})</td></tr>
    <tr><th>연체 원금</th><td class="num">{{연체원금}}원</td></tr>
    <tr><th>미납 이자</th><td class="num">{{미납이자}}원</td></tr>
    <tr><th>연체 가산이자</th><td class="num">{{가산이자}}원</td></tr>
    <tr><th>비용</th><td class="num">{{비용}}원</td></tr>
    <tr class="total"><th>납부하실 금액 ({{발행일}} 기준)</th><td class="num">{{청구금액}}원</td></tr>
  </table>
  <div class="box">
    <div><b>납부 기한</b> : {{납부기한}}까지</div>
    <div><b>입금 계좌</b> : {{입금계좌}} (예금주 {{회사명}})</div>
  </div>
  <ol class="notes">
    <li>납부하실 금액은 발행일 기준이며, 납부일까지 이자가 더해집니다. 정확한 금액은 담당자에게 확인하여 주십시오.</li>
    <li>이미 납부하셨다면 이 안내를 받지 않으신 것으로 하셔도 됩니다.</li>
    <li>납부가 어려우시면 담당자와 상담하여 분할 납부 등을 협의하실 수 있습니다.</li>
    <li>연체가 계속되면 약정에 따라 기한의 이익이 상실되어 남은 대출금 전액을 한꺼번에 갚으셔야 할 수 있습니다.</li>
  </ol>
  <div class="sign">
    <span class="company">{{회사명}}</span><span class="stamp">직인</span>
  </div>
  <div class="foot">
    {{회사주소}} · 대표전화 {{회사전화}} · 대부업 등록번호 {{대부업등록번호}}<br />
    담당자 {{담당자}} · 이 문서는 가상 회사의 예시 양식입니다.
  </div>
</div>
$body$, 1),

('ACCELERATION_NOTICE', '기한이익상실 통보서', '기한이익상실 사유 발생과 상실 예정일(R15), 상실 후에는 상실 사실과 완제 금액을 알리는 통지',
 'ACCELERATION', FALSE,
$css$
.doc { font-family: 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; color: #111; font-size: 10.5pt; line-height: 1.6; }
.doc .top { display: flex; justify-content: space-between; font-size: 9pt; color: #444; border-bottom: 3px double #111; padding-bottom: 4mm; }
.doc .mark { display: inline-block; border: 1px solid #111; padding: 0 3mm; font-size: 9pt; }
.doc h1 { text-align: center; font-size: 20pt; letter-spacing: 6pt; margin: 10mm 0 8mm; }
.doc .to { margin-bottom: 6mm; }
.doc .to .name { font-size: 13pt; font-weight: bold; }
.doc table { width: 100%; border-collapse: collapse; margin: 4mm 0; }
.doc th, .doc td { border: 1px solid #555; padding: 2mm 3mm; }
.doc th { background: #eef2f7; width: 32%; text-align: left; font-weight: normal; }
.doc td.num { text-align: right; }
.doc .key td, .doc .key th { font-weight: bold; }
.doc .warn { border-left: 4px solid #c00; padding: 2mm 4mm; margin: 6mm 0; background: #fff3f3; }
.doc .notes { font-size: 9pt; color: #333; }
.doc .sign { margin-top: 12mm; text-align: center; }
.doc .sign .company { font-size: 15pt; font-weight: bold; letter-spacing: 2pt; }
.doc .stamp { display: inline-block; border: 2px solid #c00; color: #c00; border-radius: 50%; width: 18mm; height: 18mm; line-height: 18mm; font-size: 9pt; margin-left: 4mm; vertical-align: middle; }
.doc .foot { margin-top: 10mm; border-top: 1px solid #999; padding-top: 3mm; font-size: 8.5pt; color: #555; }
$css$,
$body$
<div class="doc">
  <div class="top">
    <span>문서번호 {{문서번호}} <span class="mark">내용증명 발송용</span></span>
    <span>발행일 {{발행일}}</span>
  </div>
  <h1>기한이익상실 통보서</h1>
  <div class="to">
    <div class="name">{{고객명}} 귀하</div>
    <div>{{고객주소}}</div>
  </div>
  <p>
    귀하께서 {{대출일}}에 당사와 체결한 대출 계약(계약번호 {{계약번호}})에 관하여, 약정에서 정한 기한이익상실 사유가
    {{사유발생일}}에 발생하였음을 알려 드립니다. 상실 예정일 전까지 연체 금액을 모두 납부하시면 기한의 이익이 유지됩니다.
  </p>
  <table>
    <tr><th>계약번호 / 상품</th><td>{{계약번호}} / {{상품명}}</td></tr>
    <tr><th>사유 발생일</th><td>{{사유발생일}} (연체 시작 {{연체시작일}}, {{연체일수}}일째)</td></tr>
    <tr><th>통지 발송일</th><td>{{통지발송일}}</td></tr>
    <tr><th>도달 간주일</th><td>{{도달간주일}}</td></tr>
    <tr class="key"><th>기한이익 상실 예정일</th><td>{{상실예정일}}</td></tr>
    <tr><th>상실 확정일</th><td>{{상실일}}</td></tr>
    <tr><th>현재 연체 금액</th><td class="num">{{청구금액}}원</td></tr>
    <tr><th>원금 잔액</th><td class="num">{{원금잔액}}원</td></tr>
    <tr class="key"><th>상실 시 갚으셔야 할 금액 ({{발행일}} 기준)</th><td class="num">{{완제금액}}원</td></tr>
  </table>
  <div class="warn">
    상실 예정일까지 연체 금액이 납부되지 않으면, 그다음 날부터 남은 대출금 전액에 대하여 기한의 이익을 잃게 되며
    원금 잔액 전체에 연체 가산이자가 붙습니다. 입금 계좌: {{입금계좌}} (예금주 {{회사명}})
  </div>
  <ol class="notes">
    <li>금액은 발행일 기준이며 납부일까지의 이자가 더해집니다.</li>
    <li>상실 확정일이 '-'이면 아직 상실되지 않은 상태입니다.</li>
    <li>문의: 담당자 {{담당자}} · 대표전화 {{회사전화}}</li>
  </ol>
  <div class="sign">
    <span class="company">{{회사명}}</span><span class="stamp">직인</span>
  </div>
  <div class="foot">
    {{회사주소}} · 대부업 등록번호 {{대부업등록번호}} · 이 문서는 가상 회사의 예시 양식입니다.
  </div>
</div>
$body$, 2);
