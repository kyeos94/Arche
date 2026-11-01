-- 메뉴 관리·권한 관리 (R69·R70, 가정·검수 필요)

-- 권한: 정책서 9.1 기본값과 다르게 바꾼 칸만 저장한다. 행이 없으면 기본값 (코드의 Permission enum)
CREATE TABLE role_permission_override (
    role        VARCHAR(30)  NOT NULL,
    permission  VARCHAR(50)  NOT NULL,
    granted     BOOLEAN      NOT NULL,
    changed_by  BIGINT       NOT NULL REFERENCES staff (id),
    changed_at  TIMESTAMPTZ  NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    PRIMARY KEY (role, permission)
);

-- 권한 변경 이력: 추가만 한다
CREATE TABLE permission_change_log (
    id          BIGSERIAL    PRIMARY KEY,
    role        VARCHAR(30)  NULL,
    permission  VARCHAR(50)  NULL,
    action      VARCHAR(10)  NOT NULL CHECK (action IN ('GRANT', 'REVOKE', 'DEFAULT', 'RESET_ALL')),
    changed_by  BIGINT       NOT NULL REFERENCES staff (id),
    changed_at  TIMESTAMPTZ  NOT NULL,
    reason      VARCHAR(200) NOT NULL
);

CREATE FUNCTION forbid_permission_log_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'permission_change_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER permission_change_log_append_only
    BEFORE UPDATE OR DELETE ON permission_change_log
    FOR EACH ROW EXECUTE FUNCTION forbid_permission_log_change();

-- 메뉴: 대분류와 메뉴(화면 경로). 화면 자체와 화면별 필요 권한은 프런트 코드의 화면 목록이 정한다
CREATE TABLE menu_group (
    id          BIGSERIAL    PRIMARY KEY,
    label       VARCHAR(20)  NOT NULL,
    sort_order  INT          NOT NULL
);

CREATE TABLE menu_item (
    id          BIGSERIAL    PRIMARY KEY,
    group_id    BIGINT       NOT NULL REFERENCES menu_group (id),
    path        VARCHAR(60)  NOT NULL UNIQUE,
    label       VARCHAR(20)  NOT NULL,
    sort_order  INT          NOT NULL
);

INSERT INTO menu_group (id, label, sort_order) VALUES
    (1, '대출신청관리', 1),
    (2, '여신관리', 2),
    (3, '입출금관리', 3),
    (4, '회수관리', 4),
    (5, '보고서', 5),
    (6, '시스템설정', 6);
SELECT setval('menu_group_id_seq', 6);

INSERT INTO menu_item (group_id, path, label, sort_order) VALUES
    (1, '/applications', '신청 목록', 1),
    (1, '/underwriters', '심사자 배정', 2),
    (1, '/brokers', '중개업자', 3),
    (1, '/products', '상품', 4),
    (1, '/mock-platform', 'Mock 플랫폼', 5),
    (2, '/loans', '계약 목록', 1),
    (2, '/loans/new', '계약 등록', 2),
    (2, '/adjustments', '조정 결재', 3),
    (2, '/charge-off', '상각 후보', 4),
    (3, '/unmatched', '미확인 입금', 1),
    (3, '/payment-splits', '일괄 입금 배분', 2),
    (3, '/bulk', '입금 일괄 등록', 3),
    (3, '/corrections', '정정 승인', 4),
    (3, '/withdrawals', '출금 작성·승인', 5),
    (4, '/alerts', '추심 알림', 1),
    (4, '/legal-actions', '법조치 현황', 2),
    (5, '/reports', '보고서', 1),
    (6, '/admin', '운영 관리', 1),
    (6, '/admin/menus', '메뉴 관리', 2),
    (6, '/admin/permissions', '권한 관리', 3),
    (6, '/message-templates', '문자 템플릿', 4),
    (6, '/credit-reports', '신용정보 보고', 5),
    (6, '/account', '내 계정', 6);
