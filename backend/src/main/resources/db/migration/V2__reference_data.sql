-- 정책서 1장 상품, 8장 영업일 달력(2026), 업무일자 초기값

INSERT INTO holiday (holiday_date, name) VALUES
    ('2026-01-01', '신정'),
    ('2026-02-16', '설날 연휴'), ('2026-02-17', '설날'), ('2026-02-18', '설날 연휴'),
    ('2026-03-02', '삼일절 대체공휴일'),
    ('2026-05-05', '어린이날'),
    ('2026-05-25', '부처님오신날 대체공휴일'),
    ('2026-06-03', '전국동시지방선거'),
    ('2026-08-17', '광복절 대체공휴일'),
    ('2026-09-24', '추석 연휴'), ('2026-09-25', '추석'), ('2026-09-28', '추석 대체공휴일'),
    ('2026-10-05', '개천절 대체공휴일'),
    ('2026-10-09', '한글날'),
    ('2026-12-25', '성탄절');

-- 데모 시나리오(G11·G27·G28)를 화면에서 재현할 수 있도록 2026-06-12에서 시작. 일마감으로 하루씩 진행
INSERT INTO business_date (id, current_date_, updated_at) VALUES (1, '2026-06-12', now());

INSERT INTO team (id, name) VALUES (1, '심사팀'), (2, '채권1팀'), (3, '채권2팀'), (4, '출납팀'), (9, '본부');

INSERT INTO loan_product (code, name, repayment_method, default_rate, min_amount, max_amount, min_term_months,
                          max_term_months, min_grace_months, max_grace_months, collateral_type, prepayment_fee_rate,
                          product_version, valid_from, created_at, created_by, updated_at)
VALUES ('P1', '개인신용 분할', 'EQUAL_INSTALLMENT', 19.90, 1000000, 30000000, 12, 60, 0, 0, 'NONE', 1.00, 1, '2026-01-01', now(), 'system', now()),
       ('P2', '개인신용 단기', 'BULLET', 19.00, 1000000, 10000000, 3, 12, 0, 0, 'NONE', 0.00, 1, '2026-01-01', now(), 'system', now()),
       ('P3', '자동차담보', 'EQUAL_PRINCIPAL', 15.00, 3000000, 50000000, 12, 48, 0, 0, 'VEHICLE', 1.20, 1, '2026-01-01', now(), 'system', now()),
       ('P4', '부동산담보', 'GRACE_THEN_INSTALLMENT', 12.00, 10000000, 200000000, 12, 36, 6, 12, 'REAL_ESTATE', 1.00, 1, '2026-01-01', now(), 'system', now()),
       ('P5', '개인신용 자유상환', 'REVOLVING', 19.50, 1000000, 20000000, 12, 36, 0, 0, 'NONE', 0.00, 1, '2026-01-01', now(), 'system', now()),
       ('N1', '매입채권(NPL)', 'ACQUIRED', 20.00, 0, 9000000000, 0, 0, 0, 0, 'ORIGINAL', 0.00, 1, '2026-01-01', now(), 'system', now());
