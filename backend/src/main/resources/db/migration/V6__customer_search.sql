-- 통합 검색: 휴대폰번호 블라인드 인덱스(HMAC, 암호화 키와 분리), 이름 검색 인덱스
-- 기존 고객의 phone_bidx는 기동 시 애플리케이션이 채운다 (HMAC 키가 DB에 없으므로)
ALTER TABLE customer ADD COLUMN phone_bidx CHAR(64) NULL;
CREATE INDEX customer_phone_bidx_idx ON customer (phone_bidx);
CREATE INDEX customer_name_idx ON customer (name);
CREATE INDEX loan_account_customer_no_idx ON loan_account (account_no varchar_pattern_ops);
