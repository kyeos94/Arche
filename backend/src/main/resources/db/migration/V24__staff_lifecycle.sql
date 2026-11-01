-- 직원 등록·퇴사·복직 (R75, 가정·검수 필요)
-- 퇴사는 행을 지우지 않고 enabled = false + resigned_at으로 남긴다. 처리 이력·원장·쪽지에 이름이 계속 보여야 하므로.

ALTER TABLE staff ADD COLUMN resigned_at TIMESTAMPTZ NULL;

CREATE TABLE staff_status_change (
    id          BIGSERIAL     PRIMARY KEY,
    staff_id    BIGINT        NOT NULL REFERENCES staff (id),
    action      VARCHAR(20)   NOT NULL CHECK (action IN ('CREATE', 'RESIGN', 'REINSTATE')),
    reason      VARCHAR(200)  NOT NULL,
    -- 퇴사 시 넘긴 담당 건 요약 (예: 계약 12건 → 김채권, 심사 3건 → 이심사)
    detail      VARCHAR(300)  NULL,
    changed_by  BIGINT        NOT NULL REFERENCES staff (id),
    changed_at  TIMESTAMPTZ   NOT NULL
);

CREATE INDEX staff_status_change_staff_idx ON staff_status_change (staff_id, id);

CREATE FUNCTION forbid_staff_status_change_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'staff_status_change is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER staff_status_change_append_only
    BEFORE UPDATE OR DELETE ON staff_status_change
    FOR EACH ROW EXECUTE FUNCTION forbid_staff_status_change_update();

-- '직원 관리' 메뉴: '운영 관리' 바로 아래에 끼워 넣는다 (같은 그룹의 뒤 메뉴는 한 칸씩 밀림)
UPDATE menu_item SET sort_order = sort_order + 1
WHERE group_id = (SELECT group_id FROM menu_item WHERE path = '/admin')
  AND sort_order > (SELECT sort_order FROM menu_item WHERE path = '/admin')
  AND NOT EXISTS (SELECT 1 FROM menu_item WHERE path = '/admin/staff');

INSERT INTO menu_item (group_id, path, label, sort_order)
SELECT m.group_id, '/admin/staff', '직원 관리', m.sort_order + 1
FROM menu_item m
WHERE m.path = '/admin'
  AND NOT EXISTS (SELECT 1 FROM menu_item WHERE path = '/admin/staff');

-- '/admin'이 메뉴에서 빠져 있으면 첫 그룹 끝에 붙인다
INSERT INTO menu_item (group_id, path, label, sort_order)
SELECT g.id, '/admin/staff', '직원 관리', (SELECT coalesce(max(sort_order), 0) + 1 FROM menu_item WHERE group_id = g.id)
FROM (SELECT min(id) AS id FROM menu_group) g
WHERE g.id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM menu_item WHERE path = '/admin/staff');
