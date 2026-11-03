ALTER TABLE smart_staged_cashbook_entries ADD COLUMN matched_staged_voucher_id UUID;
ALTER TABLE smart_staged_cashbook_entries ADD COLUMN matched_party_name VARCHAR(255);
