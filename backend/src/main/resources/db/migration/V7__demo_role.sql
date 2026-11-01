-- 데모 전용 역할: 모든 메뉴·권한 (arche.demo.enabled=true일 때만 만들어지고 로그인 가능)
ALTER TABLE staff DROP CONSTRAINT staff_role_check;
ALTER TABLE staff ADD CONSTRAINT staff_role_check CHECK (role IN ('UNDERWRITER', 'UNDERWRITING_LEAD', 'COLLECTOR',
    'COLLECTION_LEAD', 'TELLER', 'HEAD', 'SYSTEM_ADMIN', 'DEMO_ALL'));
