-- Run after seed_large.sql. Adds 500,000 posted transfers and 1,000,000 ledger rows.
-- All generated transfers use the first two funded user wallets to make a 100,000-row
-- statement OFFSET measurable on one account.
BEGIN;

ALTER TABLE ledger_entries DISABLE TRIGGER ledger_transfer_balanced;
ALTER TABLE transfers DISABLE TRIGGER posted_transfer_has_ledger;

CREATE TEMP TABLE performance_batch (
    transfer_id BIGINT PRIMARY KEY,
    source_account_id BIGINT NOT NULL,
    target_account_id BIGINT NOT NULL,
    amount_paise BIGINT NOT NULL
) ON COMMIT DROP;

WITH wallet_pair AS (
    SELECT
        (array_agg(account_id ORDER BY account_id))[1] AS source_id,
        (array_agg(account_id ORDER BY account_id))[2] AS target_id
    FROM (
        SELECT account_id
        FROM accounts
        WHERE owner_user_id IS NOT NULL AND balance_paise >= 500000
        ORDER BY account_id
        LIMIT 2
    ) funded_wallets
), inserted AS (
    INSERT INTO transfers(transfer_type, status, source_account_id, target_account_id, amount_paise, note)
    SELECT 'PEER', 'POSTED', pair.source_id, pair.target_id, 1, 'performance seed ' || n
    FROM generate_series(1, 500000) AS n
    CROSS JOIN wallet_pair pair
    RETURNING transfer_id, source_account_id, target_account_id, amount_paise
)
INSERT INTO performance_batch
SELECT transfer_id, source_account_id, target_account_id, amount_paise FROM inserted;

INSERT INTO ledger_entries(transfer_id, account_id, direction, amount_paise)
SELECT transfer_id, source_account_id, 'DEBIT', amount_paise FROM performance_batch
UNION ALL
SELECT transfer_id, target_account_id, 'CREDIT', amount_paise FROM performance_batch;

WITH balance_deltas AS (
    SELECT account_id, SUM(delta) AS delta
    FROM (
        SELECT source_account_id AS account_id, -amount_paise AS delta FROM performance_batch
        UNION ALL
        SELECT target_account_id AS account_id, amount_paise AS delta FROM performance_batch
    ) changes
    GROUP BY account_id
)
UPDATE accounts a
SET balance_paise = a.balance_paise + d.delta
FROM balance_deltas d
WHERE a.account_id = d.account_id;

ALTER TABLE ledger_entries ENABLE TRIGGER ledger_transfer_balanced;
ALTER TABLE transfers ENABLE TRIGGER posted_transfer_has_ledger;

COMMIT;

ANALYZE accounts;
ANALYZE transfers;
ANALYZE ledger_entries;
