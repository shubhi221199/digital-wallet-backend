BEGIN;

INSERT INTO users(name, email, phone, kyc_status)
SELECT 'Seed User ' || n,
       'seed-user-' || n || '@example.test',
       '+9199' || lpad(n::text, 10, '0'),
       'VERIFIED'
FROM generate_series(1, 1000) AS n
ON CONFLICT (email) DO NOTHING;

INSERT INTO accounts(owner_user_id, account_type, display_name)
SELECT u.user_id, 'WALLET', wallet_name
FROM users u
CROSS JOIN (VALUES ('Main wallet'), ('Savings wallet')) AS wallet(wallet_name)
WHERE u.email LIKE 'seed-user-%@example.test'
  AND NOT EXISTS (
      SELECT 1 FROM accounts a
      WHERE a.owner_user_id = u.user_id AND a.display_name = wallet.wallet_name
  );

WITH wallets AS (
    SELECT account_id, row_number() OVER (ORDER BY account_id) AS wallet_number
    FROM accounts WHERE owner_user_id IS NOT NULL
), cash_in AS (
    SELECT account_id, (SELECT account_id FROM accounts WHERE system_key = 'CASH_IN') AS system_account_id
    FROM wallets
), topups AS (
    INSERT INTO transfers(transfer_type, status, source_account_id, target_account_id, amount_paise)
    SELECT 'TOP_UP', 'POSTED', system_account_id, account_id, 1000000000
    FROM cash_in
    RETURNING transfer_id, source_account_id, target_account_id, amount_paise
)
INSERT INTO ledger_entries(transfer_id, account_id, direction, amount_paise)
SELECT transfer_id, source_account_id, 'DEBIT', amount_paise FROM topups
UNION ALL
SELECT transfer_id, target_account_id, 'CREDIT', amount_paise FROM topups;

WITH wallets AS (
    SELECT account_id, row_number() OVER (ORDER BY account_id) AS wallet_number
    FROM accounts WHERE owner_user_id IS NOT NULL
), wallet_count AS (
    SELECT COUNT(*)::bigint AS count FROM wallets
), numbered_wallets AS (
    SELECT wallet_number, account_id FROM wallets
), generated AS (
    SELECT n,
           source.account_id AS source_account_id,
           target.account_id AS target_account_id
    FROM generate_series(1, 100000) AS n
    CROSS JOIN wallet_count
    JOIN numbered_wallets source ON source.wallet_number = ((n - 1) % wallet_count.count) + 1
    JOIN numbered_wallets target ON target.wallet_number = (n % wallet_count.count) + 1
), created AS (
    INSERT INTO transfers(transfer_type, status, source_account_id, target_account_id, amount_paise, note)
    SELECT 'PEER', 'POSTED', source_account_id, target_account_id, 100, 'Seed transfer ' || n
    FROM generated
    RETURNING transfer_id, source_account_id, target_account_id, amount_paise
)
INSERT INTO ledger_entries(transfer_id, account_id, direction, amount_paise)
SELECT transfer_id, source_account_id, 'DEBIT', amount_paise FROM created
UNION ALL
SELECT transfer_id, target_account_id, 'CREDIT', amount_paise FROM created;

UPDATE accounts a
SET balance_paise = COALESCE((
    SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_paise ELSE -l.amount_paise END)
    FROM ledger_entries l WHERE l.account_id = a.account_id
), 0)
WHERE a.owner_user_id IS NOT NULL OR a.system_key IS NOT NULL;

COMMIT;