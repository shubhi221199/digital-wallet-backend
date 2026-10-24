INSERT INTO users(name, email, phone, kyc_status)
VALUES ('Asha Rao', 'asha@example.test', '+910000000001', 'VERIFIED')
ON CONFLICT (email) DO NOTHING;

INSERT INTO accounts(owner_user_id, account_type, display_name)
SELECT user_id, 'WALLET', 'Main wallet' FROM users WHERE email = 'asha@example.test'
AND NOT EXISTS (SELECT 1 FROM accounts WHERE display_name = 'Main wallet' AND owner_user_id = users.user_id);