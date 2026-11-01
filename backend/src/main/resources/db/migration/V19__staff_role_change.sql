-- 직원 역할·팀 변경 이력 (R71, 가정·검수 필요). 추가만 한다
CREATE TABLE staff_role_change (
    id          BIGSERIAL    PRIMARY KEY,
    staff_id    BIGINT       NOT NULL REFERENCES staff (id),
    from_role   VARCHAR(30)  NOT NULL,
    to_role     VARCHAR(30)  NOT NULL,
    from_team   BIGINT       NULL REFERENCES team (id),
    to_team     BIGINT       NULL REFERENCES team (id),
    changed_by  BIGINT       NOT NULL REFERENCES staff (id),
    changed_at  TIMESTAMPTZ  NOT NULL,
    reason      VARCHAR(200) NOT NULL
);

CREATE INDEX staff_role_change_staff_idx ON staff_role_change (staff_id, id);

CREATE FUNCTION forbid_staff_role_change_edit() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'staff_role_change is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER staff_role_change_append_only
    BEFORE UPDATE OR DELETE ON staff_role_change
    FOR EACH ROW EXECUTE FUNCTION forbid_staff_role_change_edit();
