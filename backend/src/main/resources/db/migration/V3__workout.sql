-- G23 채무조정 (R9·R23): 재생 입력 이벤트에 감면액·재약정 회차를 담는다
ALTER TABLE account_event DROP CONSTRAINT account_event_type_check;
ALTER TABLE account_event ADD CONSTRAINT account_event_type_check
    CHECK (type IN ('PAYMENT', 'PREPAYMENT_REQUEST', 'DUE_DAY_CHANGE', 'COST_CHARGE', 'WORKOUT'));
ALTER TABLE account_event ADD COLUMN waive_late BIGINT NULL;
ALTER TABLE account_event ADD COLUMN waive_normal BIGINT NULL;
ALTER TABLE account_event ADD COLUMN waive_principal BIGINT NULL;
ALTER TABLE account_event ADD COLUMN installments INT NULL;
