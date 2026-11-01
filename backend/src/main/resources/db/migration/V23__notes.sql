-- 쪽지 (R74, 가정·검수 필요): 직원끼리 주고받는 사내 쪽지와 시스템 쪽지(결재 요청·결과 알림)
-- 본문은 보낸 뒤 고치지 않는다. 지우기는 받은/보낸 사람 각자의 쪽지함에서 숨기는 것뿐이다(기록은 남음).

CREATE TABLE note (
    id                BIGSERIAL     PRIMARY KEY,
    -- NULL이면 시스템 쪽지
    sender_id         BIGINT        NULL REFERENCES staff (id),
    subject           VARCHAR(100)  NOT NULL,
    body              VARCHAR(2000) NOT NULL,
    -- 업무 연결: 누르면 그 계약·신청 화면으로 이동
    ref_type          VARCHAR(20)   NULL CHECK (ref_type IN ('LOAN', 'APPLICATION')),
    ref_id            BIGINT        NULL,
    ref_label         VARCHAR(30)   NULL,
    -- 보낸 쪽지함에 보여 줄 받는 사람 요약 (예: 채권1팀 3명, 홍길동 외 2명)
    recipient_summary VARCHAR(200)  NOT NULL,
    sent_at           TIMESTAMPTZ   NOT NULL,
    sender_hidden_at  TIMESTAMPTZ   NULL,
    CHECK ((ref_type IS NULL) = (ref_id IS NULL))
);

CREATE TABLE note_recipient (
    id         BIGSERIAL   PRIMARY KEY,
    note_id    BIGINT      NOT NULL REFERENCES note (id),
    staff_id   BIGINT      NOT NULL REFERENCES staff (id),
    read_at    TIMESTAMPTZ NULL,
    hidden_at  TIMESTAMPTZ NULL,
    UNIQUE (note_id, staff_id)
);

CREATE INDEX note_recipient_inbox_idx ON note_recipient (staff_id, note_id DESC);
CREATE INDEX note_sender_idx ON note (sender_id, id DESC);

-- 보낸 내용(제목·본문·받는 사람)은 고칠 수 없다. 읽음·숨김 표시만 바뀐다
CREATE FUNCTION forbid_note_content_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'note is not deletable';
    END IF;
    IF NEW.subject IS DISTINCT FROM OLD.subject OR NEW.body IS DISTINCT FROM OLD.body
       OR NEW.sender_id IS DISTINCT FROM OLD.sender_id OR NEW.sent_at IS DISTINCT FROM OLD.sent_at THEN
        RAISE EXCEPTION 'note content is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER note_immutable
    BEFORE UPDATE OR DELETE ON note
    FOR EACH ROW EXECUTE FUNCTION forbid_note_content_change();

-- 쪽지함 메뉴: '내 계정'이 있는 그룹(없으면 첫 그룹) 끝에 붙인다
INSERT INTO menu_item (group_id, path, label, sort_order)
SELECT g.id, '/notes', '쪽지함',
       (SELECT coalesce(max(sort_order), 0) + 1 FROM menu_item WHERE group_id = g.id)
FROM (SELECT coalesce((SELECT group_id FROM menu_item WHERE path = '/account' LIMIT 1),
                      (SELECT min(id) FROM menu_group)) AS id) g
WHERE g.id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM menu_item WHERE path = '/notes');
