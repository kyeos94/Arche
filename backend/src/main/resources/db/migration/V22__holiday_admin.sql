-- 휴일 관리 (R73, 가정·검수 필요): 화면에서 휴일을 지정·해제하고 이력을 남긴다.
-- 달력 캐시는 이 이력의 마지막 id를 버전으로 써서, 서버가 여러 대여도 다음 계산부터 바뀐 휴일을 쓴다.

CREATE TABLE holiday_change_log (
    id            BIGSERIAL    PRIMARY KEY,
    holiday_date  DATE         NOT NULL,
    action        VARCHAR(10)  NOT NULL CHECK (action IN ('ADD', 'REMOVE')),
    name          VARCHAR(50)  NOT NULL,
    business_date DATE         NOT NULL,
    changed_by    VARCHAR(50)  NOT NULL,
    changed_at    TIMESTAMPTZ  NOT NULL
);

CREATE FUNCTION forbid_holiday_log_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'holiday_change_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER holiday_change_log_append_only
    BEFORE UPDATE OR DELETE ON holiday_change_log
    FOR EACH ROW EXECUTE FUNCTION forbid_holiday_log_change();

-- 시스템설정 메뉴에 '휴일 관리' 추가. 메뉴를 이미 고쳤을 수 있으므로 권한 관리가 있는 그룹(없으면 첫 그룹)의 맨 뒤에 붙인다
INSERT INTO menu_item (group_id, path, label, sort_order)
SELECT g.id, '/admin/holidays', '휴일 관리',
       (SELECT coalesce(max(sort_order), 0) + 1 FROM menu_item WHERE group_id = g.id)
FROM (SELECT coalesce((SELECT group_id FROM menu_item WHERE path = '/admin/permissions' LIMIT 1),
                      (SELECT min(id) FROM menu_group)) AS id) g
WHERE g.id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM menu_item WHERE path = '/admin/holidays');
