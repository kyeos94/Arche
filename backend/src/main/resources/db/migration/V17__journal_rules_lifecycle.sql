-- 묶음 A(R40·R44)의 원장 거래 종류를 분개 규칙(R68)에 연결한다.
-- 묶음 A와 E를 따로 만들어 V16에는 SALE_TRANSFER·SETOFF 규칙이 없었고, 그대로 두면 미결산(X-SUSPENSE)으로 빠진다.
INSERT INTO journal_account (code, name, category, sort_order) VALUES
    ('L-BUYER', '매각 후 입금 양수인 이전 예수금', 'LIABILITY', 140);

INSERT INTO journal_rule (posting_type, mode, counter_account, book_account, description) VALUES
    ('SALE_TRANSFER', 'BOOK', 'L-BUYER', 'A-CASH', '매각 후 입금: 차변 현금 / 대변 양수인 이전 예수금 (이전 완료는 음수)'),
    ('SETOFF', 'SETTLE', 'L-REFUND', NULL, '상계: 차변 반환 대기 예수금 / 대변 충당 항목');
